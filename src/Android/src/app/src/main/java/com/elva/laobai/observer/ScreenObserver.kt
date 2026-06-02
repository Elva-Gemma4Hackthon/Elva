/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.observer

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.elva.laobai.accessibility.ElvaAccessibilityService
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.models.UIElement
import com.elva.laobai.privacy.PrivacyFirewall

/**
 * Screen Observer — extracts UI node tree from the current screen,
 * then passes it through the Privacy Firewall for redaction.
 *
 * Corresponds to PPT Slide 5, Layer 02: Screen Observation
 */
object ScreenObserver {
    private const val TAG = "ScreenObserver"
    private const val MAX_DEPTH = 15
    private const val MAX_ELEMENTS = 200

    /**
     * Known app package to page type mapping for quick classification.
     */
    private val PAGE_TYPE_HINTS = mapOf(
        "com.eg.android.AlipayGphone" to "alipay",
        "com.tencent.mm" to "wechat",
        "com.android.settings" to "settings",
        "com.android.contacts" to "contacts",
        "com.android.dialer" to "phone_dialer",
        "com.android.mms" to "messaging",
        "com.android.camera" to "camera",
        "com.android.gallery3d" to "gallery",
        "com.tencent.mobileqq" to "qq",
        "com.taobao.taobao" to "shopping",
        "com.jingdong.app.mall" to "shopping",
        "com.meituan" to "local_services",
        "com.sankuai.meituan" to "local_services",
        "com.baidu.BaiduMap" to "navigation",
        "com.autonavi.minimap" to "navigation",
    )

    /**
     * Capture the current screen and create a redacted ScreenObservation.
     *
     * @return ScreenObservation with PII redacted, or null if service not available.
     */
    fun observe(): ScreenObservation? {
        val service = ElvaAccessibilityService.instance ?: run {
            Log.w(TAG, "AccessibilityService not running")
            return null
        }

        val rootNode = service.rootInActiveWindow ?: run {
            Log.w(TAG, "No active window")
            return null
        }

        try {
            // Step 1: Extract raw UI elements
            val rawElements = mutableListOf<UIElement>()
            val allText = StringBuilder()
            extractNodes(rootNode, rawElements, allText, 0)

            // Step 2: Determine page type
            val packageName = rootNode.packageName?.toString() ?: "unknown"
            val pageType = classifyPageType(packageName, rawElements, allText.toString())

            // Step 3: Pass through Privacy Firewall
            val observation = PrivacyFirewall.createScreenObservation(
                pageType = pageType,
                rawElements = rawElements,
                allText = allText.toString(),
            )

            Log.d(TAG, "Screen observed: pageType=${observation.pageType}, " +
                "elements=${rawElements.size}, cloudSafe=${observation.cloudSafe}")

            return observation
        } catch (e: Exception) {
            Log.e(TAG, "Failed to observe screen", e)
            return null
        }
    }

    /**
     * Recursively extract UI nodes into UIElement list.
     */
    private fun extractNodes(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>,
        allText: StringBuilder,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || elements.size >= MAX_ELEMENTS) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // Only include nodes that have meaningful content
        if (text.isNotBlank() || contentDesc.isNotBlank() ||
            node.isClickable || node.isEditable || node.isCheckable) {

            val element = UIElement(
                type = classifyNodeType(node),
                text = text,
                contentDescription = contentDesc.ifBlank { null },
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isRedacted = false,
                viewId = viewId.ifBlank { null },
                boundsDescription = describeBounds(node),
            )
            elements.add(element)

            if (text.isNotBlank()) {
                allText.append(text).append(" ")
            }
            if (contentDesc.isNotBlank()) {
                allText.append(contentDesc).append(" ")
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractNodes(child, elements, allText, depth + 1)
        }
    }

    /**
     * Classify the type of a UI node.
     */
    private fun classifyNodeType(node: AccessibilityNodeInfo): String {
        return when {
            node.isEditable -> "input"
            node.isCheckable -> "checkbox"
            node.className?.contains("Button", ignoreCase = true) == true -> "button"
            node.className?.contains("Image", ignoreCase = true) == true -> "image"
            node.className?.contains("EditText", ignoreCase = true) == true -> "input"
            node.className?.contains("TextView", ignoreCase = true) == true -> "text"
            node.className?.contains("RecyclerView", ignoreCase = true) == true -> "list"
            node.className?.contains("ViewPager", ignoreCase = true) == true -> "pager"
            node.isClickable -> "button"
            else -> "view"
        }
    }

    /**
     * Describe the position of a node in human-friendly terms.
     */
    private fun describeBounds(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val vertical = when {
            cy < 400 -> "top"
            cy < 1200 -> "middle"
            else -> "bottom"
        }
        val horizontal = when {
            cx < 360 -> "left"
            cx < 720 -> "center"
            else -> "right"
        }
        return "$vertical $horizontal"
    }

    /**
     * Classify the page type based on package name and UI content.
     */
    private fun classifyPageType(
        packageName: String,
        elements: List<UIElement>,
        allText: String,
    ): String {
        // Check known package mappings first
        PAGE_TYPE_HINTS[packageName]?.let { return it }

        // Heuristic classification based on UI content
        val lowerText = allText.lowercase()
        return when {
            // Payment/financial
            lowerText.contains("付款") || lowerText.contains("支付") ||
                lowerText.contains("确认订单") -> "payment"

            // Form/input
            elements.count { it.isEditable } >= 2 -> "form"

            // Settings
            lowerText.contains("设置") || lowerText.contains("setting") -> "settings"

            // Login/authentication
            lowerText.contains("登录") || lowerText.contains("密码") ||
                lowerText.contains("login") || lowerText.contains("password") -> "login"

            // Chat
            lowerText.contains("发送") && (lowerText.contains("消息") || lowerText.contains("聊天")) -> "chat"

            // Search
            lowerText.contains("搜索") || lowerText.contains("search") -> "search"

            // Default
            else -> "general"
        }
    }

    /**
     * Capture current screen with OCR fallback.
     * When accessibility node tree has insufficient elements,
     * takes a screenshot and uses text recognition to supplement.
     */
    fun observeWithOcr(screenshot: Bitmap? = null): ScreenObservation? {
        val baseResult = observe() ?: return null

        // If node tree already has enough elements, no need for OCR
        if (baseResult.uiElements.size >= 5) return baseResult

        // If we have a screenshot, try OCR supplement
        if (screenshot != null) {
            val ocrText = performOcr(screenshot)
            if (ocrText.isNotBlank()) {
                val ocrElements = ocrText.lines()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        UIElement(
                            type = "text",
                            text = line.trim(),
                            isClickable = false,
                            isEditable = false,
                            isRedacted = false,
                            boundsDescription = "ocr_detected",
                        )
                    }

                val combinedText = baseResult.uiElements.joinToString(" ") { it.text } +
                    " " + ocrElements.joinToString(" ") { it.text }

                val allElements = baseResult.uiElements + ocrElements
                return PrivacyFirewall.createScreenObservation(
                    pageType = baseResult.pageType,
                    rawElements = allElements,
                    allText = combinedText,
                )
            }
        }

        return baseResult
    }

    private fun performOcr(bitmap: Bitmap): String {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            Log.d(TAG, "OCR: bitmap ${width}x${height}")
            // ML Kit TextRecognition integration point
            ""
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed", e)
            ""
        }
    }
}
