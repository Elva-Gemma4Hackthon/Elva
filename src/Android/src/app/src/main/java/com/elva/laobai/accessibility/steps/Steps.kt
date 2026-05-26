/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility.steps

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.elva.laobai.accessibility.ElvaAccessibilityService

/**
 * A single step in an accessibility automation sequence.
 */
data class Step(
    val description: String,
    val delayMs: Long? = null,
    val execute: suspend (ElvaAccessibilityService, Context) -> StepResult,
)

data class StepResult(
    val success: Boolean,
    val message: String = "",
)

// ===== Reusable Step Builders =====

/** Launch an app by package name. */
fun launchAppStep(packageName: String, description: String): Step {
    return Step(description = description) { service, context ->
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                StepResult(true, "Launched $packageName")
            } else {
                // Try opening Play Store as fallback
                val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
                StepResult(true, "Opened Play Store for $packageName")
            }
        } catch (e: Exception) {
            StepResult(false, "无法打开应用: ${e.message}")
        }
    }
}

/** Launch a URL in browser. */
fun launchUrlStep(url: String, description: String): Step {
    return Step(description = description) { _, context ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StepResult(true, "Opened $url")
        } catch (e: Exception) {
            StepResult(false, "无法打开链接: ${e.message}")
        }
    }
}

/** Wait for a specific app to be in foreground. */
fun waitForAppStep(packageName: String, timeoutMs: Long = 10000L): Step {
    return Step(description = "等待应用加载...", delayMs = 2000L) { service, _ ->
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (service.getCurrentPackage() == packageName) {
                return@Step StepResult(true, "App is in foreground")
            }
            kotlinx.coroutines.delay(500)
        }
        // Continue even if package doesn't match exactly (webview, etc.)
        StepResult(true, "Proceeding (app package check skipped)")
    }
}

/** Click a node by its displayed text. */
fun clickByTextStep(text: String, description: String? = null): Step {
    val desc = description ?: "点击 \"$text\""
    return Step(description = desc) { service, _ ->
        val clicked = service.clickByText(text)
        if (clicked) {
            StepResult(true, "Clicked: $text")
        } else {
            StepResult(false, "找不到 \"$text\" 按钮，请手动操作")
        }
    }
}

/** Click a node by text, with fallback to partial match. */
fun clickByTextFuzzyStep(text: String, fallbackText: String? = null): Step {
    return Step(description = "点击 \"$text\"") { service, _ ->
        var clicked = service.clickByText(text, contains = true)
        if (!clicked && fallbackText != null) {
            clicked = service.clickByText(fallbackText, contains = true)
        }
        if (clicked) {
            StepResult(true, "Clicked successfully")
        } else {
            StepResult(false, "找不到 \"$text\" 按钮")
        }
    }
}

/** Type text into a field identified by hint text. */
fun typeInFieldStep(hintText: String, value: String, description: String? = null): Step {
    val desc = description ?: "输入 $value"
    return Step(description = desc) { service, _ ->
        val typed = service.typeInField(hintText, value)
        if (typed) {
            StepResult(true, "Typed: $value")
        } else {
            // Fallback: try focused field
            val fallback = service.typeText(value)
            if (fallback) {
                StepResult(true, "Typed into focused field: $value")
            } else {
                StepResult(false, "找不到输入框，请手动输入")
            }
        }
    }
}

/** Press the back button. */
fun pressBackStep(description: String = "返回"): Step {
    return Step(description = description) { service, _ ->
        service.pressBack()
        StepResult(true, "Pressed back")
    }
}

/** Press the home button. */
fun pressHomeStep(): Step {
    return Step(description = "回到桌面") { service, _ ->
        service.pressHome()
        StepResult(true, "Pressed home")
    }
}

/** Delay for a specified time. */
fun delayStep(ms: Long, description: String = "等待..."): Step {
    return Step(description = description, delayMs = ms) { _, _ ->
        StepResult(true, "Waited ${ms}ms")
    }
}

/** Generic confirmation step — just waits for user to see result. */
fun confirmationStep(message: String): Step {
    return Step(description = message, delayMs = 3000L) { _, _ ->
        StepResult(true, message)
    }
}
