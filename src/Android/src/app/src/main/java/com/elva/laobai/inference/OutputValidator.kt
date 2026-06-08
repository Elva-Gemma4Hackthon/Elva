/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.inference

import android.util.Log
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel
import org.json.JSONObject

/**
 * Validates the structured output from the Gemma model.
 *
 * Ensures that the model's response can be safely converted into a NextAction.
 * If validation fails, returns null so the caller can fall back to local routing.
 */
object OutputValidator {
    private const val TAG = "OutputValidator"

    /**
     * Validate and parse a JSON string from the model into a NextAction.
     *
     * @param jsonOutput The raw JSON string from the model.
     * @return Parsed NextAction if valid, null if invalid.
     */
    fun parseAndValidate(jsonOutput: String): NextAction? {
        return try {
            val json = parseJson(jsonOutput) ?: return null
            validateAndBuildAction(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse model output: ${e.message}")
            null
        }
    }

    /**
     * Try multiple JSON extraction strategies.
     * The model may wrap JSON in markdown code blocks or add extra text.
     */
    private fun parseJson(raw: String): JSONObject? {
        // Strategy 1: Direct parse
        raw.trim().let {
            if (it.startsWith("{")) {
                return try { JSONObject(it) } catch (_: Exception) { null }
            }
        }

        // Strategy 2: Extract from markdown code block
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")
        codeBlockRegex.find(raw)?.groupValues?.get(1)?.trim()?.let {
            return try { JSONObject(it) } catch (_: Exception) { null }
        }

        // Strategy 3: Find first { ... } block
        val braceRegex = Regex("\\{[\\s\\S]*\\}")
        braceRegex.find(raw)?.value?.let {
            return try { JSONObject(it) } catch (_: Exception) { null }
        }

        return null
    }

    /**
     * Validate the parsed JSON and build a NextAction.
     */
    private fun validateAndBuildAction(json: JSONObject): NextAction? {
        val functionName = json.optString("function").ifBlank { return null }

        // Look up function definition
        val funcDef = ElvaFunctions.getByName(functionName)
        if (funcDef == null) {
            Log.w(TAG, "Unknown function: $functionName")
            return null
        }

        val target = json.optString("target", "")
        val value = json.optString("value").ifBlank { null }
        val voice = json.optString("voice").ifBlank { null }
        val explanation = json.optString("explanation", "")

        // voice is required for all functions
        if (voice.isNullOrBlank()) {
            Log.w(TAG, "Missing required 'voice' field for $functionName")
            return null
        }

        // Determine risk level based on function + context
        val riskLevel = assessRisk(funcDef, target, value)

        return NextAction(
            action = funcDef.actionType,
            targetDescription = target,
            value = value,
            voicePrompt = voice,
            explanation = explanation,
            riskLevel = riskLevel,
            source = "on_device",
        )
    }

    /**
     * Assess the risk level based on function type and parameters.
     */
    private fun assessRisk(
        funcDef: ElvaFunctions.FunctionDef,
        target: String,
        value: String?,
    ): RiskLevel {
        // Emergency stop is always high
        if (funcDef.actionType == ActionType.EMERGENCY_STOP) return RiskLevel.HIGH

        // Typing sensitive values escalates risk
        if (funcDef.actionType == ActionType.TYPE_TEXT && value != null) {
            val lowerValue = value.lowercase()
            if (lowerValue.contains("密码") || lowerValue.contains("验证码")) {
                return RiskLevel.HIGH
            }
        }

        // Clicking payment/confirm buttons escalates risk
        if (funcDef.actionType == ActionType.CLICK_ELEMENT) {
            val lowerTarget = target.lowercase()
            if (lowerTarget.contains("支付") || lowerTarget.contains("付款") ||
                lowerTarget.contains("确认") || lowerTarget.contains("提交") ||
                lowerTarget.contains("转账") || lowerTarget.contains("授权")) {
                return RiskLevel.HIGH
            }
        }

        return funcDef.defaultRiskLevel
    }

    /**
     * Quick check if a string looks like it contains a JSON action.
     */
    fun looksLikeAction(text: String): Boolean {
        return text.contains("\"function\"") || text.contains("\"action\"")
    }
}
