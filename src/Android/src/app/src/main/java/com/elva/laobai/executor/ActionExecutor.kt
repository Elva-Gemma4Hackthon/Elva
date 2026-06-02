/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.elva.laobai.accessibility.ElvaAccessibilityService
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.observer.ScreenObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Action Executor — executes validated NextActions on the device.
 *
 * Flow:
 * 1. Validate action against ToolRegistry whitelist
 * 2. Run pre-execution guard check
 * 3. Execute the action via AccessibilityService
 * 4. Run post-execution guard check
 * 5. Record the action for replay
 *
 * From PPT Slide 5: "OpenClaw Gateway: 只执行白名单工具"
 */
object ActionExecutor {
    private const val TAG = "ActionExec"
    private const val ACTION_DELAY_MS = 1000L

    data class ExecutionResult(
        val success: Boolean,
        val action: NextAction,
        val message: String,
        val preCheckPassed: Boolean = true,
        val postCheckPassed: Boolean = true,
    )

    data class ExecutorState(
        val isExecuting: Boolean = false,
        val lastResult: ExecutionResult? = null,
        val executedCount: Int = 0,
    )

    private val _state = MutableStateFlow(ExecutorState())
    val state = _state.asStateFlow()

    /**
     * Execute a validated NextAction.
     *
     * @param action The action to execute (must have passed GuardDecision.ALLOW).
     * @param context Android context for launching apps.
     * @param onResult Callback with execution result.
     */
    fun execute(
        action: NextAction,
        context: Context,
        onResult: (ExecutionResult) -> Unit,
    ) {
        if (_state.value.isExecuting) {
            Log.w(TAG, "Another action is already executing")
            onResult(ExecutionResult(
                success = false,
                action = action,
                message = "Another action is executing",
            ))
            return
        }

        // Step 1: Validate against tool whitelist
        val validation = ToolRegistry.validateAction(action)
        if (!validation.allowed) {
            Log.w(TAG, "Action blocked by ToolRegistry: ${validation.reason}")
            onResult(ExecutionResult(
                success = false,
                action = action,
                message = validation.reason,
            ))
            return
        }

        _state.value = ExecutorState(isExecuting = true)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Step 2: Pre-execution check
                val preCheck = if (ToolRegistry.requiresPreCheck(action.action)) {
                    GuardChecks.preCheck(action)
                } else {
                    GuardChecks.CheckResult.pass()
                }

                if (!preCheck.passed) {
                    Log.w(TAG, "Pre-check failed: ${preCheck.reason}")
                    _state.value = ExecutorState(isExecuting = false)
                    onResult(ExecutionResult(
                        success = false,
                        action = action,
                        message = "前置检查失败: ${preCheck.reason}",
                        preCheckPassed = false,
                    ))
                    return@launch
                }

                // Step 3: Execute the action
                val execSuccess = performAction(action, context)

                delay(ACTION_DELAY_MS)

                // Step 4: Post-execution check
                val postCheck = if (ToolRegistry.requiresPostCheck(action.action) && execSuccess) {
                    GuardChecks.postCheck(action)
                } else {
                    GuardChecks.CheckResult.pass()
                }

                if (!postCheck.passed) {
                    Log.w(TAG, "Post-check failed: ${postCheck.reason}")
                    // Rollback: press back
                    ElvaAccessibilityService.instance?.pressBack()
                }

                // Step 5: Record for replay
                if (execSuccess) {
                    ActionReplay.record(action)
                }

                val result = ExecutionResult(
                    success = execSuccess,
                    action = action,
                    message = if (execSuccess) "执行成功" else "执行失败",
                    preCheckPassed = preCheck.passed,
                    postCheckPassed = postCheck.passed,
                )

                _state.value = ExecutorState(
                    isExecuting = false,
                    lastResult = result,
                    executedCount = _state.value.executedCount + 1,
                )

                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Action execution error", e)
                _state.value = ExecutorState(isExecuting = false)
                onResult(ExecutionResult(
                    success = false,
                    action = action,
                    message = "执行出错: ${e.message}",
                ))
            }
        }
    }

    /**
     * Perform the actual action via AccessibilityService.
     */
    private suspend fun performAction(action: NextAction, context: Context): Boolean {
        val service = ElvaAccessibilityService.instance
        if (service == null && action.action != ActionType.SPEAK_ONLY) {
            Log.w(TAG, "AccessibilityService not running")
            return false
        }

        return when (action.action) {
            ActionType.CLICK_ELEMENT -> {
                val target = action.targetDescription
                val clicked = service?.clickByText(target) ?: false
                Log.d(TAG, "Click '$target': $clicked")
                clicked
            }

            ActionType.TYPE_TEXT -> {
                val value = action.value ?: return false
                val target = action.targetDescription
                val typed = service?.typeInField(target, value) ?: false
                if (!typed) {
                    service?.typeText(value) ?: false
                } else true
            }

            ActionType.SCROLL -> {
                // Scroll direction is in targetDescription
                val direction = action.targetDescription.lowercase()
                when {
                    direction.contains("up") -> {
                        // Scroll up via swipe gesture
                        service?.performGlobalAction(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                        )
                        true
                    }
                    else -> true // Scroll down is default, handled by UI
                }
            }

            ActionType.NAVIGATE_BACK -> {
                service?.pressBack() ?: false
                true
            }

            ActionType.NAVIGATE_HOME -> {
                service?.pressHome() ?: false
                true
            }

            ActionType.OPEN_APP -> {
                val appName = action.targetDescription
                val packageName = resolveAppPackage(appName)
                if (packageName != null) {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        true
                    } else false
                } else {
                    // Try opening as URL
                    false
                }
            }

            ActionType.HIGHLIGHT_ELEMENT -> {
                // Highlight is a visual-only action, no actual execution needed
                Log.d(TAG, "Highlight: ${action.targetDescription}")
                true
            }

            ActionType.SPEAK_ONLY -> {
                com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
                true
            }

            ActionType.EMERGENCY_STOP -> {
                // Emergency stop: press home and speak warning
                service?.pressHome()
                com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
                true
            }

            ActionType.ASK_CONFIRMATION -> {
                com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
                true
            }

            ActionType.GENERATE_SUMMARY -> {
                // Summary generation is handled by FamilyAssist
                com.elva.laobai.ElvaTtsManager.speak(action.voicePrompt)
                true
            }
        }
    }

    /**
     * Resolve common Chinese app names to package names.
     */
    private fun resolveAppPackage(name: String): String? {
        return when {
            name.contains("微信") -> "com.tencent.mm"
            name.contains("支付宝") -> "com.eg.android.AlipayGphone"
            name.contains("相机") || name.contains("拍照") -> "com.android.camera"
            name.contains("相册") || name.contains("照片") -> "com.android.gallery3d"
            name.contains("设置") -> "com.android.settings"
            name.contains("电话") || name.contains("拨号") -> "com.android.dialer"
            name.contains("短信") || name.contains("信息") -> "com.android.mms"
            name.contains("QQ") -> "com.tencent.mobileqq"
            name.contains("淘宝") -> "com.taobao.taobao"
            name.contains("京东") -> "com.jingdong.app.mall"
            name.contains("美团") -> "com.sankuai.meituan"
            name.contains("地图") || name.contains("导航") -> "com.baidu.BaiduMap"
            else -> null
        }
    }
}
