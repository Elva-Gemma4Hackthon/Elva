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
    val isExecuting: Boolean = false,
    val ttsEnabled: Boolean = true,
    val guardDecision: String? = null,
    val routingRoute: String? = null,
    val executionStatus: String? = null,
    // Form filling state (Case 1)
    val isFormFilling: Boolean = false,
    val formTemplateName: String? = null,
    val formProgress: String? = null,
    // Health consultation state (Case 2)
    val isHealthConsultation: Boolean = false,
    val healthTriageStage: String? = null,
    val healthTriageQuestion: String? = null,
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

    /**
     * Process a quick action chip click — directly inject text
     * into the pipeline without requiring microphone input.
     */
    fun processQuickAction(text: String) {
        _uiState.update {
            it.copy(
                recognizedText = text,
                isThinking = true,
                responseText = "",
            )
        }
        processWithGemma4(text)
    }

    private fun startListening() {
        Log.d(TAG, "startListening: begin")
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
            Log.d(TAG, "startListening: SpeechRecognizer.startListening called")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            _uiState.update { it.copy(isListening = false) }
        }
    }

    private fun stopListening() {
        Log.d(TAG, "stopListening: requested")
        speechRecognizer?.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    // ===== RecognitionListener callbacks =====

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "onReadyForSpeech")
    }
    override fun onBeginningOfSpeech() {
        Log.d(TAG, "onBeginningOfSpeech")
    }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        Log.d(TAG, "onEndOfSpeech")
        _uiState.update { it.copy(isListening = false) }
    }

    override fun onError(error: Int) {
        Log.w(TAG, "Speech recognition error: $error")
        _uiState.update {
            it.copy(
                isListening = false,
                isThinking = false,
                responseText = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一遍好吗？"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音，再试一次？"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请先允许麦克风权限，再试一次。"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常，请检查网络后重试。"
                    else -> "出了点小问题，再试一次吧"
                },
            )
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.trim().orEmpty()
        Log.d(TAG, "onResults: recognized='$text'")
        if (text.isEmpty()) {
            _uiState.update {
                it.copy(
                    isListening = false,
                    isThinking = false,
                    recognizedText = "",
                    responseText = "没听清，再说一遍好吗？",
                )
            }
            return
        }

        _uiState.update { it.copy(isListening = false, recognizedText = text, isThinking = true) }

        // If in health consultation mode, route to health handler (Case 2)
        if (_uiState.value.isHealthConsultation) {
            handleHealthResponse(text)
        } else {
            processWithGemma4(text)
        }
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
        Log.d(TAG, "processWithGemma4: input='$userText'")
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge

        // Step 1: Run through the full pipeline
        val pipelineResult = com.elva.laobai.sentinel.AlwaysOnSentinel.triggerFullPipeline(userText)
        Log.d(
            TAG,
            "processWithGemma4: route=${pipelineResult.routingDecision?.route}, reason=${pipelineResult.routingDecision?.reason}, guard=${pipelineResult.guardDecision.decision}",
        )

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

        val routing = pipelineResult.routingDecision

        // Step 4 (highest priority): STOP route
        if (routing?.route == com.elva.laobai.models.RoutingDecision.Route.STOP) {
            val stopMessage = pipelineResult.nextAction.voicePrompt
            _uiState.update { it.copy(isThinking = false, responseText = stopMessage) }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(stopMessage)
            }
            return
        }

        // Step 4.5: Health consultation handling (Case 2)
        // Must be checked BEFORE LOCAL_ONLY because health queries often route as LOCAL_ONLY
        if (routing?.reason?.startsWith("health_query") == true ||
            isHealthRelatedText(userText)) {
            handleHealthConsultation(userText)
            return
        }

        // Step 4.6: Form filling handling (Case 1)
        if (userText.contains("填表") || userText.contains("填写表单") ||
            userText.contains("帮我填") || userText.contains("填一下")) {
            handleFormFilling(pipelineResult.observation)
            return
        }

        // Step 4.7: LOCAL_ONLY route — execute or speak locally
        if (routing?.route == com.elva.laobai.models.RoutingDecision.Route.LOCAL_ONLY) {
            val action = pipelineResult.nextAction
            if (pipelineResult.guardDecision.decision == com.elva.laobai.models.GuardDecision.GuardResult.ALLOW &&
                action.action != com.elva.laobai.models.NextAction.ActionType.SPEAK_ONLY) {
                executeAction(action)
                return
            }
            localFallbackResponse(userText)
            return
        }

        // Step 5: Cloud route - try Gemma 4 via CloudPlanner
        val runGemma4 = {
            Log.d(TAG, "processWithGemma4: invoking CloudPlanner.plan")
            com.elva.laobai.router.CloudPlanner.plan(
                observation = pipelineResult.observation,
                userText = userText,
                callback = object : com.elva.laobai.router.CloudPlanner.PlannerCallback {
                    override fun onAction(action: com.elva.laobai.models.NextAction) {
                        _uiState.update { it.copy(isThinking = false, responseText = action.voicePrompt) }
                        if (_uiState.value.ttsEnabled) {
                            com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
                        }
                    }
                    override fun onFallback(text: String) {
                        _uiState.update { it.copy(isThinking = false, responseText = text) }
                        if (_uiState.value.ttsEnabled) {
                            com.elva.laobai.ElvaTtsManager.speak(text)
                        }
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "CloudPlanner error: $error")
                        localFallbackResponse(userText)
                    }
                },
            )
        }

        if (!bridge.state.value.isModelReady) {
            Log.d(TAG, "processWithGemma4: model not ready, calling ensureReady")
            bridge.ensureReady(
                systemPrompt = com.elva.laobai.inference.ElvaFunctions.buildSystemPromptFragment(),
                context = context,
                onReady = {
                    Log.d(TAG, "processWithGemma4: ensureReady succeeded")
                    runGemma4()
                },
                onUnavailable = { message ->
                    Log.w(TAG, "processWithGemma4: ensureReady unavailable: $message")
                    _uiState.update { it.copy(isThinking = false, responseText = message) }
                    if (_uiState.value.ttsEnabled) {
                        com.elva.laobai.ElvaTtsManager.speak(message)
                    }
                },
            )
            return
        }

        Log.d(TAG, "processWithGemma4: model already ready")
        runGemma4()
    }

    /**
     * Quick check if user text is health-related (for routing priority).
     * Duplicates HealthTriageEngine keywords for fast pre-check.
     */
    private fun isHealthRelatedText(text: String): Boolean {
        val healthKeywords = listOf(
            "不舒服", "疼", "痛", "难受", "头晕", "恶心",
            "发烧", "咳嗽", "胸闷", "胃", "肚子", "腰",
            "腿", "头", "嗓子", "看病", "医院", "挂号",
            "症状", "过敏", "痒", "出血", "肿", "晕",
        )
        return healthKeywords.any { text.contains(it) }
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

    /**
     * V5: Execute an action via ActionExecutor.
     * Shows progress to the user via voice and UI.
     */
    private fun executeAction(action: com.elva.laobai.models.NextAction) {
        _uiState.update {
            it.copy(
                isThinking = false,
                isExecuting = true,
                executionStatus = "正在执行: ${action.voicePrompt}",
                responseText = action.voicePrompt,
            )
        }

        // Announce the action
        if (_uiState.value.ttsEnabled) {
            com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
        }

        com.elva.laobai.executor.ActionExecutor.execute(
            action = action,
            context = context,
        ) { result ->
            val statusMsg = if (result.success) {
                "操作完成!"
            } else {
                "操作未成功: ${result.message}"
            }

            _uiState.update {
                it.copy(
                    isExecuting = false,
                    executionStatus = statusMsg,
                    responseText = if (result.success) "${action.voicePrompt}\n\n$statusMsg" else statusMsg,
                )
            }

            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(statusMsg)
            }
        }
    }

    /**
     * Handle health consultation — start the 6-stage triage state machine.
     * Case 2: Trigger-based health consultation + cloud registration.
     */
    private fun handleHealthConsultation(userText: String) {
        val question = com.elva.laobai.health.HealthTriageEngine.startConsultation(userText)
        val stageState = com.elva.laobai.health.HealthTriageEngine.getState()
        _uiState.update {
            it.copy(
                isThinking = false,
                isHealthConsultation = true,
                healthTriageStage = stageState.stage.name,
                healthTriageQuestion = question.voicePrompt,
                responseText = question.voicePrompt,
            )
        }
        if (_uiState.value.ttsEnabled) {
            com.elva.laobai.ElvaTtsManager.speak(question.voicePrompt)
        }
    }

    /**
     * Handle user response during health consultation.
     * Advances the HealthTriageEngine state machine.
     * Call this from onResults() when isHealthConsultation is true.
     */
    fun handleHealthResponse(userText: String) {
        if (!_uiState.value.isHealthConsultation) {
            processWithGemma4(userText)
            return
        }

        _uiState.update { it.copy(isThinking = true) }

        viewModelScope.launch {
            val nextAction = com.elva.laobai.health.HealthTriageEngine.processUserResponse(userText)
            val stageState = com.elva.laobai.health.HealthTriageEngine.getState()
            val isComplete = stageState.stage == com.elva.laobai.health.HealthTriageEngine.Stage.COMPLETE

            // If stage is CLOUD_PLANNING, trigger cloud planner
            if (stageState.stage == com.elva.laobai.health.HealthTriageEngine.Stage.CLOUD_PLANNING) {
                val cloudRequest = com.elva.laobai.health.HealthTriageEngine.buildCloudRequest()
                com.elva.laobai.health.HealthCloudPlanner.plan(
                    request = cloudRequest,
                    onResult = { response ->
                        _uiState.update {
                            it.copy(
                                isThinking = false,
                                healthTriageStage = "COMPLETE",
                                isHealthConsultation = false,
                                responseText = response.userExplanation,
                            )
                        }
                        if (_uiState.value.ttsEnabled) {
                            com.elva.laobai.ElvaTtsManager.speak(response.userExplanation)
                        }
                        // If cloud planner recommends booking, trigger book_hospital skill
                        if (response.task?.intent == "book_hospital") {
                            triggerBookHospital(response.task.parameters)
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "Cloud planner error: $error")
                        _uiState.update {
                            it.copy(isThinking = false, responseText = "抱歉，云端规划失败了，建议您直接联系医院挂号。")
                        }
                    },
                    onFallback = { fallback ->
                        _uiState.update {
                            it.copy(
                                isThinking = false,
                                healthTriageStage = "COMPLETE",
                                isHealthConsultation = false,
                                responseText = fallback.userExplanation,
                            )
                        }
                        if (_uiState.value.ttsEnabled) {
                            com.elva.laobai.ElvaTtsManager.speak(fallback.userExplanation)
                        }
                        // If fallback response also recommends booking, trigger it
                        if (fallback.task?.intent == "book_hospital") {
                            triggerBookHospital(fallback.task.parameters)
                        }
                    },
                )
                return@launch
            }

            _uiState.update {
                it.copy(
                    isThinking = false,
                    healthTriageStage = stageState.stage.name,
                    healthTriageQuestion = nextAction.voicePrompt,
                    responseText = nextAction.voicePrompt,
                    isHealthConsultation = !isComplete,
                )
            }

            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(nextAction.voicePrompt)
            }
        }
    }

    /**
     * Handle form filling — trigger the form fill engine.
     * Case 1: Always-on fixed form filling assistant.
     * Executes all fill actions sequentially until every field is complete.
     */
    private fun handleFormFilling(observation: com.elva.laobai.models.ScreenObservation?) {
        val fillState = com.elva.laobai.sentinel.AlwaysOnSentinel.startFormFilling()
        if (fillState == null) {
            _uiState.update {
                it.copy(isThinking = false, responseText = "抱歉，我还不认识这个表单，不能帮您自动填写。")
            }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak("抱歉，我还不认识这个表单，不能帮您自动填写。")
            }
            return
        }

        val templateName = fillState.templateName ?: "表单"
        val intro = "好的，老白来帮您填写${templateName}，一共${fillState.totalFields}项，您看着就行~"
        _uiState.update {
            it.copy(
                isThinking = false,
                isFormFilling = true,
                formTemplateName = templateName,
                formProgress = "0/${fillState.totalFields} 已填写",
                responseText = intro,
            )
        }
        if (_uiState.value.ttsEnabled) {
            com.elva.laobai.ElvaTtsManager.speak(intro)
        }

        // Begin sequential execution loop
        executeFormActionsSequentially()
    }

    /**
     * Sequentially execute form fill actions one by one.
     * After each action completes, updates progress and triggers the next action.
     */
    private fun executeFormActionsSequentially() {
        viewModelScope.launch {
            var consecutiveErrors = 0
            while (consecutiveErrors < 3) {
                val currentState = com.elva.laobai.forms.FormFillEngine.getFillState()
                if (currentState.filledFields >= currentState.totalFields) {
                    // All fields filled
                    val templateName = currentState.templateName ?: "表单"
                    val doneMsg = "${templateName}填写完成了！您检查一下看看对不对~"
                    _uiState.update {
                        it.copy(
                            isFormFilling = false,
                            formProgress = "${currentState.filledFields}/${currentState.totalFields} 已填写",
                            responseText = doneMsg,
                        )
                    }
                    if (_uiState.value.ttsEnabled) {
                        com.elva.laobai.ElvaTtsManager.speak(doneMsg)
                    }
                    return@launch
                }

                val action = com.elva.laobai.forms.FormFillEngine.getNextAction()
                if (action == null) {
                    consecutiveErrors++
                    kotlinx.coroutines.delay(500)
                    continue
                }

                // Update progress UI
                val progress = "${currentState.filledFields}/${currentState.totalFields} 已填写"
                _uiState.update {
                    it.copy(
                        formProgress = progress,
                        responseText = action.voicePrompt,
                    )
                }

                // Execute the single fill action
                val latch = java.util.concurrent.CountDownLatch(1)
                var success = false
                com.elva.laobai.executor.ActionExecutor.execute(
                    action = action,
                    context = context,
                ) { result ->
                    success = result.success
                    latch.countDown()
                }

                // Wait for the action to complete (with timeout)
                try {
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {
                    // Timeout — continue to next action
                }

                if (success) {
                    consecutiveErrors = 0
                } else {
                    consecutiveErrors++
                }

                // Small delay between actions for UI refresh
                kotlinx.coroutines.delay(800)
            }

            // Too many consecutive errors — bail out
            _uiState.update {
                it.copy(
                    isFormFilling = false,
                    responseText = "填表遇到点困难，您可以手动完成剩下的部分。",
                )
            }
        }
    }

    /**
     * Trigger hospital booking after health consultation cloud planning.
     * Uses the fixed demo accessibility task path for Case 2.
     */
    private fun triggerBookHospital(params: Map<String, String>) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            _uiState.update {
                it.copy(
                    isExecuting = true,
                    executionStatus = "正在打开挂号流程...",
                )
            }
            com.elva.laobai.accessibility.A11yTaskExecutor.execute(
                taskType = com.elva.laobai.accessibility.A11yTaskExecutor.TaskType.BOOK_HOSPITAL,
                params = params,
                context = context,
            ) { success, message ->
                val msg = if (success) {
                    "已进入挂号流程，请您在最终确认挂号前自己检查并确认。"
                } else {
                    "挂号未成功: $message"
                }
                _uiState.update {
                    it.copy(
                        isExecuting = false,
                        executionStatus = msg,
                        responseText = msg,
                    )
                }
                if (_uiState.value.ttsEnabled) {
                    com.elva.laobai.ElvaTtsManager.speak(msg)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
