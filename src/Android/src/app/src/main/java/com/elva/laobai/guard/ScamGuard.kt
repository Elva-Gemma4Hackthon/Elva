/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.guard

import android.util.Log
import com.elva.laobai.models.EdgeEvent
import com.elva.laobai.models.GuardDecision
import com.elva.laobai.models.GuardDecision.GuardResult
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.privacy.PrivacyFirewall

/**
 * Scam Guard — dedicated anti-fraud detection and response module.
 *
 * From PPT Demo Loop 03: "遇到验证码、付款、授权时阻断并解释风险"
 * From PPT Slide 7: Safety Guard — risk keyword / deny / safe alternative
 *
 * This module provides:
 * 1. Proactive scam pattern detection
 * 2. Emergency blocking of high-risk operations
 * 3. Elderly-friendly voice warnings
 * 4. Safe alternative suggestions (call family, call 96110 hotline)
 */
object ScamGuard {
    private const val TAG = "ScamGuard"

    /** Anti-fraud hotline. */
    private const val ANTI_FRAUD_HOTLINE = "96110"

    /**
     * Scam pattern categories with severity levels.
     */
    enum class ScamSeverity {
        /** Suspicious but not confirmed. */
        WARNING,
        /** Likely scam — block and warn. */
        DANGEROUS,
        /** Confirmed scam — emergency stop. */
        CRITICAL,
    }

    /**
     * Scam pattern definitions.
     */
    data class ScamPattern(
        val name: String,
        val keywords: List<String>,
        val severity: ScamSeverity,
        val voiceWarning: String,
        val advice: String,
    )

    /** Known scam patterns targeting elderly. */
    private val SCAM_PATTERNS = listOf(
        ScamPattern(
            name = "impersonating_police",
            keywords = listOf("公安局", "涉嫌违规", "冻结账户", "安全账户", "配合调查", "法院传票"),
            severity = ScamSeverity.CRITICAL,
            voiceWarning = "大爷别信！这是冒充公检法的诈骗！公安机关不会通过电话办案！",
            advice = "请立即挂断电话，拨打110或96110核实。",
        ),
        ScamPattern(
            name = "prize_scam",
            keywords = listOf("中奖", "领取奖金", "兑换奖品", "退税", "补贴发放"),
            severity = ScamSeverity.DANGEROUS,
            voiceWarning = "天上不会掉馅饼！这是典型的中奖诈骗，千万别信！",
            advice = "不要点击任何链接，不要填写个人信息。",
        ),
        ScamPattern(
            name = "payment_fraud",
            keywords = listOf("转账", "汇款", "保证金", "手续费", "解冻费"),
            severity = ScamSeverity.CRITICAL,
            voiceWarning = "这是诈骗！老白帮您拦住了！千万不要转账！",
            advice = "不要向陌生人转账，联系家人确认或拨打96110。",
        ),
        ScamPattern(
            name = "phishing",
            keywords = listOf("密码过期", "账户异常", "立即验证", "点击链接", "重新绑定"),
            severity = ScamSeverity.DANGEROUS,
            voiceWarning = "这可能是钓鱼网站，老白建议您不要继续操作。",
            advice = "不要输入密码，退出当前页面，到官方APP检查。",
        ),
        ScamPattern(
            name = "family_emergency",
            keywords = listOf("你儿子出事了", "家人住院", "紧急用钱", "打钱过来"),
            severity = ScamSeverity.CRITICAL,
            voiceWarning = "先别急！这可能是冒充家人的诈骗！请先打电话确认。",
            advice = "请直接拨打家人电话核实，不要盲目汇款。",
        ),
        ScamPattern(
            name = "investment_scam",
            keywords = listOf("高收益", "稳赚不赔", "内部消息", "养老理财", "保健品投资"),
            severity = ScamSeverity.DANGEROUS,
            voiceWarning = "大爷，这种高收益投资都是骗局！老白帮您挡住了。",
            advice = "不要相信任何稳赚不赔的投资，请咨询家人。",
        ),
    )

    /**
     * Detect scam patterns in the given text.
     *
     * @param text The text to analyze (from screen or voice).
     * @return List of detected scam patterns with their details.
     */
    fun detectScamPatterns(text: String): List<ScamPattern> {
        val lowerText = text.lowercase()
        return SCAM_PATTERNS.filter { pattern ->
            pattern.keywords.any { keyword -> lowerText.contains(keyword.lowercase()) }
        }
    }

    /**
     * Check if a text is likely a scam attempt.
     *
     * @param text Text to check.
     * @return true if scam indicators are detected.
     */
    fun isScam(text: String): Boolean {
        return detectScamPatterns(text).isNotEmpty()
    }

    /**
     * Get the highest severity of detected scams.
     */
    fun getMaxSeverity(text: String): ScamSeverity? {
        val patterns = detectScamPatterns(text)
        if (patterns.isEmpty()) return null
        return patterns.maxByOrNull { it.severity.ordinal }?.severity
    }

    /**
     * Generate a scam alert NextAction.
     * Used when a scam is detected — creates an emergency stop action
     * with voice warning and safe alternative.
     *
     * @param screenText The screen text that triggered detection.
     * @return NextAction to speak the warning and block further actions.
     */
    fun generateScamAlert(screenText: String): NextAction {
        val patterns = detectScamPatterns(screenText)
        val severity = patterns.maxByOrNull { it.severity.ordinal }

        val voiceWarning = when (severity?.severity) {
            ScamSeverity.CRITICAL -> "大爷别点！老白帮您死死拦住了！${severity.voiceWarning}"
            ScamSeverity.DANGEROUS -> "大爷注意！${severity.voiceWarning}"
            else -> "大爷，这个页面看起来不太对劲，建议您小心操作。"
        }

        val safeAlternative = severity?.advice ?: "建议联系家人确认或拨打${ANTI_FRAUD_HOTLINE}反诈热线。"

        return NextAction(
            action = ActionType.EMERGENCY_STOP,
            targetDescription = "emergency_scam_block",
            voicePrompt = voiceWarning,
            explanation = "检测到诈骗关键词: ${patterns.map { it.name }}",
            riskLevel = RiskLevel.HIGH,
            source = "scam_guard",
        )
    }

    /**
     * Full scam analysis pipeline.
     * Combines PrivacyFirewall detection with pattern matching.
     *
     * @param screenText Full text from the screen.
     * @return GuardDecision if scam detected, null if safe.
     */
    fun analyze(screenText: String): GuardDecision? {
        val patterns = detectScamPatterns(screenText)
        if (patterns.isEmpty()) return null

        val fraudKeywords = PrivacyFirewall.detectFraudIndicators(screenText)
        val hasOtp = PrivacyFirewall.containsOtpKeywords(screenText)
        val hasPayment = PrivacyFirewall.containsPaymentKeywords(screenText)

        val severity = patterns.maxByOrNull { it.severity.ordinal }!!

        Log.w(TAG, "Scam detected: patterns=${patterns.map { it.name }}, " +
            "severity=${severity.severity}, hasOtp=$hasOtp, hasPayment=$hasPayment")

        return when (severity.severity) {
            ScamSeverity.CRITICAL -> GuardDecision(
                decision = GuardResult.DENY,
                requireHumanCheck = true,
                riskLevel = RiskLevel.HIGH,
                reason = "scam_detected_critical_${patterns.first().name}",
                securityPolicy = "anti_fraud_critical_block",
                autoProtect = true,
                safeAlternative = "${severity.voiceWarning}\n\n${severity.advice}\n\n反诈热线：${ANTI_FRAUD_HOTLINE}",
            )
            ScamSeverity.DANGEROUS -> GuardDecision(
                decision = GuardResult.REQUIRE_CONFIRMATION,
                requireHumanCheck = true,
                riskLevel = RiskLevel.HIGH,
                reason = "scam_suspected_${patterns.first().name}",
                securityPolicy = "anti_fraud_warning",
                autoProtect = false,
                safeAlternative = severity.advice,
            )
            ScamSeverity.WARNING -> GuardDecision(
                decision = GuardResult.REQUIRE_CONFIRMATION,
                requireHumanCheck = true,
                riskLevel = RiskLevel.MEDIUM,
                reason = "suspicious_activity",
                securityPolicy = "caution_policy",
                safeAlternative = "建议仔细确认后再操作。",
            )
        }
    }
}
