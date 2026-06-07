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
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.ScreenObservation
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
        val lastError: String? = null,
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
        Log.d(TAG, "initialize: requested model=${model.name}, alreadyInitialized=$isInitialized, currentModel=${currentModel?.name}")
        if (isInitialized && currentModel?.name == model.name) {
            Log.d(TAG, "initialize: reuse existing initialized model ${model.name}")
            onReady()
            return
        }

        currentModel = model
        _state.value = InferenceState(
            isInitializing = true,
            modelName = model.name,
            lastError = null,
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
                        lastError = error.ifEmpty { null },
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
    fun ensureReady(
        systemPrompt: String,
        context: Context,
        onReady: () -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        Log.d(TAG, "ensureReady: ready=${_state.value.isModelReady}, initializing=${_state.value.isInitializing}, currentModel=${currentModel?.name}, lastError=${_state.value.lastError}")
        if (_state.value.isModelReady) {
            onReady()
            return
        }
        if (_state.value.isInitializing) {
            onUnavailable("AI 模型正在加载，请稍候")
            return
        }

        val model = currentModel
        if (model == null) {
            Log.w(TAG, "ensureReady: no current model available")
            onUnavailable("未找到可用的 Gemma 模型，请先下载并加载模型")
            return
        }

        Log.d(TAG, "ensureReady: initializing model ${model.name}")
        initialize(
            model = model,
            systemPrompt = systemPrompt,
            context = context,
            onReady = onReady,
        )
    }

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

    // ===== Function Calling (V2) =====

    /**
     * Run inference with Function Calling support.
     * Sends the screen observation + user text to the model,
     * then parses the structured output into a NextAction.
     *
     * Falls back to a local response if the model output
     * cannot be parsed as a valid action.
     *
     * @param observation The current screen observation (redacted).
     * @param userText The user's voice input.
     * @param onAction Called with the parsed NextAction.
     * @param onFallback Called with a fallback voice response if parsing fails.
     * @param onError Called if inference fails entirely.
     */
    fun inferWithFunctions(
        observation: ScreenObservation?,
        userText: String,
        onAction: (NextAction) -> Unit,
        onFallback: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val model = currentModel
        if (model == null || model.instance == null) {
            onError("模型未就緒")
            return
        }

        val instance = model.instance as LlmModelInstance
        val conversation = instance.conversation

        // Build the prompt with observation context
        val prompt = buildFunctionCallPrompt(observation, userText)
        val content = mutableListOf<com.google.ai.edge.litertlm.Content>()
        content.add(com.google.ai.edge.litertlm.Content.Text(prompt))

        val fullResponse = StringBuilder()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                conversation
                    .sendMessageAsync(Contents.of(content))
                    .collect { chunk ->
                        fullResponse.append(chunk.toString())
                    }

                val responseText = fullResponse.toString().trim()
                Log.d(TAG, "Function call response: $responseText")

                // Try to parse as structured action
                val action = OutputValidator.parseAndValidate(responseText)
                if (action != null) {
                    Log.d(TAG, "Parsed action: ${action.action}, target=${action.targetDescription}")
                    onAction(action)
                } else {
                    // Use the raw text as a fallback voice response
                    Log.d(TAG, "Could not parse as action, using as fallback text")
                    onFallback(responseText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Function call inference error", e)
                onError(e.message ?: "推理失败")
            }
        }
    }

    /**
     * Build the prompt for function calling inference.
     * Combines the system prompt with current screen context.
     */
    private fun buildFunctionCallPrompt(
        observation: ScreenObservation?,
        userText: String,
    ): String {
        val sb = StringBuilder()

        // Screen context
        if (observation != null) {
            sb.appendLine("【当前屏幕信息】")
            sb.appendLine("页面类型: ${observation.pageType}")
            sb.appendLine("敏感字段: ${observation.sensitiveFieldCategories.joinToString(", ")}")
            sb.appendLine("涉及付款: ${if (observation.hasPaymentKeyword) "是" else "否"}")
            sb.appendLine("有验证码: ${if (observation.hasOtpField) "是" else "否"}")
            sb.appendLine("有授权请求: ${if (observation.hasAuthorizationRequest) "是" else "否"}")
            if (observation.fraudIndicators.isNotEmpty()) {
                sb.appendLine("诈骗指标: ${observation.fraudIndicators.joinToString(", ")}")
            }
            sb.appendLine()
            sb.appendLine("【页面元素】")
            for (el in observation.uiElements.take(30)) {
                val flags = mutableListOf<String>()
                if (el.isClickable) flags.add("可点击")
                if (el.isEditable) flags.add("可输入")
                if (el.isRedacted) flags.add("已脱敏")
                val flagStr = if (flags.isNotEmpty()) " [${flags.joinToString(",")}]" else ""
                sb.appendLine("- (${el.type}${flagStr}) ${el.text}")
            }
            sb.appendLine()
        }

        // User input
        sb.appendLine("【老人说的话】")
        sb.appendLine(userText)
        sb.appendLine()
        sb.appendLine("请选择最合适的工具并回复JSON格式。")

        return sb.toString()
    }
}
