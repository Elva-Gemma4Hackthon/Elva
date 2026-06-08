/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Opens system accessibility settings for Elva LaoBai.
 *
 * Note: Android 15+ blocks third-party apps from launching
 * ACCESSIBILITY_DETAILS_SETTINGS (requires OPEN_ACCESSIBILITY_DETAILS_SETTINGS).
 * We always open the general accessibility settings list instead.
 */
object AccessibilitySettingsNavigator {
    private const val TAG = "ElvaA11yNav"

    fun openElvaAccessibilitySettings(context: Context): Boolean {
        val intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        return try {
            context.startActivity(intent)
            Toast.makeText(
                context,
                "请在列表中找到「Elva LaoBai」并开启",
                Toast.LENGTH_LONG,
            ).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
            Toast.makeText(
                context,
                "无法打开无障碍设置，请手动前往：设置 → 无障碍",
                Toast.LENGTH_LONG,
            ).show()
            false
        }
    }
}
