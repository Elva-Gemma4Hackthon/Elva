/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.models

/**
 * Layer 01: Edge Event — trigger event captured on device.
 *
 * Represents the initial event that activates the Elva pipeline.
 * Can be triggered by accessibility events, user voice, notifications, etc.
 */
data class EdgeEvent(
    /** Type of event: form_interaction, voice_request, sms_interception, notification, etc. */
    val eventType: String,

    /** Source component: AccessibilityService, VoiceInput, NotificationListener, etc. */
    val source: String,

    /** Package name of the foreground app, if applicable. */
    val sourcePackage: String? = null,

    /** Confidence of the trigger (0.0 - 1.0). */
    val confidence: Float = 1.0f,

    /** Key phrases that triggered this event. */
    val triggerKeywords: List<String> = emptyList(),

    /** Whether the event contains sensitive data that must stay on-device. */
    val hasSensitiveData: Boolean = false,

    /** Timestamp of the event. */
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Layer 02: Screen Observation — redacted screen analysis result.
 *
 * Contains the structured UI observation with all PII removed.
 * The `cloudSafe` flag is the critical boundary: if false,
 * data MUST NOT be sent to cloud.
 */
data class ScreenObservation(
    /** Detected page type: form, settings, payment, chat, browser, etc. */
    val pageType: String,

    /** Extracted UI elements with sensitive values redacted. */
    val uiElements: List<UIElement>,

    /** Detected sensitive field categories (without actual values). */
    val sensitiveFieldCategories: List<String>,

    /** Whether any payment-related keywords were detected. */
    val hasPaymentKeyword: Boolean = false,

    /** Whether OTP/verification code fields were detected. */
    val hasOtpField: Boolean = false,

    /** Whether authorization/permission request was detected. */
    val hasAuthorizationRequest: Boolean = false,

    /** Fraud risk indicators. */
    val fraudIndicators: List<String> = emptyList(),

    /** CRITICAL: Only true if ALL PII has been redacted. */
    val cloudSafe: Boolean = false,

    /** User's inferred goal from the current screen context. */
    val userGoal: String? = null,

    /** Available tools applicable to this screen. */
    val availableTools: List<String> = emptyList(),

    /** Timestamp of the observation. */
    val timestamp: Long = System.currentTimeMillis(),
)

/** A single UI element extracted from the screen. */
data class UIElement(
    /** Element type: button, input, text, image, checkbox, etc. */
    val type: String,

    /** Element text (redacted if sensitive). */
    val text: String,

    /** Content description (redacted if sensitive). */
    val contentDescription: String? = null,

    /** Whether this element is clickable. */
    val isClickable: Boolean = false,

    /** Whether this element is editable (input field). */
    val isEditable: Boolean = false,

    /** Whether this element's text was redacted. */
    val isRedacted: Boolean = false,

    /** View ID resource name, if available. */
    val viewId: String? = null,

    /** Bounds description (e.g., "center", "top-right") for voice guidance. */
    val boundsDescription: String? = null,
)

/**
 * Layer 03: Routing Decision — local routing result.
 *
 * Determines whether the task can be handled locally,
 * needs cloud planning, requires user input, or must be stopped.
 */
data class RoutingDecision(
    /** Route to take. */
    val route: Route,

    /** Task complexity assessment. */
    val complexity: Complexity,

    /** Reason for this routing decision. */
    val reason: String,

    /** Whether unredacted PII was detected (blocks cloud). */
    val containsUnredactedPii: Boolean = false,

    /** Whether cloud is available and safe to use. */
    val cloudSafe: Boolean = false,

    /** Local fallback available if cloud fails. */
    val localFallback: Boolean = true,

    /** Timestamp. */
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Route {
        /** Handle entirely on-device (e.g., adjust font size). */
        LOCAL_ONLY,

        /** Send redacted observation to cloud planner. */
        CLOUD_PLANNER,

        /** Ask user for clarification or missing info. */
        ASK_USER,

        /** Stop immediately — high risk detected. */
        STOP,
    }

    enum class Complexity {
        LOW,
        MEDIUM,
        HIGH,
        URGENT_DANGER,
    }
}

/**
 * Layer 04: Next Action — the suggested action from the planner.
 *
 * This is a semantic-level action, not a raw click/type command.
 * Must pass through Safety Guard before execution.
 */
data class NextAction(
    /** The action to perform. */
    val action: ActionType,

    /** Human-readable description of the target element. */
    val targetDescription: String,

    /** Value to input, if action is TYPE_TEXT. */
    val value: String? = null,

    /** Voice prompt to speak to the user. */
    val voicePrompt: String,

    /** Explanation of why this action is suggested. */
    val explanation: String,

    /** Risk level of this action. */
    val riskLevel: RiskLevel = RiskLevel.LOW,

    /** Source: "local" or "cloud". */
    val source: String = "local",

    /** Timestamp. */
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class ActionType {
        CLICK_ELEMENT,
        TYPE_TEXT,
        SCROLL,
        NAVIGATE_BACK,
        NAVIGATE_HOME,
        OPEN_APP,
        HIGHLIGHT_ELEMENT,
        SPEAK_ONLY,
        EMERGENCY_STOP,
        ASK_CONFIRMATION,
        GENERATE_SUMMARY,
    }

    enum class RiskLevel {
        ZERO,
        LOW,
        MEDIUM,
        HIGH,
    }
}

/**
 * Layer 05: Guard Decision — the final safety check result.
 *
 * Every NextAction must pass through the Safety Guard.
 * The guard can allow, require user confirmation, or deny.
 */
data class GuardDecision(
    /** The guard's decision. */
    val decision: GuardResult,

    /** Whether human confirmation is required before execution. */
    val requireHumanCheck: Boolean = false,

    /** Risk assessment after guard analysis. */
    val riskLevel: NextAction.RiskLevel = NextAction.RiskLevel.LOW,

    /** Reason for this decision. */
    val reason: String,

    /** Security policy that triggered this decision. */
    val securityPolicy: String? = null,

    /** Whether auto-protection was activated (e.g., fraud blocking). */
    val autoProtect: Boolean = false,

    /** Safe alternative suggestion for the user. */
    val safeAlternative: String? = null,

    /** Timestamp. */
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class GuardResult {
        /** Allow execution. */
        ALLOW,

        /** Require user confirmation before executing. */
        REQUIRE_CONFIRMATION,

        /** Deny — block this action entirely. */
        DENY,
    }
}
