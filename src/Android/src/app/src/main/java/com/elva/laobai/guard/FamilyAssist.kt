/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.guard

import android.util.Log
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.privacy.PrivacyFirewall

/**
 * Family Assist — generates privacy-safe summaries for family members.
 *
 * From PPT Demo Loop 04: "生成脱敏摘要，而不是转发完整敏感截图"
 * From PPT Slide 5: "家人协助模式：redacted summary / family assist"
 *
 * Key principle: Never send original screenshots or PII to family.
 * Instead, generate a structured, redacted summary card.
 */
object FamilyAssist {
    private const val TAG = "FamilyAssist"

    /**
     * Family assist summary card — safe to share externally.
     */
    data class FamilySummaryCard(
        /** Who needs help. */
        val assistTarget: String = "爸爸/妈妈",

        /** Current situation description (redacted). */
        val situation: String,

        /** Problem description. */
        val problem: String,

        /** What the user is trying to do. */
        val userGoal: String? = null,

        /** Any error messages (redacted). */
        val errorMessage: String? = null,

        /** Elva's diagnosis (safe to share). */
        val elvaDiagnosis: String,

        /** Suggested action for family member. */
        val suggestedAction: String,

        /** Timestamp. */
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        /**
         * Convert to a human-readable text message (for sharing).
         */
        fun toShareText(): String {
            val sb = StringBuilder()
            sb.appendLine("【老白守护求助简报】")
            sb.appendLine("协助对象：$assistTarget")
            sb.appendLine("当前情况：$situation")
            if (userGoal != null) sb.appendLine("想做的事情：$userGoal")
            if (errorMessage != null) sb.appendLine("遇到问题：$errorMessage")
            sb.appendLine()
            sb.appendLine("老白诊断：$elvaDiagnosis")
            sb.appendLine("建议：$suggestedAction")
            sb.appendLine()
            sb.appendLine("—— 由 Elva 老白 安全生成，敏感信息已脱敏 ——")
            return sb.toString()
        }
    }

    /**
     * Generate a family assist summary from the current screen observation.
     *
     * @param observation The redacted screen observation.
     * @param userMessage Optional voice message from the user.
     * @param contactName The family member's relationship/name.
     * @return FamilySummaryCard safe for sharing.
     */
    fun generateSummary(
        observation: ScreenObservation?,
        userMessage: String? = null,
        contactName: String = "家人",
    ): FamilySummaryCard {
        val situation = if (observation != null) {
            describeSituation(observation)
        } else if (userMessage != null) {
            PrivacyFirewall.redactText(userMessage).redactedText
        } else {
            "老人遇到了操作困难，需要帮助。"
        }

        val goal = observation?.userGoal ?: extractGoalFromMessage(userMessage)
        val error = extractErrorMessage(observation)
        val diagnosis = generateDiagnosis(observation, userMessage)
        val action = suggestAction(observation, contactName)

        Log.d(TAG, "Family summary generated: situation=${situation.take(30)}...")

        return FamilySummaryCard(
            assistTarget = "爸爸/妈妈",
            situation = situation,
            problem = error ?: "老人在操作手机时遇到困难",
            userGoal = goal,
            errorMessage = error,
            elvaDiagnosis = diagnosis,
            suggestedAction = action,
        )
    }

    /**
     * Describe the current situation from the screen observation.
     * All PII should already be redacted by PrivacyFirewall.
     */
    private fun describeSituation(observation: ScreenObservation): String {
        val pageType = when (observation.pageType) {
            "payment" -> "支付/付款页面"
            "form" -> "表单填写页面"
            "settings" -> "手机设置页面"
            "login" -> "登录页面"
            "shopping" -> "购物页面"
            "alipay" -> "支付宝"
            "wechat" -> "微信"
            else -> "手机操作页面"
        }
        val elementCount = observation.uiElements.size
        val inputCount = observation.uiElements.count { it.isEditable }

        val sb = StringBuilder()
        sb.append("老人正在使用$pageType")
        if (inputCount > 0) {
            sb.append("，页面有$inputCount 个需要填写的字段")
        }
        if (observation.hasPaymentKeyword) {
            sb.append("（涉及付款操作）")
        }
        return sb.toString()
    }

    /**
     * Extract user's goal from voice message.
     */
    private fun extractGoalFromMessage(message: String?): String? {
        if (message == null) return null
        // Simple extraction — look for action verbs
        val goals = mutableListOf<String>()
        if (message.contains("交电费")) goals.add("交电费")
        if (message.contains("交水费")) goals.add("交水费")
        if (message.contains("挂号")) goals.add("医院挂号")
        if (message.contains("报销")) goals.add("医疗报销")
        if (message.contains("买")) goals.add("购物")
        if (message.contains("设置")) goals.add("调整手机设置")
        return if (goals.isNotEmpty()) goals.joinToString("、") else null
    }

    /**
     * Extract error message from the screen observation.
     */
    private fun extractErrorMessage(observation: ScreenObservation?): String? {
        if (observation == null) return null
        // Look for error-related UI elements
        val errorElements = observation.uiElements.filter { element ->
            element.text.contains("错误", ignoreCase = true) ||
                element.text.contains("失败", ignoreCase = true) ||
                element.text.contains("error", ignoreCase = true) ||
                element.text.contains("不匹配", ignoreCase = true) ||
                element.text.contains("无法", ignoreCase = true)
        }
        if (errorElements.isEmpty()) return null
        // Redact any remaining PII from error messages
        return errorElements.joinToString("；") {
            PrivacyFirewall.redactText(it.text).redactedText
        }
    }

    /**
     * Generate Elva's diagnosis based on screen and message.
     */
    private fun generateDiagnosis(observation: ScreenObservation?, message: String?): String {
        if (observation == null && message == null) {
            return "老人需要操作帮助。"
        }

        val parts = mutableListOf<String>()

        if (observation?.hasPaymentKeyword == true) {
            parts.add("当前操作涉及付款")
        }
        if (observation?.hasOtpField == true) {
            parts.add("页面有验证码输入")
        }
        if (observation?.fraudIndicators?.isNotEmpty() == true) {
            parts.add("检测到可疑内容，已进行安全防护")
        }
        if (observation?.hasAuthorizationRequest == true) {
            parts.add("页面有授权请求")
        }

        val errorCount = observation?.uiElements?.count {
            it.text.contains("错误") || it.text.contains("失败")
        } ?: 0
        if (errorCount > 0) {
            parts.add("遇到$errorCount 个错误提示")
        }

        return if (parts.isNotEmpty()) {
            parts.joinToString("，") + "。敏感隐私已在端侧完成安全脱敏。"
        } else {
            "老人在操作手机时遇到困难，需要远程协助。敏感隐私已在端侧完成安全脱敏。"
        }
    }

    /**
     * Suggest an action for the family member.
     */
    private fun suggestAction(observation: ScreenObservation?, contactName: String): String {
        return when {
            observation?.fraudIndicators?.isNotEmpty() == true ->
                "请尽快联系老人确认是否安全，建议拨打96110反诈热线。"
            observation?.hasPaymentKeyword == true ->
                "请确认老人是否确实需要进行付款操作，谨防诈骗。"
            observation?.hasOtpField == true ->
                "老人页面出现验证码，请确认操作是否安全。"
            else ->
                "请帮助老人完成当前手机操作，可通过远程协助或电话指导。"
        }
    }

    /**
     * Generate a NextAction for the family assist flow.
     */
    fun generateFamilyAssistAction(
        observation: ScreenObservation?,
        userMessage: String?,
        contactName: String,
    ): NextAction {
        val card = generateSummary(observation, userMessage, contactName)
        return NextAction(
            action = ActionType.GENERATE_SUMMARY,
            targetDescription = "family_assist_card",
            voicePrompt = "大爷别急，老白已经帮您做成了一张安全的求助卡片，" +
                "里面的私人信息都替您藏好了，可以放心发给$contactName。",
            explanation = "生成脱敏求助卡片",
            riskLevel = NextAction.RiskLevel.LOW,
            source = "family_assist",
        )
    }
}
