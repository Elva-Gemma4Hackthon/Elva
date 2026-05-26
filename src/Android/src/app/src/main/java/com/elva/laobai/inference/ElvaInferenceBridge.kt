/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridge between ElvaVoiceViewModel and the Gemma 4 inference engine.
 * Manages model initialization, inference, and streaming responses.
 */
object ElvaInferenceBridge {
    private const val TAG = "ElvaInference"

    data class InferenceState(
        val isModelReady: Boolean = false,
        val modelName: String = "",
        val isInitializing: Boolean = false,
    )

    private val _state = MutableStateFlow(InferenceState())
    val state = _state.asStateFlow()

    private var currentModel: Model? = null
    private var isInitialized = false

    /**
     * Initialize the Gemma 4 model for Elva voice assistant.
     * @param model The downloaded model to use.
     * @param systemPrompt The system prompt (Lao Bai persona).
     * @param context Android context.
     * @param onReady Called when model is ready for inference.
     */
    fun initialize(
        model: Model,
        systemPrompt: String,
        context: Context,
        onReady: () -> Unit,
    ) {
        if (isInitialized && currentModel?.name == model.name) {
            onReady()
            return
        }

        currentModel = model
        _state.value = InferenceState(
            isInitializing = true,
            modelName = model.name,
        )

        val elvaSystemPrompt = Contents.of(systemPrompt)

        CoroutineScope(Dispatchers.Default).launch {
            LlmChatModelHelper.initialize(
                context = context,
                model = model,
                taskId = "elva_voice",
                supportImage = true,
                supportAudio = true,
                onDone = { error ->
                    _state.value = _state.value.copy(
                        isInitializing = false,
                        isModelReady = error.isEmpty(),
                    )
                    if (error.isEmpty()) {
                        isInitialized = true
                        Log.d(TAG, "Gemma 4 model initialized for Elva")
                        onReady()
                    } else {
                        Log.e(TAG, "Model init error: $error")
                    }
                },
                systemInstruction = elvaSystemPrompt,
                tools = listOf(),
            )
        }
    }

    /**
     * Run inference on user input and stream the response.
     * @param input The user's recognized speech text.
     * @param onPartialResult Called with each token of the response.
     * @param onDone Called when inference is complete with the full response.
     * @param onError Called if inference fails.
     */
    fun infer(
        input: String,
        onPartialResult: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val model = currentModel
        if (model == null || model.instance == null) {
            onError("模型未就绪，请稍等")
            return
        }

        val instance = model.instance as LlmModelInstance
        val conversation = instance.conversation

        val content = mutableListOf<com.google.ai.edge.litertlm.Content>()
        content.add(com.google.ai.edge.litertlm.Content.Text(input))

        val fullResponse = StringBuilder()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                conversation
                    .sendMessageAsync(Contents.of(content))
                    .collect { chunk ->
                        val text = chunk.toString()
                        fullResponse.append(text)
                        onPartialResult(text)
                    }

                onDone(fullResponse.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Inference error", e)
                onError(e.message ?: "推理失败")
            }
        }
    }

    /** Reset the conversation context. */
    fun resetConversation(systemPrompt: String) {
        val model = currentModel ?: return
        LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = true,
            supportAudio = true,
            systemInstruction = Contents.of(systemPrompt),
        )
    }

    /** Clean up model resources. */
    fun cleanUp(onDone: () -> Unit = {}) {
        val model = currentModel ?: return
        isInitialized = false
        _state.value = InferenceState()
        LlmChatModelHelper.cleanUp(model) {
            currentModel = null
            onDone()
        }
    }
}
