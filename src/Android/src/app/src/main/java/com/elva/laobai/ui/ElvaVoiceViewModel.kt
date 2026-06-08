/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elva.laobai.audio.ElvaAudioRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ElvaVoiceUiState())
    val uiState = _uiState.asStateFlow()

    private val audioRecorder = ElvaAudioRecorder()
    private var recordingJob: Job? = null

    /** Pending hospital booking params awaiting user voice confirmation. */
    private var pendingBookHospitalParams: Map<String, String>? = null
    private var awaitingBookingConfirmation: Boolean = false

    private fun showUserMessage(message: String, speak: Boolean = true) {
        val ttsEnabled = _uiState.value.ttsEnabled
        _uiState.update {
            it.copy(
                isListening = false,
                isThinking = false,
                responseText = message,
            )
        }
        if (speak && ttsEnabled) {
            com.elva.laobai.ElvaTtsManager.speak(message)
        }
    }

    /**
     * Mic button: first tap starts recording (red), second tap sends audio to Gemma.
     */
    fun toggleListening() {
        if (_uiState.value.isListening) {
            finishRecordingAndSend()
        } else {
            startRecording()
        }
    }

    fun toggleTts() {
        val newEnabled = !_uiState.value.ttsEnabled
        _uiState.update { it.copy(ttsEnabled = newEnabled) }
        com.elva.laobai.ElvaTtsManager.setEnabled(newEnabled)
    }

    /** Text field / quick chips — send typed text to Gemma. */
    fun submitTextInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        com.elva.laobai.ElvaTtsManager.stop()
        _uiState.update {
            it.copy(
                recognizedText = trimmed,
                isThinking = true,
                responseText = "",
                isListening = false,
            )
        }
        dispatchUserText(trimmed)
    }

    fun processQuickAction(text: String) = submitTextInput(text)

    private fun startRecording() {
        Log.d(TAG, "startRecording")
        com.elva.laobai.ElvaTtsManager.stop()
        _uiState.update {
            it.copy(
                isListening = true,
                recognizedText = "",
                responseText = "",
                isThinking = false,
            )
        }

        recordingJob = viewModelScope.launch {
            val started = audioRecorder.start(viewModelScope)
            if (!started) {
                Log.e(TAG, "AudioRecord failed to start")
                showUserMessage("麦克风启动失败，请检查权限，或用下方文字输入。")
            }
        }
    }

    private fun finishRecordingAndSend() {
        Log.d(TAG, "finishRecordingAndSend")
        _uiState.update {
            it.copy(
                isListening = false,
                isThinking = true,
                recognizedText = "（语音消息）",
                responseText = "",
            )
        }

        viewModelScope.launch {
            recordingJob?.join()
            recordingJob = null
            val wav = audioRecorder.stopToWav()
            if (wav.isEmpty()) {
                showUserMessage("录音太短了，请按住多说几句，或直接打字。")
                return@launch
            }
            sendAudioToGemma(wav)
        }
    }

    private fun sendAudioToGemma(audioWav: ByteArray) {
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge
        val systemPrompt = com.elva.laobai.inference.ElvaFunctions.buildVoiceChatSystemPrompt()

        val doInfer = {
            bridge.inferWithAudio(
                audioWav = audioWav,
                onPartialResult = { chunk ->
                    _uiState.update {
                        it.copy(
                            responseText = it.responseText + chunk,
                            isThinking = false,
                        )
                    }
                },
                onDone = { full ->
                    val reply = full.trim().ifBlank { "大爷，我没听清楚，您再说一遍或用打字？" }
                    _uiState.update { it.copy(isThinking = false, responseText = reply) }
                    if (_uiState.value.ttsEnabled) {
                        com.elva.laobai.ElvaTtsManager.speak(reply)
                    }
                },
                onError = { error ->
                    Log.e(TAG, "sendAudioToGemma error: $error")
                    showUserMessage("语音理解失败：$error。您可以改用下方文字输入。")
                },
            )
        }

        if (bridge.state.value.isModelReady) {
            doInfer()
            return
        }

        bridge.ensureReady(
            systemPrompt = systemPrompt,
            context = context,
            onReady = doInfer,
            onUnavailable = { message ->
                showUserMessage("$message 您也可以先用文字跟老白说话。")
            },
        )
    }

    private fun dispatchUserText(text: String) {
        if (_uiState.value.isHealthConsultation) {
            handleHealthResponse(text)
        } else {
            processWithGemma4(text)
        }
    }
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
        if (routing?.reason?.startsWith("health_query") == true) {
            // triggerFullPipeline already started HealthTriageEngine — reuse its first action
            val stageState = com.elva.laobai.health.HealthTriageEngine.getState()
            _uiState.update {
                it.copy(
                    isThinking = false,
                    isHealthConsultation = true,
                    healthTriageStage = stageState.stage.name,
                    healthTriageQuestion = pipelineResult.nextAction.voicePrompt,
                    responseText = pipelineResult.nextAction.voicePrompt,
                )
            }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(pipelineResult.nextAction.voicePrompt)
            }
            return
        }
        if (isHealthRelatedText(userText)) {
            handleHealthConsultation(userText)
            return
        }

        // Step 4.6: Form filling handling (Case 1)
        if (userText.contains("填表") || userText.contains("填写表单") ||
            userText.contains("帮我填") || userText.contains("填一下")) {
            handleFormFilling(pipelineResult.observation)
            return
        }

        // Step 4.7: Structured device actions (bills, etc.) — planner + executor
        if (isStructuredActionIntent(userText)) {
            runPlannerAction(userText, pipelineResult.observation)
            return
        }

        // Step 5: Default — home voice chat via on-device Gemma (ASR text → LLM → TTS)
        runVoiceChat(userText)
    }

    /**
     * Primary home-screen path: ASR text → on-device Gemma conversation → TTS.
     */
    private fun runVoiceChat(userText: String) {
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge
        val systemPrompt = com.elva.laobai.inference.ElvaFunctions.buildVoiceChatSystemPrompt()

        val doInfer = {
            Log.d(TAG, "runVoiceChat: inferring for '$userText'")
            val responseBuilder = StringBuilder()
            bridge.infer(
                input = userText,
                onPartialResult = { chunk ->
                    responseBuilder.append(chunk)
                    _uiState.update {
                        it.copy(responseText = responseBuilder.toString(), isThinking = false)
                    }
                },
                onDone = { full ->
                    val reply = full.trim().ifBlank { "大爷，我没想好怎么说，您再说一遍好吗？" }
                    Log.d(TAG, "runVoiceChat: done, length=${reply.length}")
                    _uiState.update { it.copy(isThinking = false, responseText = reply) }
                    if (_uiState.value.ttsEnabled) {
                        com.elva.laobai.ElvaTtsManager.speak(reply)
                    }
                },
                onError = { error ->
                    Log.e(TAG, "runVoiceChat error: $error")
                    localFallbackResponse(userText)
                },
            )
        }

        if (bridge.state.value.isModelReady) {
            doInfer()
            return
        }

        Log.d(TAG, "runVoiceChat: model not ready, calling ensureReady")
        bridge.ensureReady(
            systemPrompt = systemPrompt,
            context = context,
            onReady = { doInfer() },
            onUnavailable = { message ->
                Log.w(TAG, "runVoiceChat: ensureReady unavailable: $message")
                showUserMessage(message)
            },
        )
    }

    /** Device automation intents that need structured planning, not free chat. */
    private fun isStructuredActionIntent(text: String): Boolean {
        return text.contains("交电费") || text.contains("交水费") ||
            (text.contains("打开") && (text.contains("相册") || text.contains("相机") || text.contains("照相")))
    }

    private fun runPlannerAction(
        userText: String,
        observation: com.elva.laobai.models.ScreenObservation?,
    ) {
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge
        val runPlanner = {
            com.elva.laobai.router.CloudPlanner.plan(
                observation = observation,
                userText = userText,
                callback = object : com.elva.laobai.router.CloudPlanner.PlannerCallback {
                    override fun onAction(action: com.elva.laobai.models.NextAction) {
                        handlePlannerAction(action, observation)
                    }
                    override fun onFallback(text: String) {
                        _uiState.update { it.copy(isThinking = false, responseText = text) }
                        if (_uiState.value.ttsEnabled) {
                            com.elva.laobai.ElvaTtsManager.speak(text)
                        }
                    }
                    override fun onError(error: String) {
                        Log.e(TAG, "CloudPlanner error: $error")
                        runVoiceChat(userText)
                    }
                },
            )
        }

        if (!bridge.state.value.isModelReady) {
            bridge.ensureReady(
                systemPrompt = com.elva.laobai.inference.ElvaFunctions.buildSystemPromptFragment(),
                context = context,
                onReady = runPlanner,
                onUnavailable = { message ->
                    _uiState.update { it.copy(isThinking = false, responseText = message) }
                    if (_uiState.value.ttsEnabled) {
                        com.elva.laobai.ElvaTtsManager.speak(message)
                    }
                },
            )
        } else {
            runPlanner()
        }
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
        if (awaitingBookingConfirmation) {
            handleBookingConfirmationResponse(userText)
            return
        }

        if (!_uiState.value.isHealthConsultation) {
            processWithGemma4(userText)
            return
        }

        _uiState.update { it.copy(isThinking = true) }

        viewModelScope.launch {
            val nextAction = com.elva.laobai.health.HealthTriageEngine.processUserResponse(userText)
            val stageState = com.elva.laobai.health.HealthTriageEngine.getState()
            val isComplete = stageState.stage == com.elva.laobai.health.HealthTriageEngine.Stage.COMPLETE

            // If stage is CLOUD_PLANNING, speak transition prompt then run planner
            if (stageState.stage == com.elva.laobai.health.HealthTriageEngine.Stage.CLOUD_PLANNING) {
                _uiState.update {
                    it.copy(
                        isThinking = true,
                        healthTriageStage = stageState.stage.name,
                        healthTriageQuestion = nextAction.voicePrompt,
                        responseText = nextAction.voicePrompt,
                    )
                }
                if (_uiState.value.ttsEnabled) {
                    com.elva.laobai.ElvaTtsManager.speak(nextAction.voicePrompt)
                }
                delay(1500)

                val cloudRequest = com.elva.laobai.health.HealthTriageEngine.buildCloudRequest()
                com.elva.laobai.health.HealthCloudPlanner.plan(
                    request = cloudRequest,
                    onResult = { response -> handleHealthPlannerResponse(response) },
                    onError = { error ->
                        Log.e(TAG, "Cloud planner error: $error")
                        _uiState.update {
                            it.copy(isThinking = false, responseText = "抱歉，规划失败了，建议您直接联系医院挂号。")
                        }
                    },
                    onFallback = { fallback -> handleHealthPlannerResponse(fallback) },
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
                val action = com.elva.laobai.forms.FormFillEngine.getNextAction()
                if (action == null) {
                    // Action queue exhausted — includes stop-before-submit prompts
                    val currentState = com.elva.laobai.forms.FormFillEngine.getFillState()
                    _uiState.update {
                        it.copy(
                            isFormFilling = false,
                            formProgress = "${currentState.filledFields}/${currentState.totalFields} 已填写",
                        )
                    }
                    return@launch
                }

                val currentState = com.elva.laobai.forms.FormFillEngine.getFillState()
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
     * Handle cloud/local health planner response — ask before booking when required.
     */
    private fun handleHealthPlannerResponse(response: com.elva.laobai.models.CloudPlannerResponse) {
        val action = com.elva.laobai.health.HealthTriageEngine.handleCloudResponse(response)
        _uiState.update {
            it.copy(
                isThinking = false,
                healthTriageStage = com.elva.laobai.health.HealthTriageEngine.getState().stage.name,
                responseText = action.voicePrompt,
            )
        }
        if (_uiState.value.ttsEnabled) {
            com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
        }

        if (action.action == com.elva.laobai.models.NextAction.ActionType.ASK_CONFIRMATION &&
            response.task?.intent == "book_hospital") {
            pendingBookHospitalParams = enrichBookingParams(response.task.parameters)
            awaitingBookingConfirmation = true
            _uiState.update { it.copy(isHealthConsultation = true) }
            return
        }

        if (response.task?.intent == "book_hospital" && !response.requiresConfirmation) {
            triggerBookHospital(enrichBookingParams(response.task.parameters))
            _uiState.update { it.copy(isHealthConsultation = false) }
            return
        }

        _uiState.update { it.copy(isHealthConsultation = false) }
    }

    private fun handleBookingConfirmationResponse(userText: String) {
        _uiState.update { it.copy(isThinking = true) }
        if (isAffirmative(userText)) {
            awaitingBookingConfirmation = false
            val params = pendingBookHospitalParams ?: emptyMap()
            pendingBookHospitalParams = null
            _uiState.update { it.copy(isThinking = false, isHealthConsultation = false) }
            triggerBookHospital(params)
        } else {
            awaitingBookingConfirmation = false
            pendingBookHospitalParams = null
            val msg = "没关系大爷，如果感觉不舒服得厉害，随时叫老白帮您挂号！"
            _uiState.update {
                it.copy(isThinking = false, isHealthConsultation = false, responseText = msg)
            }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(msg)
            }
        }
    }

    /**
     * Execute a planner-produced action: speak-only or run through ActionExecutor.
     */
    private fun handlePlannerAction(
        action: com.elva.laobai.models.NextAction,
        observation: com.elva.laobai.models.ScreenObservation?,
    ) {
        val guardDecision = com.elva.laobai.guard.SafetyGuard.evaluate(action, observation)
        if (guardDecision.decision == com.elva.laobai.models.GuardDecision.GuardResult.DENY) {
            val msg = action.voicePrompt.ifBlank { guardDecision.safeAlternative ?: "老白建议您不要继续这个操作。" }
            _uiState.update { it.copy(isThinking = false, responseText = msg) }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(msg)
            }
            return
        }
        if (guardDecision.decision == com.elva.laobai.models.GuardDecision.GuardResult.REQUIRE_CONFIRMATION ||
            action.action == com.elva.laobai.models.NextAction.ActionType.SPEAK_ONLY ||
            action.action == com.elva.laobai.models.NextAction.ActionType.ASK_CONFIRMATION) {
            _uiState.update { it.copy(isThinking = false, responseText = action.voicePrompt) }
            if (_uiState.value.ttsEnabled) {
                com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
            }
            return
        }
        executeAction(action)
    }

    private fun enrichBookingParams(params: Map<String, String>): Map<String, String> {
        val enriched = params.toMutableMap()
        if (enriched["hospital"].isNullOrBlank()) {
            com.elva.laobai.memory.LocalUserMemory.state.value["preferred_hospital"]
                ?.takeIf { it.isNotBlank() }
                ?.let { enriched["hospital"] = it }
        }
        if (enriched["department"].isNullOrBlank()) {
            com.elva.laobai.health.HealthTriageEngine.getState().recommendedDepartment
                ?.let { enriched["department"] = it }
        }
        return enriched
    }

    private fun isAffirmative(text: String): Boolean {
        val lowerText = text.lowercase()
        val affirmativeWords = listOf(
            "好", "可以", "行", "是的", "嗯", "对", "没错",
            "要", "需要", "想", "挂", "yes", "ok", "sure",
        )
        return affirmativeWords.any { lowerText.contains(it) }
    }

    /**
     * Trigger hospital booking via WeChat accessibility flow (BookHospitalTask).
     */
    private fun triggerBookHospital(params: Map<String, String>) {
        viewModelScope.launch {
            delay(500)
            val taskParams = enrichBookingParams(params)
            _uiState.update {
                it.copy(isExecuting = true, executionStatus = "正在帮您打开挂号页面...")
            }
            com.elva.laobai.accessibility.A11yTaskExecutor.execute(
                taskType = com.elva.laobai.accessibility.A11yTaskExecutor.TaskType.BOOK_HOSPITAL,
                params = taskParams,
                context = context,
            ) { success, message ->
                val msg = if (success) {
                    "挂号页面已打开，请您仔细核对信息后自己确认挂号。"
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
        audioRecorder.cancel()
    }
}
