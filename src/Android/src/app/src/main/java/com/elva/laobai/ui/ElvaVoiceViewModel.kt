/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.ui

import android.content.Context
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ElvaVoiceVM"

data class ElvaVoiceUiState(
    val isListening: Boolean = false,
    val recognizedText: String = "",
    val responseText: String = "",
    val isThinking: Boolean = false,
    val ttsEnabled: Boolean = true,
    val guardDecision: String? = null,
    val routingRoute: String? = null,
)

@HiltViewModel
class ElvaVoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel(), RecognitionListener {

    private val _uiState = MutableStateFlow(ElvaVoiceUiState())
    val uiState = _uiState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: android.content.Intent? = null

    init {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(this)
            recognizerIntent = android.content.Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
        }
    }

    fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun toggleTts() {
        val newEnabled = !_uiState.value.ttsEnabled
        _uiState.update { it.copy(ttsEnabled = newEnabled) }
        com.elva.laobai.ElvaTtsManager.setEnabled(newEnabled)
    }

    private fun startListening() {
        // Stop TTS when user starts speaking
        com.elva.laobai.ElvaTtsManager.stop()
        _uiState.update {
            it.copy(
                isListening = true,
                recognizedText = "",
                responseText = "",
                isThinking = false,
            )
        }
        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            _uiState.update { it.copy(isListening = false) }
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    // ===== RecognitionListener callbacks =====

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        _uiState.update { it.copy(isListening = false) }
    }

    override fun onError(error: Int) {
        Log.w(TAG, "Speech recognition error: $error")
        _uiState.update {
            it.copy(
                isListening = false,
                responseText = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一遍好吗？"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音，再试一次？"
                    else -> "出了点小问题，再试一次吧"
                },
            )
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        _uiState.update { it.copy(isListening = false, recognizedText = text, isThinking = true) }

        // Send recognized text to Gemma 4 via ElvaInferenceBridge
        processWithGemma4(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        _uiState.update { it.copy(recognizedText = text) }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    /**
     * Process user input through the full Elva pipeline:
     * 1. ScamGuard check (highest priority)
     * 2. ScreenObserver + PrivacyFirewall
     * 3. LocalRouter
     * 4. Gemma 4 inference (if cloud route)
     * 5. SafetyGuard evaluation
     * Falls back to local pattern matching if model is not ready.
     */
    private fun processWithGemma4(userText: String) {
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge

        // Step 1: Run through the full pipeline
        val pipelineResult = com.elva.laobai.sentinel.AlwaysOnSentinel.triggerFullPipeline(userText)

        // Step 2: If guard DENIED, speak the denial immediately (highest priority)
        if (pipelineResult.guardDecision.decision ==
            com.elva.laobai.models.GuardDecision.GuardResult.DENY) {
            val denialMessage = pipelineResult.nextAction.voicePrompt
            _uiState.update { it.copy(isThinking = false, responseText = denialMessage) }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(denialMessage)
            }
            return
        }

        // Step 3: If guard requires confirmation, ask user
        if (pipelineResult.guardDecision.decision ==
            com.elva.laobai.models.GuardDecision.GuardResult.REQUIRE_CONFIRMATION) {
            val confirmMessage = pipelineResult.nextAction.voicePrompt
            _uiState.update { it.copy(isThinking = false, responseText = confirmMessage) }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(confirmMessage)
            }
            return
        }

        // Step 4: Check routing — if LOCAL_ONLY, use local response
        val routing = pipelineResult.routingDecision
        if (routing?.route == com.elva.laobai.models.RoutingDecision.Route.LOCAL_ONLY ||
            routing?.route == com.elva.laobai.models.RoutingDecision.Route.STOP) {

            if (routing.route == com.elva.laobai.models.RoutingDecision.Route.STOP) {
                val stopMessage = pipelineResult.nextAction.voicePrompt
                _uiState.update { it.copy(isThinking = false, responseText = stopMessage) }
                if (_uiState.value.ttsEnabled) {
                    com.elva.laobai.ElvaTtsManager.speak(stopMessage)
                }
                return
            }

            // Local response
            localFallbackResponse(userText)
            return
        }

        // Step 5: Cloud route — try Gemma 4
        if (!bridge.state.value.isModelReady) {
            localFallbackResponse(userText)
            return
        }

        val fullResponse = StringBuilder()

        bridge.infer(
            input = userText,
            onPartialResult = { token ->
                fullResponse.append(token)
                _uiState.update { it.copy(responseText = fullResponse.toString()) }
            },
            onDone = { response ->
                _uiState.update { it.copy(isThinking = false, responseText = response) }
                if (_uiState.value.ttsEnabled) {
                    com.elva.laobai.ElvaTtsManager.speak(response)
                }
            },
            onError = { error ->
                Log.e(TAG, "Gemma 4 inference error: $error")
                localFallbackResponse(userText)
            },
        )
    }

    /**
     * Local fallback response when Gemma 4 is not available.
     * Uses simple pattern matching for common elderly requests.
     */
    private fun localFallbackResponse(userText: String) {
        viewModelScope.launch {
            val response = when {
                userText.contains("打") && userText.contains("电话") -> "好的，正在帮您拨打电话~"
                userText.contains("照片") || userText.contains("相册") -> "好的，帮您打开相册啦~"
                userText.contains("几点") || userText.contains("时间") -> {
                    val sdf = java.text.SimpleDateFormat("HH:mm, EEEE", java.util.Locale.CHINESE)
                    "现在是${sdf.format(java.util.Date())}。"
                }
                userText.contains("你好") || userText.contains("hello", ignoreCase = true) ->
                    "您好呀！我是老白，有什么能帮您的吗？"
                userText.contains("交电费") -> "好的，正在帮您打开交电费页面~"
                userText.contains("交水费") -> "好的，正在帮您打开交水费页面~"
                userText.contains("挂号") -> "好的，正在帮您打开挂号页面~"
                userText.contains("拍照") || userText.contains("照相机") -> "好的，帮您打开相机~"
                userText.contains("转账") || userText.contains("汇款") ->
                    "这很可能是诈骗！请不要转账、不要提供验证码。建议您先跟家人确认一下。"
                else -> "我听到您说\"$userText\"，让我想想怎么帮您~"
            }
            _uiState.update { it.copy(isThinking = false, responseText = response) }

            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(response)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
