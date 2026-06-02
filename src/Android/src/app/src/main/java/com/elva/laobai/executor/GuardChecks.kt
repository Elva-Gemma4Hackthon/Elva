/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.executor

import android.util.Log
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.observer.ScreenObserver
import com.elva.laobai.privacy.PrivacyFirewall

/**
 * Pre/Post execution guard checks.
 *
 * Pre-check: Verifies screen state matches expectations before action.
 * Post-check: Verifies execution result is safe (no unexpected dialogs, payments, etc.)
 *
 * From PPT Slide 5:
 * - "Guard: 付款、验证码、提交、授权等动作必须确认或拒绝"
 * - Guard failure -> automatic rollback (pressBack) + user notification
 */
object GuardChecks {
    private const val TAG = "GuardChecks"

    data class CheckResult(
        val passed: Boolean,
        val reason: String,
    ) {
        companion object {
            fun pass(): CheckResult = CheckResult(true, "Check passed")
            fun fail(reason: String): CheckResult = CheckResult(false, reason)
        }
    }

    /**
     * Pre-execution check: verify screen is in expected state.
     *
     * Checks:
     * 1. AccessibilityService is running
     * 2. Target element is visible on screen
     * 3. No unexpected risk dialogs appeared since last observation
     */
    fun preCheck(action: NextAction): CheckResult {
        // Check 1: Service running
        if (!com.elva.laobai.accessibility.ElvaAccessibilityService.isRunning()) {
            return CheckResult.fail("无障碍服务未运行")
        }

        // Check 2: For click/type actions, verify target element exists
        if (action.action == ActionType.CLICK_ELEMENT || action.action == ActionType.TYPE_TEXT) {
            val observation = ScreenObserver.observe()
            if (observation == null) {
                return CheckResult.fail("无法获取当前屏幕信息")
            }

            val target = action.targetDescription
            val elementExists = observation.uiElements.any {
                it.text.contains(target, ignoreCase = true) ||
                    it.contentDescription?.contains(target, ignoreCase = true) == true
            }

            if (!elementExists) {
                Log.w(TAG, "Pre-check: target '$target' not found on screen")
                return CheckResult.fail("找不到目标元素: $target")
            }
        }

        // Check 3: No sudden risk indicators
        val currentObservation = ScreenObserver.observe()
        if (currentObservation != null) {
            if (currentObservation.hasPaymentKeyword && currentObservation.hasOtpField) {
                return CheckResult.fail("页面突然出现付款+验证码，中止操作")
            }
            if (currentObservation.fraudIndicators.isNotEmpty()) {
                return CheckResult.fail("页面出现诈骗指标，中止操作")
            }
        }

        return CheckResult.pass()
    }

    /**
     * Post-execution check: verify the action result is safe.
     *
     * Checks:
     * 1. No unexpected error dialogs appeared
     * 2. No surprise payment/OTP dialogs after the action
     * 3. No fraudulent content appeared
     */
    fun postCheck(action: NextAction): CheckResult {
        val observation = ScreenObserver.observe()
        if (observation == null) {
            // Can't verify, assume safe for low-risk actions
            return if (action.riskLevel.ordinal <= NextAction.RiskLevel.LOW.ordinal) {
                CheckResult.pass()
            } else {
                CheckResult.fail("无法验证执行结果")
            }
        }

        // Check 1: Unexpected payment/OTP after action
        if (observation.hasPaymentKeyword && observation.hasOtpField) {
            Log.w(TAG, "Post-check: Payment + OTP appeared after action!")
            return CheckResult.fail("操作后出现付款+验证码页面")
        }

        // Check 2: Fraud indicators appeared
        if (observation.fraudIndicators.isNotEmpty()) {
            Log.w(TAG, "Post-check: Fraud indicators appeared!")
            return CheckResult.fail("操作后出现诈骗内容")
        }

        // Check 3: Error dialogs
        val errorElements = observation.uiElements.filter {
            it.text.contains("错误", ignoreCase = true) ||
                it.text.contains("失败", ignoreCase = true) ||
                it.text.contains("error", ignoreCase = true) ||
                it.text.contains("异常", ignoreCase = true)
        }

        if (errorElements.isNotEmpty()) {
            Log.w(TAG, "Post-check: Error elements detected: ${errorElements.map { it.text }}")
            // Don't fail for errors, but log them
            // Some errors are normal (e.g., "该功能暂不可用")
        }

        return CheckResult.pass()
    }
}
