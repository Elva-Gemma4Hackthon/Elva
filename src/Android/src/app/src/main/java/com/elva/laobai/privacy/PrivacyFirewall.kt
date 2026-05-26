/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.privacy

import android.util.Log
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.models.UIElement

object PrivacyFirewall {
    private const val TAG = "PrivacyFirewall"

    private val ID_CARD_PATTERN = Regex("\\b\\d{17}[\\dXx]\\b")
    private val PHONE_NUMBER_PATTERN = Regex("\\b1[3-9]\\d{9}\\b")
    private val OTP_PATTERN = Regex("\\b\\d{4,6}\\b")
    private val BANK_CARD_PATTERN = Regex("\\b\\d{16,19}\\b")
    private val EMAIL_PATTERN = Regex("\\b[\\w.-]+@[\\w.-]+\\.\\w+\\b")

    private val PAYMENT_KEYWORDS = listOf(
        "付款", "支付", "转账", "扣款", "充值", "缴费",
        "确认支付", "立即支付", "授权付款", "代扣",
        "payment", "pay", "transfer",
    )

    private val OTP_KEYWORDS = listOf(
        "验证码", "校验码", "动态密码", "安全码", "短信码",
        "verification code", "OTP", "SMS code",
    )

    private val AUTH_KEYWORDS = listOf(
        "授权", "权限", "同意", "允许", "确认",
        "authorize", "permission", "allow", "confirm",
    )

    private val FRAUD_KEYWORDS = listOf(
        "涉嫌违规", "账户冻结", "安全验证", "紧急处理",
        "公安局", "法院传票", "资金清查", "保证金",
        "中奖", "退款", "客服电话",
    )

    private val SENSITIVE_FIELD_NAMES = setOf(
        "身份证", "证件号", "证件号码", "身份号码",
        "手机号", "电话号码", "联系方式",
        "银行卡", "卡号", "账号",
        "密码", "支付密码", "登录密码",
        "验证码", "校验码",
        "姓名", "真实姓名",
    )

    data class RedactionResult(
        val originalText: String,
        val redactedText: String,
        val wasRedacted: Boolean,
        val detectedCategories: List<String>,
    )

    fun redactText(text: String): RedactionResult {
        val detectedCategories = mutableListOf<String>()
        var result = text

        if (ID_CARD_PATTERN.containsMatchIn(result)) {
            detectedCategories.add("id_card")
            result = ID_CARD_PATTERN.replace(result) { matchResult ->
                val original = matchResult.value
                if (original.length == 18) {
                    "${original.substring(0, 6)}********${original.substring(14)}"
                } else {
                    "[ID_REDACTED]"
                }
            }
        }

        if (PHONE_NUMBER_PATTERN.containsMatchIn(result)) {
            detectedCategories.add("phone_number")
            result = PHONE_NUMBER_PATTERN.replace(result) { matchResult ->
                val original = matchResult.value
                "${original.substring(0, 3)}****${original.substring(7)}"
            }
        }

        if (BANK_CARD_PATTERN.containsMatchIn(result)) {
            detectedCategories.add("bank_card")
            result = BANK_CARD_PATTERN.replace(result, "[CARD_REDACTED]")
        }

        if (EMAIL_PATTERN.containsMatchIn(result)) {
            detectedCategories.add("email")
            result = EMAIL_PATTERN.replace(result, "[EMAIL_REDACTED]")
        }

        val hasOtpContext = OTP_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        if (hasOtpContext && OTP_PATTERN.containsMatchIn(result)) {
            detectedCategories.add("verification_code")
            result = OTP_PATTERN.replace(result, "[OTP_REDACTED]")
        }

        return RedactionResult(
            originalText = text,
            redactedText = result,
            wasRedacted = detectedCategories.isNotEmpty(),
            detectedCategories = detectedCategories,
        )
    }

    fun containsPii(text: String): Boolean {
        return ID_CARD_PATTERN.containsMatchIn(text) ||
            PHONE_NUMBER_PATTERN.containsMatchIn(text) ||
            BANK_CARD_PATTERN.containsMatchIn(text) ||
            EMAIL_PATTERN.containsMatchIn(text)
    }

    fun containsPaymentKeywords(text: String): Boolean {
        val lowerText = text.lowercase()
        return PAYMENT_KEYWORDS.any { lowerText.contains(it.lowercase()) }
    }

    fun containsOtpKeywords(text: String): Boolean {
        val lowerText = text.lowercase()
        return OTP_KEYWORDS.any { lowerText.contains(it.lowercase()) }
    }

    fun containsAuthKeywords(text: String): Boolean {
        val lowerText = text.lowercase()
        return AUTH_KEYWORDS.any { lowerText.contains(it.lowercase()) }
    }

    fun detectFraudIndicators(text: String): List<String> {
        return FRAUD_KEYWORDS.filter { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }

    fun isSensitiveFieldName(fieldName: String): Boolean {
        val normalized = fieldName.lowercase().replace(" ", "").replace("：", "")
        return SENSITIVE_FIELD_NAMES.any { sensitive ->
            normalized.contains(sensitive) || sensitive.contains(normalized)
        }
    }

    fun redactUIElements(elements: List<UIElement>): List<UIElement> {
        return elements.map { element ->
            val textRedaction = redactText(element.text)
            val descRedaction = element.contentDescription?.let { redactText(it) }

            val isSensitiveField = isSensitiveFieldName(element.text) ||
                isSensitiveFieldName(element.contentDescription ?: "")

            element.copy(
                text = if (isSensitiveField && !textRedaction.wasRedacted) {
                    "[SENSITIVE_FIELD_REDACTED]"
                } else {
                    textRedaction.redactedText
                },
                contentDescription = descRedaction?.redactedText ?: element.contentDescription,
                isRedacted = textRedaction.wasRedacted || isSensitiveField,
            )
        }
    }

    fun createScreenObservation(
        pageType: String,
        rawElements: List<UIElement>,
        allText: String,
    ): ScreenObservation {
        val redactedElements = redactUIElements(rawElements)

        val hasPayment = containsPaymentKeywords(allText)
        val hasOtp = containsOtpKeywords(allText)
        val hasAuth = containsAuthKeywords(allText)
        val fraudIndicators = detectFraudIndicators(allText)

        val sensitiveCategories = mutableSetOf<String>()
        if (containsPii(allText)) sensitiveCategories.add("pii_detected")
        if (hasPayment) sensitiveCategories.add("payment")
        if (hasOtp) sensitiveCategories.add("otp")
        if (hasAuth) sensitiveCategories.add("authorization")
        if (fraudIndicators.isNotEmpty()) sensitiveCategories.add("fraud_risk")

        val stillContainsPii = redactedElements.any { element ->
            containsPii(element.text) ||
                (element.contentDescription != null && containsPii(element.contentDescription))
        }

        val cloudSafe = !stillContainsPii &&
            redactedElements.all { !it.isRedacted || it.text.contains("REDACTED") }

        Log.d(
            TAG,
            "Privacy Firewall: pageType=$pageType, " +
                "sensitiveCategories=$sensitiveCategories, " +
                "hasPayment=$hasPayment, hasOtp=$hasOtp, " +
                "fraudIndicators=$fraudIndicators, cloudSafe=$cloudSafe"
        )

        return ScreenObservation(
            pageType = pageType,
            uiElements = redactedElements,
            sensitiveFieldCategories = sensitiveCategories.toList(),
            hasPaymentKeyword = hasPayment,
            hasOtpField = hasOtp,
            hasAuthorizationRequest = hasAuth,
            fraudIndicators = fraudIndicators,
            cloudSafe = cloudSafe,
        )
    }
}
