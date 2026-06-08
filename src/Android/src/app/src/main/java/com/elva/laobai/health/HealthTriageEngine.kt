/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.health

import android.util.Log
import com.elva.laobai.models.CloudPlannerRequest
import com.elva.laobai.models.CloudPlannerResponse
import com.elva.laobai.models.CloudTask
import com.elva.laobai.models.HealthTriageSummary
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel
import com.elva.laobai.models.LocalContextSummary
import com.elva.laobai.memory.LocalUserMemory
import java.util.UUID

/**
 * Health Triage Engine — local-first health consultation state machine
 * for the trigger-based medical consultation assistant (Case 2).
 *
 * Flow (6 stages):
 * INITIAL → SYMPTOM_CLARIFICATION → RISK_PROMPT → ADVICE →
 *   REGISTRATION_WILLINGNESS → CLOUD_PLANNING → COMPLETE
 *
 * Key principles:
 * - NEVER provides medical diagnosis
 * - NEVER recommends specific medications
 * - Always clarifies this is NOT a substitute for a doctor
 * - All cloud data is strictly redacted (age band, not exact age)
 * - High-risk symptoms trigger emergency warning
 */
object HealthTriageEngine {
    private const val TAG = "HealthTriage"

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    /** Consultation stages. */
    enum class Stage {
        /** User just started — extract initial symptoms. */
        INITIAL,

        /** Asking about symptom details (type, location, etc.). */
        SYMPTOM_CLARIFICATION,

        /** Provide risk warning based on symptoms (non-diagnostic). */
        RISK_PROMPT,

        /** Give general advice about seeing a doctor. */
        ADVICE,

        /** Ask if user wants to book a hospital appointment. */
        REGISTRATION_WILLINGNESS,

        /** Preparing and sending redacted data to cloud planner. */
        CLOUD_PLANNING,

        /** Consultation complete — either user declined or booking is done. */
        COMPLETE,
    }

    /**
     * Full state of an ongoing consultation.
     */
    data class ConsultationState(
        val stage: Stage = Stage.INITIAL,
        val symptoms: List<String> = emptyList(),
        val symptomDetails: String = "",
        val duration: String = "",
        val severity: String = "moderate",
        val riskFlags: List<String> = emptyList(),
        val userWantsToBook: Boolean = false,
        val recommendedDepartment: String? = null,
        val cloudResponse: CloudPlannerResponse? = null,
    )

    private var consultationState = ConsultationState()

    /** High-risk symptoms that warrant immediate medical attention. */
    private val EMERGENCY_SYMPTOMS = mapOf(
        "chest_pain_severe" to listOf("胸口剧痛", "胸痛难忍", "心绞痛"),
        "breathing_difficulty" to listOf("呼吸困难", "喘不上气", "气短严重"),
        "consciousness" to listOf("意识模糊", "晕倒", "昏迷", "不省人事"),
        "stroke_signs" to listOf("半边身子动不了", "嘴歪", "说不出话", "突然看不清"),
        "severe_bleeding" to listOf("大出血", "吐血", "便血"),
    )

    /** Known symptom-to-department mapping for local advice. */
    private val SYMPTOM_DEPARTMENT_MAP = mapOf(
        "stomach" to "消化内科",
        "head" to "神经内科",
        "heart" to "心内科",
        "skin" to "皮肤科",
        "bone" to "骨科",
        "eye" to "眼科",
        "ear" to "耳鼻喉科",
        "throat" to "耳鼻喉科",
        "nose" to "耳鼻喉科",
        "tooth" to "口腔科",
        "woman" to "妇科",
        "child" to "儿科",
        "mental" to "心理科",
        "urinary" to "泌尿外科",
    )

    /**
     * Start a new health consultation.
     *
     * @param userText The initial user input (e.g., "我胃不舒服").
     * @return The NextAction response for the first stage.
     */
    fun startConsultation(userText: String): NextAction {
        consultationState = ConsultationState()

        // Extract initial symptoms
        val symptoms = extractSymptoms(userText)

        // Check for emergency symptoms
        val riskFlags = checkEmergencySymptoms(userText)

        consultationState = consultationState.copy(
            symptoms = symptoms,
            riskFlags = riskFlags,
            stage = if (riskFlags.isNotEmpty()) Stage.RISK_PROMPT else Stage.SYMPTOM_CLARIFICATION,
        )

        logDebug("Consultation started: symptoms=$symptoms, riskFlags=$riskFlags")

        return if (riskFlags.isNotEmpty()) {
            buildRiskPrompt()
        } else {
            buildSymptomClarification()
        }
    }

    /**
     * Process the user's response and advance the state machine.
     *
     * @param userText The user's response text.
     * @return The NextAction for the next stage.
     */
    fun processUserResponse(userText: String): NextAction {
        val currentStage = consultationState.stage

        logDebug("Processing response at stage=$currentStage: $userText")

        return when (currentStage) {
            Stage.SYMPTOM_CLARIFICATION -> {
                // Extract duration and details
                val duration = extractDuration(userText)
                val severity = extractSeverity(userText)
                consultationState = consultationState.copy(
                    symptomDetails = userText,
                    duration = duration,
                    severity = severity,
                    stage = Stage.RISK_PROMPT,
                )
                buildRiskPrompt()
            }

            Stage.RISK_PROMPT -> {
                // User acknowledged risk — move to advice
                consultationState = consultationState.copy(stage = Stage.ADVICE)
                buildAdvice()
            }

            Stage.ADVICE -> {
                // Check if user wants to book
                if (isAffirmative(userText)) {
                    consultationState = consultationState.copy(stage = Stage.REGISTRATION_WILLINGNESS)
                    buildRegistrationPrompt()
                } else {
                    consultationState = consultationState.copy(stage = Stage.COMPLETE)
                    buildCompleteMessage()
                }
            }

            Stage.REGISTRATION_WILLINGNESS -> {
                if (isAffirmative(userText)) {
                    consultationState = consultationState.copy(
                        userWantsToBook = true,
                        stage = Stage.CLOUD_PLANNING,
                    )
                    buildCloudPlanningPrompt()
                } else {
                    consultationState = consultationState.copy(stage = Stage.COMPLETE)
                    buildDeclineMessage()
                }
            }

            else -> buildCompleteMessage()
        }
    }

    /**
     * Build a redacted CloudPlannerRequest for the cloud.
     */
    fun buildCloudRequest(): CloudPlannerRequest {
        val state = consultationState

        // Attempt to map symptoms to department
        val guessedDepartment = guessDepartment(state.symptoms)

        val healthSummary = HealthTriageSummary(
            ageBand = computeAgeBand(),
            symptoms = state.symptoms,
            duration = state.duration,
            severity = state.severity,
            riskFlags = state.riskFlags,
            summaryText = buildSummaryText(),
        )

        val localContext = LocalContextSummary(
            preferredHospitalAvailable = isPreferredHospitalAvailable(),
            preferredDepartment = guessedDepartment,
            freeTimeWindows = listOf("tomorrow_morning"),
        )

        return CloudPlannerRequest(
            requestId = UUID.randomUUID().toString(),
            caseType = "health_consultation",
            userGoal = "book_hospital",
            redactionLevel = "strict",
            cloudSafe = true,
            healthSummary = healthSummary,
            localContextSummary = localContext,
            availableTools = listOf("book_hospital"),
        )
    }

    /**
     * Handle the cloud planner response and build the appropriate NextAction.
     */
    fun handleCloudResponse(response: CloudPlannerResponse): NextAction {
        consultationState = consultationState.copy(
            cloudResponse = response,
            recommendedDepartment = response.recommendedDepartment,
            stage = Stage.COMPLETE,
        )

        val dept = response.recommendedDepartment ?: "合适科室"
        val explanation = response.userExplanation.ifBlank {
            "根据您的症状，建议您去${dept}看看。"
        }

        return if (response.requiresConfirmation && response.task != null) {
            // Build a booking action that will trigger book_hospital
            NextAction(
                action = ActionType.ASK_CONFIRMATION,
                targetDescription = "health_booking_${response.task.intent}",
                voicePrompt = "${explanation}\n\n要不要老白帮您挂${dept}的号？",
                explanation = "云端建议挂${dept}，询问用户是否执行挂号",
                riskLevel = RiskLevel.MEDIUM,
                source = "health_cloud",
            )
        } else {
            NextAction(
                action = ActionType.SPEAK_ONLY,
                targetDescription = "health_advice",
                voicePrompt = explanation,
                explanation = "云端健康建议（仅语音）",
                riskLevel = RiskLevel.ZERO,
                source = "health_cloud",
            )
        }
    }

    /**
     * Get the current consultation state.
     */
    fun getState(): ConsultationState = consultationState

    /**
     * Reset the consultation state.
     */
    fun reset() {
        consultationState = ConsultationState()
    }

    // ===== State Machine Step Builders =====

    private fun buildSymptomClarification(): NextAction {
        val symptomList = consultationState.symptoms.joinToString("、")
        val prompt = when {
            symptomList.isNotEmpty() ->
                "大爷，您这${symptomList}有多久了呀？是怎么个不舒服法，比如刺痛还是胀痛？"
            else ->
                "大爷，您是哪里不舒服呀？跟老白详细说说？"
        }
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "symptom_clarification",
            voicePrompt = prompt,
            explanation = "询问症状详情",
            riskLevel = RiskLevel.ZERO,
            source = "health_engine",
        )
    }

    private fun buildRiskPrompt(): NextAction {
        val flags = consultationState.riskFlags
        val prompt = if (flags.isNotEmpty()) {
            val flagDescriptions = flags.map { getRiskFlagDescription(it) }
            buildString {
                append("老白不是医生，不能给您下诊断。")
                append("不过您说的这些情况听起来需要重视：")
                append(flagDescriptions.joinToString("；"))
                append("。建议您尽快去医院看看。")
            }
        } else {
            val symptoms = consultationState.symptoms.joinToString("、")
            buildString {
                append("大爷，老白不是医生，不能给您下诊断。")
                if (consultationState.severity == "severe") {
                    append("您${symptoms}听起来比较严重，建议尽快就医。")
                } else {
                    append("不过${symptoms}如果不舒服得厉害，还是建议去看看医生哦。")
                }
            }
        }
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "risk_prompt",
            voicePrompt = prompt,
            explanation = "非诊断式风险提示",
            riskLevel = RiskLevel.ZERO,
            source = "health_engine",
        )
    }

    private fun buildAdvice(): NextAction {
        val dept = guessDepartment(consultationState.symptoms)
        val prompt = if (dept != null) {
            "根据您的情况，可以考虑去${dept}看看。您觉得呢？"
        } else {
            "建议您去医院让医生看看。您觉得呢？"
        }
        consultationState = consultationState.copy(
            recommendedDepartment = dept,
            stage = Stage.ADVICE,
        )
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "health_advice",
            voicePrompt = prompt,
            explanation = "给出就医建议",
            riskLevel = RiskLevel.ZERO,
            source = "health_engine",
        )
    }

    private fun buildRegistrationPrompt(): NextAction {
        val dept = consultationState.recommendedDepartment
        val prompt = if (dept != null) {
            "要不要老白帮您挂${dept}的号？"
        } else {
            "要不要老白帮您挂个号？"
        }
        return NextAction(
            action = ActionType.ASK_CONFIRMATION,
            targetDescription = "registration_prompt",
            voicePrompt = prompt,
            explanation = "询问是否需要挂号",
            riskLevel = RiskLevel.LOW,
            source = "health_engine",
        )
    }

    private fun buildCloudPlanningPrompt(): NextAction {
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "cloud_planning",
            voicePrompt = "好的，老白帮您整理一下信息，看看怎么挂号最合适...",
            explanation = "准备上云查询挂号建议",
            riskLevel = RiskLevel.LOW,
            source = "health_engine",
        )
    }

    private fun buildCompleteMessage(): NextAction {
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "health_complete",
            voicePrompt = "好的大爷，有需要随时叫老白！多喝热水，注意身体~",
            explanation = "咨询结束",
            riskLevel = RiskLevel.ZERO,
            source = "health_engine",
        )
    }

    private fun buildDeclineMessage(): NextAction {
        return NextAction(
            action = ActionType.SPEAK_ONLY,
            targetDescription = "health_decline",
            voicePrompt = "没关系大爷，如果感觉不舒服得厉害，随时叫老白帮您挂号！",
            explanation = "用户拒绝挂号",
            riskLevel = RiskLevel.ZERO,
            source = "health_engine",
        )
    }

    // ===== Helper Methods =====

    /**
     * Extract symptom keywords from user text.
     */
    private fun extractSymptoms(text: String): List<String> {
        val lowerText = text.lowercase()
        val symptoms = mutableListOf<String>()

        val symptomPatterns = mapOf(
            "stomach" to listOf("胃", "肚子", "腹", "消化"),
            "head" to listOf("头", "头疼", "头晕", "偏头痛"),
            "throat" to listOf("嗓子", "喉咙", "咽", "咳嗽"),
            "fever" to listOf("发烧", "发热", "体温"),
            "chest" to listOf("胸", "心", "心脏"),
            "back" to listOf("腰", "背", "脊椎"),
            "leg" to listOf("腿", "脚", "膝盖", "关节"),
            "skin" to listOf("皮肤", "疹", "痒", "红肿"),
            "nausea" to listOf("恶心", "吐", "呕吐"),
            "fatigue" to listOf("累", "乏力", "没劲", "疲惫"),
        )

        for ((key, patterns) in symptomPatterns) {
            if (patterns.any { lowerText.contains(it) }) {
                symptoms.add(key)
            }
        }

        return symptoms.ifEmpty { listOf("general_discomfort") }
    }

    /**
     * Check for emergency-level symptoms.
     */
    private fun checkEmergencySymptoms(text: String): List<String> {
        val lowerText = text.lowercase()
        val flags = mutableListOf<String>()

        for ((flag, patterns) in EMERGENCY_SYMPTOMS) {
            if (patterns.any { lowerText.contains(it) }) {
                flags.add(flag)
            }
        }

        return flags
    }

    /**
     * Extract duration from user text.
     */
    private fun extractDuration(text: String): String {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("今天") || lowerText.contains("刚刚") -> "today"
            lowerText.contains("昨天") || lowerText.contains("一天") -> "1_day"
            lowerText.contains("两天") || lowerText.contains("2天") -> "2_days"
            lowerText.contains("三天") || lowerText.contains("3天") -> "3_days"
            lowerText.contains("一周") || lowerText.contains("一个星期") -> "1_week"
            lowerText.contains("半个月") -> "half_month"
            lowerText.contains("一个月") -> "1_month"
            lowerText.contains("好久") || lowerText.contains("一直") -> "long_term"
            else -> "unknown"
        }
    }

    /**
     * Extract severity from user text.
     */
    private fun extractSeverity(text: String): String {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("很痛") || lowerText.contains("特别") ||
                lowerText.contains("受不了") || lowerText.contains("厉害") -> "severe"
            lowerText.contains("有点") || lowerText.contains("稍微") ||
                lowerText.contains("不太") || lowerText.contains("还好") -> "mild"
            else -> "moderate"
        }
    }

    /**
     * Guess the appropriate department from symptoms.
     */
    private fun guessDepartment(symptoms: List<String>): String? {
        for (symptom in symptoms) {
            // Direct match
            SYMPTOM_DEPARTMENT_MAP[symptom]?.let { return it }
        }
        // Heuristic matching
        for (symptom in symptoms) {
            for ((key, dept) in SYMPTOM_DEPARTMENT_MAP) {
                if (symptom.contains(key) || key.contains(symptom)) {
                    return dept
                }
            }
        }
        return null
    }

    /**
     * Check if user response is affirmative.
     */
    private fun isAffirmative(text: String): Boolean {
        val lowerText = text.lowercase()
        val affirmativeWords = listOf(
            "好", "可以", "行", "是的", "嗯", "对", "没错",
            "要", "需要", "想", "挂", "yes", "ok", "sure",
        )
        return affirmativeWords.any { lowerText.contains(it) }
    }

    /**
     * Check if preferred hospital is stored in local memory.
     */
    private fun isPreferredHospitalAvailable(): Boolean {
        return LocalUserMemory.state.value["preferred_hospital"]?.isNotBlank() == true
    }

    /**
     * Compute age band from LocalUserMemory for privacy-safe health data.
     * Returns a decade band like "60s", "70s", "80s" or "unknown".
     */
    private fun computeAgeBand(): String {
        val ageStr = LocalUserMemory.state.value["age"] ?: return "unknown"
        val age = ageStr.trim().toIntOrNull() ?: return "unknown"
        val decade = (age / 10) * 10
        return "${decade}s"
    }

    /**
     * Get a human-readable description for a risk flag.
     */
    private fun getRiskFlagDescription(flag: String): String {
        return when (flag) {
            "chest_pain_severe" -> "严重胸痛可能涉及心脏问题"
            "breathing_difficulty" -> "呼吸困难需要立即就医"
            "consciousness" -> "意识问题需要紧急处理"
            "stroke_signs" -> "可能是中风征兆"
            "severe_bleeding" -> "严重出血需要急救"
            else -> "需要医生评估"
        }
    }

    /**
     * Build a redacted summary text for cloud.
     */
    private fun buildSummaryText(): String {
        val state = consultationState
        return buildString {
            append("症状: ${state.symptoms.joinToString(", ")}")
            if (state.duration.isNotEmpty()) append("; 持续时间: ${state.duration}")
            append("; 严重程度: ${state.severity}")
            if (state.riskFlags.isNotEmpty()) {
                append("; 风险标记: ${state.riskFlags.joinToString(", ")}")
            }
        }
    }
}
