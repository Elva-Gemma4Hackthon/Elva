/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.router

import android.util.Log
import com.elva.laobai.inference.ElvaInferenceBridge
import com.elva.laobai.inference.LocalSummarizer
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.ScreenObservation
import com.elva.laobai.guard.SafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Local Advisor — handles simple, low-risk requests entirely on-device.
 *
 * Used for queries that don't need cloud planning:
 * - Time/date questions
 * - Opening simple apps
 * - Explaining current screen
 * - General conversation
 *
 * Combines pattern matching with on-device model inference
 * for fast, private responses.
 */
object LocalAdvisor {
    private const val TAG = "LocalAdvisor"

    /**
     * Check if a user request can be handled locally.
     * Returns true for simple, low-risk queries.
     */
    fun canHandleLocally(userText: String, observation: ScreenObservation?): Boolean {
        val localPatterns = listOf(
            "几点", "时间", "今天", "日期",
            "你好", "hello", "hi",
            "谢谢", "感谢",
            "照片", "相册", "拍照", "相机",
            "设置",
            "什么页面", "这是什么", "看不懂",
        )

        return localPatterns.any { userText.contains(it, ignoreCase = true) } ||
            observation?.pageType in listOf("settings", "camera", "gallery")
    }

    /**
     * Handle a local request synchronously.
     * Returns a NextAction without needing cloud or model inference.
     */
    fun handleLocal(userText: String, observation: ScreenObservation?): NextAction {
        val response = when {
            // Time queries
            userText.contains("几点") || userText.contains("时间") -> {
                val sdf = java.text.SimpleDateFormat("HH:mm, EEEE", java.util.Locale.CHINESE)
                "现在是${sdf.format(java.util.Date())}。"
            }
            // Date queries
            userText.contains("今天") || userText.contains("日期") -> {
                val sdf = java.text.SimpleDateFormat("yyyy年M月d日, EEEE", java.util.Locale.CHINESE)
                "今天是${sdf.format(java.util.Date())}。"
            }
            // Greetings
            userText.contains("你好") || userText.contains("hello", ignoreCase = true) ->
                "您好呀！我是老白，有什么能帮您的吗？"
            // Thanks
            userText.contains("谢谢") || userText.contains("感谢") ->
                "不客气！有事随时叫老白~"
            // Screen explanation
            userText.contains("什么页面") || userText.contains("这是什么") ||
                userText.contains("看不懂") -> {
                if (observation != null) {
                    LocalSummarizer.summarizeForVoice(observation)
                } else {
                    "让我看看屏幕上有什么...抱歉，现在看不了。"
                }
            }
            // Photo/album
            userText.contains("照片") || userText.contains("相册") ->
                "好的，帮您打开相册啦~"
            // Camera
            userText.contains("拍照") || userText.contains("相机") ->
                "好的，帮您打开相机~"
            // Settings
            userText.contains("设置") ->
                "好的，帮您打开设置~"
            // Default
            else -> "让我想想怎么帮您~"
        }

        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "local_advisor",
            voicePrompt = response,
            explanation = "Local advisor response",
            riskLevel = NextAction.RiskLevel.ZERO,
            source = "local_advisor",
        )
    }

    /**
     * Handle a local request with on-device model enhancement.
     * Falls back to pattern matching if model is not available.
     */
    fun handleLocalWithModel(
        userText: String,
        observation: ScreenObservation?,
        onResult: (NextAction) -> Unit,
    ) {
        val bridge = ElvaInferenceBridge

        if (!bridge.state.value.isModelReady) {
            // Model not ready, use pattern matching
            onResult(handleLocal(userText, observation))
            return
        }

        // Use the model for a better response
        val contextPrompt = if (observation != null) {
            "当前屏幕: ${LocalSummarizer.summarizeForCloud(observation)}\n老人说: $userText"
        } else {
            "老人说: $userText"
        }

        CoroutineScope(Dispatchers.Main).launch {
            bridge.infer(
                input = contextPrompt,
                onPartialResult = { /* streaming not needed for local */ },
                onDone = { response ->
                    val action = NextAction(
                        action = ActionType.SPEAK_ONLY,
                        targetDescription = "local_advisor_model",
                        voicePrompt = response,
                        explanation = "Local advisor with model enhancement",
                        riskLevel = NextAction.RiskLevel.ZERO,
                        source = "local_model",
                    )
                    // Still run through safety guard
                    val guardDecision = SafetyGuard.evaluate(action, observation)
                    if (guardDecision.decision == com.elva.laobai.models.GuardDecision.GuardResult.ALLOW) {
                        onResult(action)
                    } else {
                        // Guard blocked model response, fall back to patterns
                        onResult(handleLocal(userText, observation))
                    }
                },
                onError = { _ ->
                    onResult(handleLocal(userText, observation))
                },
            )
        }
    }
}
