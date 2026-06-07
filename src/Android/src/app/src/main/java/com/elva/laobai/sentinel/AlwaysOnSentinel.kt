/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.sentinel

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.elva.laobai.accessibility.ElvaAccessibilityService
import com.elva.laobai.guard.ScamGuard
import com.elva.laobai.guard.SafetyGuard
import com.elva.laobai.models.EdgeEvent
import com.elva.laobai.models.GuardDecision
import com.elva.laobai.models.GuardDecision.GuardResult
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.observer.ScreenObserver
import com.elva.laobai.privacy.PrivacyFirewall
import com.elva.laobai.router.CloudPlanner
import com.elva.laobai.router.LocalRouter
import com.elva.laobai.forms.FormTemplateMatcher
import com.elva.laobai.forms.FormFillEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Always On Sentinel — monitors accessibility events in the background
 * and triggers the Elva pipeline when risky situations are detected.
 *
 * From PPT Slide 5:
 * - "Always On Sentinel: 事件驱动，不持续录屏；只在风险或停滞时轻量提醒。"
 * - "Trigger Assistant: 用户点击悬浮按钮、通知或语音入口后主动触发。"
 *
 * Two modes:
 * 1. SENTINEL (Always On): Passive monitoring, reacts to risk events
 * 2. TRIGGER (On Demand): User-initiated via voice/button
 */
object AlwaysOnSentinel {
    private const val TAG = "ElvaSentinel"

    /** Minimum interval between automatic scans (ms). */
    private const val SCAN_COOLDOWN_MS = 3000L

    data class SentinelState(
        val mode: SentinelMode = SentinelMode.SENTINEL,
        val isActive: Boolean = false,
        val lastAlertTime: Long = 0,
        val lastScanTime: Long = 0,
        val lastEvent: EdgeEvent? = null,
        val lastObservation: ScreenObservation? = null,
        val lastGuardDecision: GuardDecision? = null,
    )

    enum class SentinelMode {
        /** Passive monitoring — only alerts on risk. */
        SENTINEL,
        /** Active mode — user triggered, full pipeline. */
        TRIGGER,
    }

    private val _state = MutableStateFlow(SentinelState())
    val state = _state.asStateFlow()

    private var isMonitoring = false

    /**
     * Start the sentinel monitoring loop.
     */
    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        _state.value = _state.value.copy(isActive = true)
        Log.d(TAG, "Always On Sentinel started")

        CoroutineScope(Dispatchers.Default).launch {
            while (isMonitoring) {
                try {
                    // Only scan if accessibility service is running
                    if (ElvaAccessibilityService.isRunning()) {
                        performPassiveScan()
                    }
                    delay(SCAN_COOLDOWN_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Sentinel scan error", e)
                    delay(SCAN_COOLDOWN_MS * 2)
                }
            }
        }
    }

    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        isMonitoring = false
        _state.value = _state.value.copy(isActive = false)
        Log.d(TAG, "Always On Sentinel stopped")
    }

    /**
     * Receive accessibility events from ElvaAccessibilityService.
     * Used for event-driven risk detection without continuous screen recording.
     *
     * From PPT: "事件驱动，不持续录屏；只在风险或停滞时轻量提醒。"
     */
    private val pendingEvents = mutableListOf<PendingEvent>()
    private val EVENT_BATCH_SIZE = 10

    data class PendingEvent(
        val eventType: Int,
        val packageName: String,
        val text: String,
        val className: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    fun onAccessibilityEvent(
        eventType: Int,
        packageName: String,
        text: String,
        className: String,
    ) {
        // Only process events from third-party apps (not our own)
        if (packageName.startsWith("com.elva.laobai") ||
            packageName.startsWith("com.google.ai.edge.gallery")) {
            return
        }

        val event = PendingEvent(eventType, packageName, text, className)

        // Quick risk check on every event (lightweight)
        val combinedText = text.lowercase()
        val hasHighRiskKeyword = combinedText.contains("验证码") && combinedText.contains("付款") ||
            combinedText.contains("转账") || combinedText.contains("涉嫌违规")

        if (hasHighRiskKeyword) {
            Log.w(TAG, "High-risk event detected from $packageName: $text")
            // Trigger immediate observation and scan
            CoroutineScope(Dispatchers.Default).launch {
                performPassiveScan()
            }
        }

        // Store for batch processing
        synchronized(pendingEvents) {
            pendingEvents.add(event)
            if (pendingEvents.size >= EVENT_BATCH_SIZE) {
                pendingEvents.clear()
            }
        }
    }

    /**
     * Switch between sentinel and trigger mode.
     */
    fun setMode(mode: SentinelMode) {
        _state.value = _state.value.copy(mode = mode)
        Log.d(TAG, "Mode changed to: $mode")
    }

    /**
     * Passive scan — only triggers alerts for high-risk situations.
     * Does NOT activate on every screen change (PPT: "不持续录屏").
     */
    private suspend fun performPassiveScan() {
        val now = System.currentTimeMillis()
        if (now - _state.value.lastScanTime < SCAN_COOLDOWN_MS) return
        _state.value = _state.value.copy(lastScanTime = now)

        // Observe current screen
        val observation = ScreenObserver.observe() ?: return

        // Check for fraud indicators — highest priority
        if (observation.fraudIndicators.isNotEmpty()) {
            val allText = observation.uiElements.joinToString(" ") { it.text }
            val scamResult = ScamGuard.analyze(allText)
            if (scamResult != null) {
                triggerAlert(
                    event = EdgeEvent(
                        eventType = "fraud_detected",
                        source = "sentinel_passive_scan",
                        triggerKeywords = observation.fraudIndicators,
                        hasSensitiveData = true,
                        confidence = 0.9f,
                    ),
                    observation = observation,
                    guardDecision = scamResult,
                )
                return
            }
        }

        // Check for payment + OTP combination — high risk
        if (observation.hasPaymentKeyword && observation.hasOtpField) {
            triggerAlert(
                event = EdgeEvent(
                    eventType = "payment_otp_detected",
                    source = "sentinel_passive_scan",
                    triggerKeywords = listOf("payment", "otp"),
                    hasSensitiveData = true,
                    confidence = 0.85f,
                ),
                observation = observation,
                guardDecision = GuardDecision(
                    decision = GuardResult.DENY,
                    requireHumanCheck = true,
                    reason = "payment_with_otp_auto_detected",
                    securityPolicy = "sentinel_auto_block",
                    autoProtect = true,
                    safeAlternative = "检测到付款+验证码页面，老白建议您先不要操作，联系家人确认。",
                ),
            )
            return
        }

        // Check for form pages — passive form detection (Case 1)
        if (observation.pageType == "form") {
            val match = FormTemplateMatcher.match(observation)
            if (match.confidence >= 0.5f) {
                Log.d(TAG, "Form template matched: ${match.template?.displayName} (confidence=${match.confidence})")
                val templateName = match.template?.displayName ?: "表单"
                val prompt = "老白发现您正在填写${templateName}，需要我帮您填写吗？"
                com.elva.laobai.ElvaTtsManager.speak(prompt)
                // Update state to indicate form was detected
                _state.value = _state.value.copy(
                    lastObservation = observation,
                )
                return
            }
        }

        // Update state with current observation (no alert)
        _state.value = _state.value.copy(
            lastObservation = observation,
        )
    }

    /**
     * User-triggered full pipeline execution.
     * Called when user presses the voice button or uses "老白" wake word.
     *
     * @param userText The user's voice input.
     * @return The complete pipeline result for the UI to display.
     */
    fun triggerFullPipeline(userText: String): PipelineResult {
        Log.d(TAG, "Full pipeline triggered with: $userText")

        // Step 1: Observe screen
        val observation = ScreenObserver.observe()

        // Step 2: Check for scams first
        val allText = (observation?.uiElements?.joinToString(" ") { it.text } ?: "") + " " + userText
        val scamResult = ScamGuard.analyze(allText)
        if (scamResult != null) {
            val scamAction = ScamGuard.generateScamAlert(allText)
            return PipelineResult(
                event = EdgeEvent(
                    eventType = "scam_detected",
                    source = "trigger_pipeline",
                    triggerKeywords = ScamGuard.detectScamPatterns(allText).map { it.name },
                    hasSensitiveData = true,
                ),
                observation = observation,
                routingDecision = null,
                nextAction = scamAction,
                guardDecision = scamResult,
            )
        }

        // Step 3: Route
        val routing = LocalRouter.route(
            observation ?: ScreenObservation(pageType = "unknown", uiElements = emptyList(), sensitiveFieldCategories = emptyList()),
            userText,
        )

        // Step 3.5: Health consultation handling (Case 2)
        // All triage is local — HealthTriageEngine + on-device Gemma 4.
        if (routing.reason.startsWith("health_query")) {
            val healthFirstAction = com.elva.laobai.health.HealthTriageEngine.startConsultation(userText)
            val healthGuardDecision = SafetyGuard.evaluate(healthFirstAction, observation)
            val finalAction = when (healthGuardDecision.decision) {
                GuardResult.ALLOW -> healthFirstAction
                GuardResult.REQUIRE_CONFIRMATION -> healthFirstAction.copy(
                    action = ActionType.ASK_CONFIRMATION,
                    voicePrompt = "大爷，${healthFirstAction.voicePrompt}\n\n您确认要咨询看病问题吗？",
                )
                GuardResult.DENY -> NextAction(
                    action = ActionType.EMERGENCY_STOP,
                    targetDescription = "health_deny",
                    voicePrompt = healthGuardDecision.safeAlternative ?: "老白建议您直接去医院就诊，不要耽误。",
                    explanation = healthGuardDecision.reason,
                    riskLevel = RiskLevel.HIGH,
                )
            }

            val event = EdgeEvent(
                eventType = "health_consultation",
                source = "voice_input",
                triggerKeywords = extractKeywords(userText),
            )

            return PipelineResult(
                event = event,
                observation = observation,
                routingDecision = routing,
                nextAction = finalAction,
                guardDecision = healthGuardDecision,
            )
        }

        // Step 4: Generate action based on route
        val action = when (routing.route) {
            com.elva.laobai.models.RoutingDecision.Route.LOCAL_ONLY ->
                generateLocalAction(userText, observation)
            com.elva.laobai.models.RoutingDecision.Route.CLOUD_PLANNER ->
                generateCloudAction(userText, observation)
            com.elva.laobai.models.RoutingDecision.Route.ASK_USER ->
                NextAction(
                    action = ActionType.SPEAK_ONLY,
                    targetDescription = "ask_user",
                    voicePrompt = "我不太明白您的意思，能再说一次吗？",
                    explanation = "Need user clarification",
                    riskLevel = NextAction.RiskLevel.ZERO,
                )
            com.elva.laobai.models.RoutingDecision.Route.STOP ->
                NextAction(
                    action = ActionType.EMERGENCY_STOP,
                    targetDescription = "risk_stop",
                    voicePrompt = "大爷别点！老白发现这里有风险，帮您拦住了。如果有疑问，请联系家人。",
                    explanation = routing.reason,
                    riskLevel = NextAction.RiskLevel.HIGH,
                )
        }

        // Step 5: Safety Guard evaluation
        val guardDecision = SafetyGuard.evaluate(action, observation)

        // Step 6: Adjust action based on guard decision
        val finalAction = when (guardDecision.decision) {
            GuardResult.ALLOW -> action
            GuardResult.REQUIRE_CONFIRMATION -> action.copy(
                action = ActionType.ASK_CONFIRMATION,
                voicePrompt = "大爷，${action.voicePrompt}\n\n您确认要这样做吗？",
            )
            GuardResult.DENY -> NextAction(
                action = ActionType.EMERGENCY_STOP,
                targetDescription = "guard_deny",
                voicePrompt = guardDecision.safeAlternative ?: "老白建议您不要继续这个操作。",
                explanation = guardDecision.reason,
                riskLevel = NextAction.RiskLevel.HIGH,
            )
        }

        val event = EdgeEvent(
            eventType = "user_trigger",
            source = "voice_input",
            triggerKeywords = extractKeywords(userText),
        )

        // Update state
        _state.value = _state.value.copy(
            mode = SentinelMode.TRIGGER,
            lastEvent = event,
            lastObservation = observation,
            lastGuardDecision = guardDecision,
        )

        return PipelineResult(
            event = event,
            observation = observation,
            routingDecision = routing,
            nextAction = finalAction,
            guardDecision = guardDecision,
        )
    }

    /**
     * Trigger an alert from passive monitoring.
     */
    private fun triggerAlert(
        event: EdgeEvent,
        observation: ScreenObservation,
        guardDecision: GuardDecision,
    ) {
        val now = System.currentTimeMillis()
        // Prevent alert spam
        if (now - _state.value.lastAlertTime < SCAN_COOLDOWN_MS * 2) return

        _state.value = _state.value.copy(
            lastAlertTime = now,
            lastEvent = event,
            lastObservation = observation,
            lastGuardDecision = guardDecision,
        )

        Log.w(TAG, "ALERT: ${event.eventType}, decision=${guardDecision.decision}")

        // Speak the alert via TTS
        val voiceMessage = when (guardDecision.decision) {
            GuardResult.DENY -> guardDecision.safeAlternative ?: "检测到风险，已为您拦截。"
            GuardResult.REQUIRE_CONFIRMATION -> "大爷注意，老白发现当前操作可能存在风险，请确认后再继续。"
            GuardResult.ALLOW -> ""
        }

        if (voiceMessage.isNotBlank()) {
            com.elva.laobai.ElvaTtsManager.speak(voiceMessage)
        }
    }

    /**
     * Generate a local-only action (no cloud needed).
     */
    private fun generateLocalAction(userText: String, observation: ScreenObservation?): NextAction {
        val response = when {
            userText.contains("几点") || userText.contains("时间") -> {
                val sdf = java.text.SimpleDateFormat("HH:mm, EEEE", java.util.Locale.CHINESE)
                "现在是${sdf.format(java.util.Date())}。"
            }
            userText.contains("照片") || userText.contains("相册") ->
                "好的，帮您打开相册啦~"
            userText.contains("拍照") || userText.contains("照相机") ->
                "好的，帮您打开相机~"
            userText.contains("你好") || userText.contains("hello", ignoreCase = true) ->
                "您好呀！我是老白，有什么能帮您的吗？"
            else ->
                "让我想想怎么帮您~"
        }

        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "local_response",
            voicePrompt = response,
            explanation = "Local pattern match",
            riskLevel = NextAction.RiskLevel.ZERO,
            source = "local",
        )
    }

    /**
     * Generate a cloud-planned action.
     * Returns a placeholder — actual cloud planning happens in ElvaInferenceBridge.
     */
    private fun generateCloudAction(userText: String, observation: ScreenObservation?): NextAction {
        val bridge = com.elva.laobai.inference.ElvaInferenceBridge
        if (bridge.state.value.isModelReady) {
            return NextAction(
                action = ActionType.SPEAK_ONLY,
                targetDescription = "cloud_planning",
                voicePrompt = "让我帮您想想怎么操作...",
                explanation = "Routing to cloud planner (model ready)",
                riskLevel = NextAction.RiskLevel.LOW,
                source = "cloud_router",
            )
        } else {
            return CloudPlanner.generateFallbackAction(userText)
        }
    }

    /**
     * Start the form filling assistant (Case 1).
     * Called when user triggers form assistance on a matched form page.
     *
     * @return The FillState after beginning fill, or null if no template matched.
     */
    fun startFormFilling(): FormFillEngine.FillState? {
        val observation = ScreenObserver.observe() ?: return null
        val match = FormTemplateMatcher.match(observation)
        if (match.confidence < 0.5f || match.template == null) return null

        // Begin filling and return the fill state
        FormFillEngine.beginFill(match.template, observation)
        return FormFillEngine.getFillState()
    }

    /**
     * Start the health consultation assistant (Case 2).
     * Returns the first consultation question.
     */
    fun startHealthConsultation(userText: String): NextAction {
        return com.elva.laobai.health.HealthTriageEngine.startConsultation(userText)
    }

    private fun extractKeywords(text: String): List<String> {
        val keywords = mutableListOf<String>()
        if (text.contains("打电话")) keywords.add("phone_call")
        if (text.contains("照片")) keywords.add("photos")
        if (text.contains("交电费")) keywords.add("electric_bill")
        if (text.contains("交水费")) keywords.add("water_bill")
        if (text.contains("挂号")) keywords.add("hospital")
        if (text.contains("转账")) keywords.add("transfer")
        if (text.contains("几点")) keywords.add("time")
        return keywords
    }

    /**
     * Result of the full pipeline execution.
     * Contains all 5 layers of data for transparency.
     */
    data class PipelineResult(
        val event: EdgeEvent,
        val observation: ScreenObservation?,
        val routingDecision: com.elva.laobai.models.RoutingDecision?,
        val nextAction: NextAction,
        val guardDecision: GuardDecision,
        /** V5: Whether the action was auto-executed. */
        val wasExecuted: Boolean = false,
        /** V5: Execution result if auto-executed. */
        val executionResult: com.elva.laobai.executor.ActionExecutor.ExecutionResult? = null,
    )
}
