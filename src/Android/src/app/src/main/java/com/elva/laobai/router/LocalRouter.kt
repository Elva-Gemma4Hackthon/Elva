/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.router

import android.util.Log
import com.elva.laobai.models.RoutingDecision
import com.elva.laobai.models.RoutingDecision.Complexity
import com.elva.laobai.models.RoutingDecision.Route
import com.elva.laobai.models.ScreenObservation

/**
 * Local Router — decides whether a task should be handled locally,
 * sent to cloud planner, ask user for input, or stopped immediately.
 *
 * Routing rules (from PPT Slide 5):
 * - LOCAL_ONLY: Simple system tasks (e.g., adjust font, open settings)
 * - CLOUD_PLANNER: Complex multi-step tasks with redacted data
 * - ASK_USER: Need clarification or missing information
 * - STOP: High risk detected, must block immediately
 *
 * Key constraint: If cloudSafe=false or unredacted PII detected, cloud is BLOCKED.
 */
object LocalRouter {
    private const val TAG = "LocalRouter"

    /**
     * Simple local tasks that can be handled entirely on-device.
     * These match the PPT's "低风险本地建议" category.
     */
    private val LOCAL_TASKS = setOf(
        "adjust_font", "adjust_brightness", "open_settings",
        "open_camera", "open_photos", "check_time",
        "open_dialer", "show_contacts",
    )

    /**
     * High-risk keywords that trigger immediate STOP.
     * From PPT: "验证码 + 付款等风险直接拒绝继续"
     */
    private val STOP_KEYWORDS = listOf(
        "转账", "汇款", "保证金", "安全账户",
        "涉嫌违规", "账户冻结", "紧急处理",
    )

    /**
     * Health-related keywords for Case 2 health consultation routing.
     */
    private val HEALTH_KEYWORDS = listOf(
        "不舒服", "疼", "痛", "难受", "头晕", "恶心",
        "发烧", "咳嗽", "胸闷", "胃", "肚子", "腰",
        "腿", "头", "嗓子", "看病", "医院", "挂号",
        "症状", "过敏", "痒", "出血", "肿", "晕",
    )

    /**
     * Make a routing decision based on the screen observation.
     *
     * @param observation The redacted screen observation.
     * @param userIntent The user's inferred intent from voice input.
     * @return RoutingDecision with the appropriate route.
     */
    fun route(
        observation: ScreenObservation,
        userIntent: String? = null,
    ): RoutingDecision {
        // Step 1: Check for urgent danger — fraud indicators with OTP
        if (observation.fraudIndicators.isNotEmpty() && observation.hasOtpField) {
            Log.w(TAG, "URGENT_DANGER: Fraud indicators + OTP field detected")
            return RoutingDecision(
                route = Route.STOP,
                complexity = Complexity.URGENT_DANGER,
                reason = "fraud_indicators_with_otp",
                containsUnredactedPii = false,
                cloudSafe = false,
                localFallback = true,
            )
        }

        // Step 2: Check for high-risk payment + OTP combination
        if (observation.hasPaymentKeyword && observation.hasOtpField) {
            Log.w(TAG, "HIGH risk: Payment + OTP detected")
            return RoutingDecision(
                route = Route.STOP,
                complexity = Complexity.URGENT_DANGER,
                reason = "payment_with_otp_high_risk",
                containsUnredactedPii = false,
                cloudSafe = false,
                localFallback = true,
            )
        }

        // Step 3: Check for stop keywords in user intent
        if (userIntent != null) {
            val hasStopKeyword = STOP_KEYWORDS.any { userIntent.contains(it) }
            if (hasStopKeyword) {
                Log.w(TAG, "STOP: User intent contains high-risk keyword")
                return RoutingDecision(
                    route = Route.STOP,
                    complexity = Complexity.HIGH,
                    reason = "user_intent_contains_stop_keyword",
                    containsUnredactedPii = false,
                    cloudSafe = false,
                    localFallback = true,
                )
            }
        }

        // Step 4: Check if PII is still present (blocks cloud)
        if (!observation.cloudSafe) {
            Log.d(TAG, "LOCAL_ONLY: cloudSafe=false, PII not fully redacted")
            return RoutingDecision(
                route = Route.LOCAL_ONLY,
                complexity = Complexity.MEDIUM,
                reason = "cloud_safe_false_pii_detected",
                containsUnredactedPii = true,
                cloudSafe = false,
                localFallback = true,
            )
        }

        // Step 5: Check for simple local tasks
        val isLocalTask = observation.pageType in listOf("settings", "camera", "gallery") ||
            (userIntent != null && LOCAL_TASKS.any { userIntent.contains(it.replace("_", "")) })

        if (isLocalTask && observation.sensitiveFieldCategories.isEmpty()) {
            Log.d(TAG, "LOCAL_ONLY: Simple local task")
            return RoutingDecision(
                route = Route.LOCAL_ONLY,
                complexity = Complexity.LOW,
                reason = "simple_local_task_no_sensitive_data",
                containsUnredactedPii = false,
                cloudSafe = true,
                localFallback = true,
            )
        }

        // Step 6: Health consultation routing (Case 2)
        // All health triage is handled locally via HealthTriageEngine + on-device Gemma 4.
        // The reason starts with "health_query" so ElvaVoiceViewModel can route to the state machine.
        if (userIntent != null && isHealthRelated(userIntent)) {
            Log.d(TAG, "LOCAL_ONLY: Health query — handled by on-device Gemma 4 triage")
            return RoutingDecision(
                route = Route.LOCAL_ONLY,
                complexity = Complexity.HIGH,
                reason = "health_query_local_only",
                containsUnredactedPii = false,
                cloudSafe = true,
                localFallback = true,
            )
        }

        // Step 7: Check if we need to ask user for missing info
        if (userIntent.isNullOrBlank() && observation.uiElements.isEmpty()) {
            Log.d(TAG, "ASK_USER: No intent and no UI elements")
            return RoutingDecision(
                route = Route.ASK_USER,
                complexity = Complexity.LOW,
                reason = "no_user_intent_no_ui_elements",
                containsUnredactedPii = false,
                cloudSafe = true,
                localFallback = true,
            )
        }

        // Step 8: Complex task — route to cloud planner
        // This is the default for multi-step tasks like forms, payments (without OTP), etc.
        val complexity = when {
            observation.hasPaymentKeyword -> Complexity.HIGH
            observation.hasAuthorizationRequest -> Complexity.HIGH
            observation.uiElements.count { it.isEditable } >= 3 -> Complexity.MEDIUM
            observation.fraudIndicators.isNotEmpty() -> Complexity.HIGH
            else -> Complexity.MEDIUM
        }

        Log.d(TAG, "CLOUD_PLANNER: Routing to cloud, complexity=$complexity")
        return RoutingDecision(
            route = Route.CLOUD_PLANNER,
            complexity = complexity,
            reason = "complex_task_requires_cloud_planning",
            containsUnredactedPii = false,
            cloudSafe = observation.cloudSafe,
            localFallback = true,
        )
    }

    /**
     * Check if user intent is health-related for Case 2 routing.
     */
    private fun isHealthRelated(intent: String): Boolean {
        return HEALTH_KEYWORDS.any { intent.contains(it) }
    }
}
