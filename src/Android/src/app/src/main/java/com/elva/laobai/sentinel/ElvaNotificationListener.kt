/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.sentinel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.elva.laobai.guard.ScamGuard
import com.elva.laobai.models.EdgeEvent
import com.elva.laobai.models.GuardDecision
import com.elva.laobai.models.GuardDecision.GuardResult
import com.elva.laobai.privacy.PrivacyFirewall

/**
 * Notification Listener Service for Elva LaoBai.
 *
 * Monitors incoming notifications (SMS, WeChat, Alipay, etc.)
 * and feeds them into the ScamGuard for real-time fraud analysis.
 *
 * High-risk notifications (verification code + payment) trigger
 * immediate Sentinel alerts.
 *
 * Requires BIND_NOTIFICATION_LISTENER_SERVICE permission.
 */
class ElvaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ElvaNotifListener"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Packages that are commonly associated with sensitive operations. */
        private val SENSITIVE_PACKAGES = setOf(
            "com.tencent.mm",          // WeChat
            "com.eg.android.AlipayGphone", // Alipay
            "com.android.mms",         // SMS
            "com.google.android.apps.messaging", // Messages
            "com.android.messaging",
            "com.tencent.mobileqq",    // QQ
            "com.eg.android.AlipayGphone:ui",
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.d(TAG, "Elva Notification Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
        Log.d(TAG, "Elva Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        // Only process notifications from sensitive packages
        if (!SENSITIVE_PACKAGES.any { packageName.startsWith(it) }) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract text from notification
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val fullText = "$title $text $bigText".trim()

        if (fullText.isBlank()) return

        Log.d(TAG, "Notification from $packageName: ${fullText.take(80)}")

        // Analyze for scam patterns
        analyzeNotification(fullText, packageName)
    }

    /**
     * Analyze notification text for fraud indicators.
     */
    private fun analyzeNotification(text: String, packageName: String) {
        // Check for scam patterns
        val scamResult = ScamGuard.analyze(text)
        if (scamResult != null) {
            Log.w(TAG, "SCAM detected in notification from $packageName")
            triggerScamAlert(text, packageName, scamResult)
            return
        }

        // Check for OTP + payment combination
        val hasOtp = PrivacyFirewall.containsOtpKeywords(text)
        val hasPayment = PrivacyFirewall.containsPaymentKeywords(text)
        val hasFraudIndicators = PrivacyFirewall.detectFraudIndicators(text)

        if (hasOtp && hasPayment) {
            Log.w(TAG, "OTP + Payment detected in notification from $packageName")
            triggerHighRiskAlert(text, packageName)
        } else if (hasFraudIndicators.isNotEmpty()) {
            Log.w(TAG, "Fraud indicators in notification: ${hasFraudIndicators.joinToString()}")
            triggerFraudWarning(text, packageName, hasFraudIndicators)
        }
    }

    private fun triggerScamAlert(text: String, packageName: String, decision: GuardDecision) {
        val alertMessage = decision.safeAlternative ?: "检测到可疑通知，请小心！"
        com.elva.laobai.ElvaTtsManager.speak(alertMessage)

        // Feed to Sentinel for state tracking
        AlwaysOnSentinel.onAccessibilityEvent(
            eventType = 0,
            packageName = packageName,
            text = text,
            className = "notification",
        )
    }

    private fun triggerHighRiskAlert(text: String, packageName: String) {
        val message = "大爷注意！老白发现您收到了验证码，同时有付款相关通知。" +
            "千万不要把验证码告诉任何人！如有疑问请联系家人。"
        com.elva.laobai.ElvaTtsManager.speak(message)

        AlwaysOnSentinel.onAccessibilityEvent(
            eventType = 0,
            packageName = packageName,
            text = text,
            className = "notification_otp_payment",
        )
    }

    private fun triggerFraudWarning(text: String, packageName: String, indicators: List<String>) {
        val message = "大爷，老白发现通知里有可疑内容：${indicators.joinToString("、")}。" +
            "请小心，建议不要点击。"
        com.elva.laobai.ElvaTtsManager.speak(message)

        AlwaysOnSentinel.onAccessibilityEvent(
            eventType = 0,
            packageName = packageName,
            text = text,
            className = "notification_fraud",
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed when notification is removed
    }
}
