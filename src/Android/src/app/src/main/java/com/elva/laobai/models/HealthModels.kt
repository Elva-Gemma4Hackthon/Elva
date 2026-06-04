/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.models

/**
 * Health triage summary — strictly redacted health information
 * that is safe to send to the cloud planner.
 *
 * Contains ONLY the minimum necessary information for
 * department recommendation and booking planning.
 * NEVER includes: original screenshots, names, ID numbers,
 * phone numbers, verification codes, full medical records.
 */
data class HealthTriageSummary(
    /** Age band (e.g., "60-70", "70-80", "80+") — never exact age. */
    val ageBand: String = "",

    /** Symptom descriptions (e.g., ["stomach_discomfort", "nausea"]). */
    val symptoms: List<String> = emptyList(),

    /** Duration of symptoms (e.g., "2_days", "1_week"). */
    val duration: String = "",

    /** Severity level: "mild", "moderate", or "severe". */
    val severity: String = "moderate",

    /** Risk flags that may indicate urgent conditions. */
    val riskFlags: List<String> = emptyList(),

    /** Redacted summary text for the cloud model to understand context. */
    val summaryText: String = "",
)

/**
 * Local context summary for cloud planner.
 *
 * Tells the cloud what resources are available on the device
 * without exposing raw personal data.
 */
data class LocalContextSummary(
    /** Whether the user has a preferred hospital saved locally. */
    val preferredHospitalAvailable: Boolean = false,

    /** Preferred department (redacted label only). */
    val preferredDepartment: String? = null,

    /** Free time windows from local calendar (redacted). */
    val freeTimeWindows: List<String>? = null,
)

/**
 * Request payload sent to the cloud Gemma 31B planner.
 *
 * All fields are strictly redacted — no PII, no raw screenshots,
 * no original medical records. See Section 5.1 of the development doc.
 */
data class CloudPlannerRequest(
    /** Unique request identifier. */
    val requestId: String,

    /** Case type: "health_consultation" or "always_on_form". */
    val caseType: String,

    /** User's goal (e.g., "book_hospital", "health_advice"). */
    val userGoal: String,

    /** Redaction level: must be "strict" for cloud-safe data. */
    val redactionLevel: String = "strict",

    /** CRITICAL: Must be true for data to be sent to cloud. */
    val cloudSafe: Boolean = false,

    /** Redacted health summary. */
    val healthSummary: HealthTriageSummary? = null,

    /** Redacted local context. */
    val localContextSummary: LocalContextSummary? = null,

    /** Tools available on the device for execution. */
    val availableTools: List<String> = emptyList(),

    /** Timestamp. */
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Cloud task specification returned by the planner.
 * Defines what intent to execute and with what parameters.
 */
data class CloudTask(
    /** Intent name (e.g., "book_hospital"). */
    val intent: String,

    /** Task parameters (e.g., hospital, department, date). */
    val parameters: Map<String, String> = emptyMap(),
)

/**
 * Response from the cloud Gemma 31B planner.
 *
 * The cloud only provides planning recommendations, never direct
 * GUI coordinates or execution commands. See Section 5.2.
 */
data class CloudPlannerResponse(
    /** Decision: "plan", "recommend_hospital", "recommend_home_care",
     *  "recommend_emergency", "ambiguous". */
    val decision: String,

    /** Human-readable reasoning for the decision. */
    val reason: String = "",

    /** Recommended department (e.g., "消化内科"). */
    val recommendedDepartment: String? = null,

    /** Task to execute on-device, or null if only advice is given. */
    val task: CloudTask? = null,

    /** Risk level: "low", "medium", "high". */
    val riskLevel: String = "medium",

    /** Whether the task requires user confirmation before execution. */
    val requiresConfirmation: Boolean = true,

    /** Elderly-friendly explanation to speak to the user. */
    val userExplanation: String = "",

    /** Suggested materials the user should prepare for the visit. */
    val preparationItems: List<String> = emptyList(),

    /** Timestamp. */
    val timestamp: Long = System.currentTimeMillis(),
)
