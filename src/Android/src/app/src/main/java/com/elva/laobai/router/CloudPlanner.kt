/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.router

import android.util.Log
import com.elva.laobai.inference.ElvaInferenceBridge
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.ScreenObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object CloudPlanner {
    private const val TAG = "CloudPlanner"

    // NOTE: Despite the name, ALL inference runs on-device via ElvaInferenceBridge + Gemma 4.
    // No data is ever sent to any cloud service.

    data class PlannerState(
        val isPlanning: Boolean = false,
        val lastAction: NextAction? = null,
        val lastError: String? = null,
        val lastSource: String = "none",
    )

    private val _state = MutableStateFlow(PlannerState())
    val state = _state.asStateFlow()

    interface PlannerCallback {
        fun onAction(action: NextAction)
        fun onFallback(text: String)
        fun onError(error: String)
    }

    fun plan(
        observation: ScreenObservation?,
        userText: String,
        callback: PlannerCallback,
    ) {
        val bridge = ElvaInferenceBridge
        Log.d(TAG, "plan: userText='$userText', modelReady=${bridge.state.value.isModelReady}")

        if (!bridge.state.value.isModelReady) {
            Log.w(TAG, "plan: model not ready, using fallback action")
            callback.onAction(generateFallbackAction(userText))
            return
        }

        _state.value = PlannerState(isPlanning = true)

        CoroutineScope(Dispatchers.Main).launch {
            bridge.inferWithFunctions(
                observation = observation,
                userText = userText,
                onAction = { action ->
                    Log.d(TAG, "plan: onAction action=${action.action}, target=${action.targetDescription}")
                    _state.value = PlannerState(isPlanning = false, lastAction = action, lastSource = "on_device")
                    callback.onAction(action)
                },
                onFallback = { text ->
                    Log.d(TAG, "plan: onFallback text='$text'")
                    val fallbackAction = NextAction(
                        action = ActionType.SPEAK_ONLY,
                        targetDescription = "on_device_fallback",
                        voicePrompt = text,
                        explanation = "On-device model returned unstructured text",
                        riskLevel = NextAction.RiskLevel.LOW,
                        source = "on_device_text",
                    )
                    _state.value = PlannerState(isPlanning = false, lastAction = fallbackAction, lastSource = "on_device_text")
                    callback.onFallback(text)
                },
                onError = { error ->
                    Log.e(TAG, "plan: onError error=$error")
                    _state.value = PlannerState(isPlanning = false, lastError = error, lastSource = "error")
                    callback.onError(error)
                    callback.onAction(generateFallbackAction(userText))
                },
            )
        }
    }

    fun generateFallbackAction(userText: String): NextAction {
        val response = when {
            userText.contains("几点") || userText.contains("时间") -> {
                val sdf = java.text.SimpleDateFormat("HH:mm, EEEE", java.util.Locale.CHINESE)
                "现在是${sdf.format(java.util.Date())}。"
            }
            userText.contains("照片") || userText.contains("相册") -> "好的，帮您打开相册啦~"
            userText.contains("拍照") || userText.contains("照相机") -> "好的，帮您打开相机~"
            userText.contains("你好") || userText.contains("hello", ignoreCase = true) -> "您好呀！我是老白，有什么能帮您的吗？"
            userText.contains("交电费") -> "好的，正在帮您打开交电费页面~"
            userText.contains("交水费") -> "好的，正在帮您打开交水费页面~"
            userText.contains("挂号") -> "好的，正在帮您打开挂号页面~"
            userText.contains("打") && userText.contains("电话") -> "好的，正在帮您拨打电话~"
            userText.contains("转账") || userText.contains("汇款") -> "这很可能是诈骗！请不要转账。"
            else -> "让我想想怎么帮您~"
        }
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "local_fallback",
            voicePrompt = response,
            explanation = "Local fallback",
            riskLevel = NextAction.RiskLevel.ZERO,
            source = "local",
        )
    }
}
