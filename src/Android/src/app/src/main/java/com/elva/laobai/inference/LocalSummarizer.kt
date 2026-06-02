/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.inference

import android.util.Log
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.privacy.PrivacyFirewall

/**
 * Local Summarizer — generates privacy-safe screen summaries
 * using the on-device Gemma model.
 *
 * Used for:
 * 1. Creating condensed observations for cloud planner input
 * 2. Generating family assist summary cards
 * 3. Providing voice narration of current screen state
 *
 * All summaries are automatically redacted to ensure cloudSafe=true.
 */
object LocalSummarizer {
    private const val TAG = "LocalSummarizer"

    /**
     * Generate a text summary of the current screen observation.
     * The summary is automatically redacted of all PII.
     *
     * @param observation The screen observation (already redacted).
     * @return A human-readable summary safe for cloud upload.
     */
    fun summarizeScreen(observation: ScreenObservation): String {
        val sb = StringBuilder()

        // Page type description
        sb.append("页面类型: ${describePageType(observation.pageType)}。")

        // Element summary
        val buttons = observation.uiElements.filter { it.isClickable }
        val inputs = observation.uiElements.filter { it.isEditable }
        val texts = observation.uiElements.filter { !it.isClickable && !it.isEditable }

        if (buttons.isNotEmpty()) {
            sb.append("可点击按钮: ${buttons.take(5).joinToString("、") { it.text }}。")
        }
        if (inputs.isNotEmpty()) {
            sb.append("输入框${inputs.size}个。")
        }
        if (texts.isNotEmpty()) {
            val textSummary = texts.take(3).joinToString("、") { it.text }
            sb.append("页面文字: $textSummary。")
        }

        // Risk indicators
        if (observation.hasPaymentKeyword) sb.append("【涉及付款】")
        if (observation.hasOtpField) sb.append("【有验证码】")
        if (observation.hasAuthorizationRequest) sb.append("【有授权请求】")
        if (observation.fraudIndicators.isNotEmpty()) {
            sb.append("【诈骗指标: ${observation.fraudIndicators.joinToString("、")}】")
        }

        val summary = sb.toString()

        // Double-check: redact any remaining PII
        val redacted = PrivacyFirewall.redactText(summary).redactedText

        Log.d(TAG, "Screen summary: ${redacted.take(100)}...")
        return redacted
    }

    /**
     * Generate a brief voice-friendly summary for TTS.
     */
    fun summarizeForVoice(observation: ScreenObservation): String {
        val pageDesc = describePageType(observation.pageType)

        val sb = StringBuilder()
        sb.append("当前在${pageDesc}。")

        val buttons = observation.uiElements.filter { it.isClickable }
        if (buttons.isNotEmpty()) {
            sb.append("页面上有${buttons.size}个按钮。")
        }

        when {
            observation.hasPaymentKeyword -> sb.append("这个页面涉及付款操作，请小心。")
            observation.hasOtpField -> sb.append("页面有验证码输入，不要告诉任何人。")
            observation.fraudIndicators.isNotEmpty() -> sb.append("老白发现页面有可疑内容，建议不要操作。")
            observation.hasAuthorizationRequest -> sb.append("页面有授权请求，建议先跟家人确认。")
            else -> sb.append("页面看起来正常。")
        }

        return sb.toString()
    }

    /**
     * Generate a condensed summary for cloud planner input.
     * Much shorter than full summary — optimized for token efficiency.
     */
    fun summarizeForCloud(observation: ScreenObservation): String {
        val sb = StringBuilder()
        sb.append("page:${observation.pageType}")
        sb.append("|btn:${observation.uiElements.count { it.isClickable }}")
        sb.append("|inp:${observation.uiElements.count { it.isEditable }}")

        if (observation.hasPaymentKeyword) sb.append("|pay:1")
        if (observation.hasOtpField) sb.append("|otp:1")
        if (observation.hasAuthorizationRequest) sb.append("|auth:1")
        if (observation.fraudIndicators.isNotEmpty()) sb.append("|fraud:${observation.fraudIndicators.size}")

        // Key element texts (top 10, redacted)
        val keyTexts = observation.uiElements
            .filter { it.text.isNotBlank() }
            .take(10)
            .joinToString(",") { it.text.take(20) }
        sb.append("|elems:$keyTexts")

        return PrivacyFirewall.redactText(sb.toString()).redactedText
    }

    private fun describePageType(pageType: String): String {
        return when (pageType) {
            "payment" -> "付款页面"
            "form" -> "表单页面"
            "settings" -> "设置页面"
            "login" -> "登录页面"
            "chat" -> "聊天页面"
            "shopping" -> "购物页面"
            "alipay" -> "支付宝"
            "wechat" -> "微信"
            "qq" -> "QQ"
            "navigation" -> "导航页面"
            "phone_dialer" -> "拨号页面"
            "contacts" -> "通讯录"
            "messaging" -> "短信页面"
            "camera" -> "相机"
            "gallery" -> "相册"
            "search" -> "搜索页面"
            "local_services" -> "生活服务"
            else -> "操作页面"
        }
    }
}
