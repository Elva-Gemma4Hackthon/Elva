/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.executor

import android.util.Log
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel

/**
 * Tool Registry — whitelist of allowed execution tools.
 *
 * Every action that the ActionExecutor can perform must be registered
 * here with its risk level, pre-check, and post-check requirements.
 *
 * From PPT Slide 5: "OpenClaw Gateway: 只执行白名单工具；高风险动作先过Guard"
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"

    /**
     * Definition of an executable tool.
     */
    data class ToolDef(
        val name: String,
        val actionType: ActionType,
        val riskLevel: RiskLevel,
        val requiresPreCheck: Boolean,
        val requiresPostCheck: Boolean,
        val description: String,
    )

    /** All registered tools (whitelist). */
    private val tools = mutableMapOf<ActionType, ToolDef>()

    init {
        // Register built-in tools
        register(ToolDef(
            name = "emergency_stop",
            actionType = ActionType.EMERGENCY_STOP,
            riskLevel = RiskLevel.HIGH,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Stop automation and warn the user",
        ))
        register(ToolDef(
            name = "click_element",
            actionType = ActionType.CLICK_ELEMENT,
            riskLevel = RiskLevel.MEDIUM,
            requiresPreCheck = true,
            requiresPostCheck = true,
            description = "Click a UI element by text",
        ))
        register(ToolDef(
            name = "type_text",
            actionType = ActionType.TYPE_TEXT,
            riskLevel = RiskLevel.MEDIUM,
            requiresPreCheck = true,
            requiresPostCheck = true,
            description = "Type text into an input field",
        ))
        register(ToolDef(
            name = "scroll",
            actionType = ActionType.SCROLL,
            riskLevel = RiskLevel.ZERO,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Scroll the screen",
        ))
        register(ToolDef(
            name = "navigate_back",
            actionType = ActionType.NAVIGATE_BACK,
            riskLevel = RiskLevel.ZERO,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Press back button",
        ))
        register(ToolDef(
            name = "navigate_home",
            actionType = ActionType.NAVIGATE_HOME,
            riskLevel = RiskLevel.ZERO,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Press home button",
        ))
        register(ToolDef(
            name = "open_app",
            actionType = ActionType.OPEN_APP,
            riskLevel = RiskLevel.ZERO,
            requiresPreCheck = false,
            requiresPostCheck = true,
            description = "Open an application",
        ))
        register(ToolDef(
            name = "highlight_element",
            actionType = ActionType.HIGHLIGHT_ELEMENT,
            riskLevel = RiskLevel.ZERO,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Highlight a UI element for guidance",
        ))
        register(ToolDef(
            name = "speak",
            actionType = ActionType.SPEAK_ONLY,
            riskLevel = RiskLevel.ZERO,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Speak a message via TTS",
        ))
        register(ToolDef(
            name = "ask_confirmation",
            actionType = ActionType.ASK_CONFIRMATION,
            riskLevel = RiskLevel.HIGH,
            requiresPreCheck = false,
            requiresPostCheck = false,
            description = "Ask the user to manually confirm before continuing",
        ))
        register(ToolDef(
            name = "generate_summary",
            actionType = ActionType.GENERATE_SUMMARY,
            riskLevel = RiskLevel.MEDIUM,
            requiresPreCheck = true,
            requiresPostCheck = false,
            description = "Generate a local summary",
        ))
    }

    /**
     * Register a new tool.
     */
    fun register(tool: ToolDef) {
        tools[tool.actionType] = tool
        Log.d(TAG, "Registered tool: ${tool.name} (${tool.actionType})")
    }

    /**
     * Check if an action type is allowed (in the whitelist).
     */
    fun isAllowed(actionType: ActionType): Boolean {
        return tools.containsKey(actionType)
    }

    /**
     * Get the tool definition for an action type.
     */
    fun getTool(actionType: ActionType): ToolDef? {
        return tools[actionType]
    }

    /**
     * Check if an action requires pre-execution guard check.
     */
    fun requiresPreCheck(actionType: ActionType): Boolean {
        return tools[actionType]?.requiresPreCheck ?: true // Default to safe
    }

    /**
     * Check if an action requires post-execution guard check.
     */
    fun requiresPostCheck(actionType: ActionType): Boolean {
        return tools[actionType]?.requiresPostCheck ?: true // Default to safe
    }

    /**
     * Get all registered tool names.
     */
    fun getAllToolNames(): List<String> {
        return tools.values.map { it.name }
    }

    /**
     * Validate that a NextAction is allowed by the tool registry.
     */
    fun validateAction(action: NextAction): ValidationResult {
        // Emergency stop is always allowed
        if (action.action == ActionType.EMERGENCY_STOP) {
            return ValidationResult(allowed = true, reason = "Emergency stop always allowed")
        }

        val tool = tools[action.action] ?: return ValidationResult(
            allowed = false,
            reason = "Action type ${action.action} is not in the whitelist",
        )

        // Check risk level alignment
        if (action.riskLevel.ordinal > tool.riskLevel.ordinal) {
            return ValidationResult(
                allowed = false,
                reason = "Action risk level (${action.riskLevel}) exceeds tool limit (${tool.riskLevel})",
            )
        }

        return ValidationResult(allowed = true, reason = "Action validated")
    }

    data class ValidationResult(
        val allowed: Boolean,
        val reason: String,
    )
}
