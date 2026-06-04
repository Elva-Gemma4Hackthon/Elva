/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.guard

import android.util.Log
import com.elva.laobai.models.GuardDecision
import com.elva.laobai.models.GuardDecision.GuardResult
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel
import com.elva.laobai.models.ScreenObservation

/**
 * Safety Guard — the final safety checkpoint before any action is executed.
 *
 * From PPT Slide 7: "Safety Guard 让 Agent 在关键动作前停下来"
 *
 * Three-level decision:
 * - ALLOW: Low risk, reversible actions (e.g., open settings page)
 * - REQUIRE_CONFIRMATION: Submit, send, authorize — must let user confirm
 * - DENY: Verification code + payment risk — directly refuse
 *
 * Security output must be split into three layers (PPT):
 * 1. 事实: What's actually on screen
 * 2. 推断: What this probably means
 * 3. 建议: What the user should do
 */
object SafetyGuard {
    private const val TAG = "SafetyGuard"

    /**
     * Actions that are always safe — can be allowed without confirmation.
     * "低风险、可逆动作，例如打开设置页"
     */
    private val SAFE_ACTIONS = setOf(
        ActionType.SPEAK_ONLY,
        ActionType.HIGHLIGHT_ELEMENT,
        ActionType.OPEN_APP,
        ActionType.NAVIGATE_BACK,
        ActionType.NAVIGATE_HOME,
        ActionType.SCROLL,
    )

    /**
     * Actions that always require user confirmation.
     * "提交、发送、授权前，必须让用户确认"
     */
    private val CONFIRM_REQUIRED_ACTIONS = setOf(
        ActionType.CLICK_ELEMENT,
        ActionType.TYPE_TEXT,
        ActionType.GENERATE_SUMMARY,
        ActionType.ASK_CONFIRMATION,
    )

    /**
     * Actions that are always denied.
     * Emergency stop is handled differently (it IS the deny).
     */
    private val DENIED_ACTIONS = setOf<ActionType>()

    /**
     * Context-sensitive keywords that escalate risk.
     */
    private val HIGH_RISK_CONTEXTS = mapOf(
        "payment" to RiskLevel.HIGH,
        "otp" to RiskLevel.HIGH,
        "authorization" to RiskLevel.HIGH,
        "fraud_risk" to RiskLevel.HIGH,
    )

    /**
     * Blocked targets in form pages — these must never be auto-clicked.
     * From Case 1: Always-on form filling assistant.
     */
    private val FORM_BLOCKED_TARGETS = setOf(
        "提交", "确认提交", "确认报名", "立即报名",
        "支付", "付款", "确认支付", "立即支付",
        "授权", "解除绑定", "获取验证码", "发送验证码",
        "删除", "删除患者信息", "注销", "取消预约",
    )

    /**
     * Evaluate a NextAction against the current screen context.
     *
     * @param action The proposed action from the planner.
     * @param observation The current screen observation (for context).
     * @return GuardDecision with allow/require_confirmation/deny.
     */
    fun evaluate(
        action: NextAction,
        observation: ScreenObservation?,
    ): GuardDecision {
        Log.d(TAG, "Evaluating action: ${action.action}, risk: ${action.riskLevel}")

        // Rule 1: Emergency stop is always allowed (it IS the protection)
        if (action.action == ActionType.EMERGENCY_STOP) {
            return GuardDecision(
                decision = GuardResult.ALLOW,
                requireHumanCheck = true,
                riskLevel = RiskLevel.HIGH,
                reason = "emergency_stop_is_protection",
                securityPolicy = "emergency_protocols",
                autoProtect = true,
                safeAlternative = "已为您拦截当前操作，请检查是否遇到诈骗。",
            )
        }

        // Rule 2: Deny if trying to interact with OTP fields
        if (observation?.hasOtpField == true &&
            action.action in listOf(ActionType.TYPE_TEXT, ActionType.CLICK_ELEMENT)) {
            Log.w(TAG, "DENY: Attempting to interact with OTP field")
            return GuardDecision(
                decision = GuardResult.DENY,
                requireHumanCheck = true,
                riskLevel = RiskLevel.HIGH,
                reason = "blocked_otp_field_interaction",
                securityPolicy = "otp_protection_policy",
                autoProtect = true,
                safeAlternative = buildThreeLayerOutput(
                    fact = "页面出现了验证码输入框。",
                    inference = "这可能是付款授权流程，输入验证码可能导致资金损失。",
                    suggestion = "不要输入验证码，先联系家人或拨打官方客服确认。"
                ),
            )
        }

        // Rule 3: Deny if fraud indicators present
        if (observation?.fraudIndicators?.isNotEmpty() == true) {
            Log.w(TAG, "DENY: Fraud indicators detected: ${observation.fraudIndicators}")
            return GuardDecision(
                decision = GuardResult.DENY,
                requireHumanCheck = true,
                riskLevel = RiskLevel.HIGH,
                reason = "fraud_indicators_detected_${observation.fraudIndicators.joinToString("_")}",
                securityPolicy = "anti_fraud_policy",
                autoProtect = true,
                safeAlternative = buildThreeLayerOutput(
                    fact = "页面出现了可疑内容：${observation.fraudIndicators.joinToString("、")}。",
                    inference = "这极有可能是诈骗，骗子常用这些话术恐吓老人。",
                    suggestion = "千万不要继续操作！建议拨打96110反诈热线或联系家人。"
                ),
            )
        }

        // Rule 3.5: Form context — block interactions with blocked targets (Case 1)
        if (observation?.pageType == "form") {
            val targetDesc = action.targetDescription
            val isBlockedTarget = FORM_BLOCKED_TARGETS.any { blocked ->
                targetDesc.contains(blocked, ignoreCase = true)
            }

            if (isBlockedTarget && action.action in listOf(ActionType.CLICK_ELEMENT)) {
                Log.w(TAG, "REQUIRE_CONFIRMATION: Form blocked target '$targetDesc'")
                return GuardDecision(
                    decision = GuardResult.REQUIRE_CONFIRMATION,
                    requireHumanCheck = true,
                    riskLevel = RiskLevel.HIGH,
                    reason = "form_blocked_target_$targetDesc",
                    securityPolicy = "form_blocked_targets_policy",
                    autoProtect = true,
                    safeAlternative = buildThreeLayerOutput(
                        fact = "当前页面有'$targetDesc'按钮。",
                        inference = "这是表单提交或付款操作，需要您自己确认。",
                        suggestion = "请仔细检查填写的信息后，自己点击'$targetDesc'按钮。"
                    ),
                )
            }

            // Also check if typing into sensitive fields
            if (action.action == ActionType.TYPE_TEXT && action.value != null) {
                val lowerValue = action.value.lowercase()
                val sensitiveValuePatterns = listOf("验证码", "密码", "身份证")
                val isSensitiveValue = sensitiveValuePatterns.any {
                    lowerValue.contains(it) || targetDesc.contains(it)
                }
                if (isSensitiveValue) {
                    Log.w(TAG, "DENY: Typing sensitive value in form context")
                    return GuardDecision(
                        decision = GuardResult.DENY,
                        requireHumanCheck = true,
                        riskLevel = RiskLevel.HIGH,
                        reason = "form_sensitive_value_${targetDesc}",
                        securityPolicy = "form_sensitive_value_policy",
                        autoProtect = true,
                        safeAlternative = buildThreeLayerOutput(
                            fact = "表单中有'$targetDesc'字段。",
                            inference = "这是敏感信息字段，老白不会帮您自动填写。",
                            suggestion = "请自己手动输入'$targetDesc'，千万不要告诉任何人。"
                        ),
                    )
                }
            }
        }

        // Rule 4: Check action-specific risk
        val contextRisk = assessContextRisk(observation)
        val combinedRisk = maxOf(action.riskLevel, contextRisk)

        // Rule 5: High-risk actions with payment context → deny
        if (combinedRisk == RiskLevel.HIGH &&
            observation?.hasPaymentKeyword == true &&
            action.action in CONFIRM_REQUIRED_ACTIONS) {
            Log.w(TAG, "DENY: High risk + payment context + sensitive action")
            return GuardDecision(
                decision = GuardResult.DENY,
                requireHumanCheck = true,
                riskLevel = RiskLevel.HIGH,
                reason = "high_risk_payment_action_blocked",
                securityPolicy = "payment_protection_policy",
                autoProtect = true,
                safeAlternative = buildThreeLayerOutput(
                    fact = "页面涉及付款操作。",
                    inference = "当前操作可能产生真实资金变动。",
                    suggestion = "建议先和家人确认后再操作。如不确定，请勿继续。"
                ),
            )
        }

        // Rule 6: Safe actions → allow
        if (action.action in SAFE_ACTIONS && combinedRisk <= RiskLevel.LOW) {
            return GuardDecision(
                decision = GuardResult.ALLOW,
                riskLevel = combinedRisk,
                reason = "safe_action_low_risk",
                securityPolicy = "allowlist_only_click_and_type",
            )
        }

        // Rule 7: Medium risk or confirmation-required actions → require confirmation
        if (action.action in CONFIRM_REQUIRED_ACTIONS || combinedRisk >= RiskLevel.MEDIUM) {
            return GuardDecision(
                decision = GuardResult.REQUIRE_CONFIRMATION,
                requireHumanCheck = true,
                riskLevel = combinedRisk,
                reason = "confirmation_required_${action.action}",
                securityPolicy = "confirmation_policy",
                safeAlternative = action.voicePrompt,
            )
        }

        // Rule 8: Default allow for low-risk actions
        return GuardDecision(
            decision = GuardResult.ALLOW,
            riskLevel = combinedRisk,
            reason = "default_allow_low_risk",
        )
    }

    /**
     * Assess risk level from the screen context.
     */
    private fun assessContextRisk(observation: ScreenObservation?): RiskLevel {
        if (observation == null) return RiskLevel.ZERO

        var maxRisk = RiskLevel.ZERO
        for (category in observation.sensitiveFieldCategories) {
            HIGH_RISK_CONTEXTS[category]?.let { risk ->
                if (risk.ordinal > maxRisk.ordinal) {
                    maxRisk = risk
                }
            }
        }
        return maxRisk
    }

    /**
     * Build the three-layer security output required by the PPT:
     * 事实 (fact) + 推断 (inference) + 建议 (suggestion)
     */
    private fun buildThreeLayerOutput(
        fact: String,
        inference: String,
        suggestion: String,
    ): String {
        return "【事实】$fact\n【推断】$inference\n【建议】$suggestion"
    }
}
