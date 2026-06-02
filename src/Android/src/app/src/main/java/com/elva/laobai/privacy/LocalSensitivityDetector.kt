/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.privacy

import android.util.Log

/**
 * Semantic PII detector using the on-device Gemma model.
 *
 * Goes beyond regex-based detection by understanding context:
 * - "我住在朝阳区望京街道" -> detects as address even without "省/市"
 * - "发给138xxxx" in a chat context -> detects as phone-related
 * - "王明明天来" -> distinguishes name from regular text
 *
 * Combines regex results (from PrivacyFirewall) with semantic analysis
 * for higher accuracy.
 */
object LocalSensitivityDetector {
    private const val TAG = "LocalSensitivity"

    /**
     * Result of semantic sensitivity detection.
     */
    data class SensitivityResult(
        val hasPii: Boolean,
        val detectedCategories: List<String>,
        val confidence: Float,
        val details: List<String>,
    )

    /**
     * Detect sensitive information using semantic analysis.
     * This uses the on-device model for deeper understanding.
     *
     * For now, implements enhanced heuristic detection.
     * When the model is loaded, it will be supplemented with model inference.
     */
    fun detectSemanticPii(text: String): SensitivityResult {
        val categories = mutableListOf<String>()
        val details = mutableListOf<String>()

        // Check for address patterns (enhanced beyond regex)
        if (detectAddress(text)) {
            categories.add("address")
            details.add("检测到地址信息")
        }

        // Check for name patterns using context
        val nameDetection = detectNameInContext(text)
        if (nameDetection != null) {
            categories.add("name")
            details.add(nameDetection)
        }

        // Check for financial context
        if (detectFinancialContext(text)) {
            categories.add("financial")
            details.add("检测到金融相关敏感上下文")
        }

        // Check for health/medical information
        if (detectHealthInfo(text)) {
            categories.add("health")
            details.add("检测到健康/医疗信息")
        }

        // Cross-reference with regex-based detection
        val regexResult = PrivacyFirewall.redactText(text)
        if (regexResult.wasRedacted) {
            categories.addAll(regexResult.detectedCategories)
            details.add("正则匹配确认: ${regexResult.detectedCategories.joinToString()}")
        }

        val confidence = if (categories.size > 1) 0.9f else if (categories.size == 1) 0.7f else 0f

        return SensitivityResult(
            hasPii = categories.isNotEmpty(),
            detectedCategories = categories.distinct(),
            confidence = confidence,
            details = details,
        )
    }

    /**
     * Enhanced address detection using context clues.
     */
    private fun detectAddress(text: String): Boolean {
        // Look for Chinese address patterns
        val addressPatterns = listOf(
            Regex("\\d+号[楼弄座]?"),
            Regex("[东西南北]?\\d+楼\\d+单元\\d+室"),
            Regex("[省市区县镇乡村街道路巷弄].{2,20}[号楼层室]"),
        )
        return addressPatterns.any { it.containsMatchIn(text) }
    }

    /**
     * Detect names in context (e.g., "请告诉王明" vs "明天来").
     */
    private fun detectNameInContext(text: String): String? {
        // Look for verbs commonly followed by names
        val nameContextVerbs = listOf(
            "叫", "是", "发给", "转给", "联系", "找", "请", "通知", "告诉",
        )
        for (verb in nameContextVerbs) {
            val idx = text.indexOf(verb)
            if (idx >= 0) {
                val after = text.substring(idx + verb.length).trim()
                // Check if next 2-3 chars look like a name
                if (after.length >= 2) {
                    val candidate = after.take(3)
                    if (PrivacyFirewall.isLikelyNameContent(candidate, null)) {
                        return "在\"$verb\"后面检测到可能的姓名: ${candidate.first()}**"
                    }
                }
            }
        }
        return null
    }

    /**
     * Detect financial/transaction context that may contain sensitive info.
     */
    private fun detectFinancialContext(text: String): Boolean {
        val financialPatterns = listOf(
            "余额", "存款", "理财", "基金", "股票", "投资",
            "信用卡", "借记卡", "花呗", "借呗", "微粒贷",
            "还款", "账单", "额度",
        )
        return financialPatterns.any { text.contains(it) }
    }

    /**
     * Detect health/medical information.
     */
    private fun detectHealthInfo(text: String): Boolean {
        val healthPatterns = listOf(
            "诊断", "病历", "处方", "用药", "手术",
            "住院", "体检", "血压", "血糖", "心脏",
        )
        return healthPatterns.any { text.contains(it) }
    }

    /**
     * Combine regex + semantic results for comprehensive detection.
     */
    fun comprehensiveCheck(text: String): SensitivityResult {
        val semantic = detectSemanticPii(text)
        val regex = PrivacyFirewall.redactText(text)

        val allCategories = (semantic.detectedCategories + regex.detectedCategories).distinct()
        val maxConfidence = maxOf(semantic.confidence, if (regex.wasRedacted) 0.95f else 0f)

        return SensitivityResult(
            hasPii = allCategories.isNotEmpty(),
            detectedCategories = allCategories,
            confidence = maxConfidence,
            details = semantic.details,
        )
    }
}
