/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.health

import android.util.Log
import com.elva.laobai.inference.ElvaInferenceBridge
import com.elva.laobai.models.CloudPlannerRequest
import com.elva.laobai.models.CloudPlannerResponse
import com.elva.laobai.models.CloudTask
import com.elva.laobai.privacy.PrivacyFirewall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Health Cloud Planner — sends redacted health triage summaries
 * to the cloud Gemma 31B model for department recommendation
 * and booking planning (Case 2).
 *
 * Key principles:
 * - ONLY sends strictly redacted data (age band, symptom categories, etc.)
 * - NEVER sends raw screenshots, names, ID numbers, phone numbers, verification codes
 * - Falls back to local heuristic advice if model is not ready
 * - Parses cloud response into CloudPlannerResponse for on-device action
 */
object HealthCloudPlanner {
    private const val TAG = "HealthCloudPlanner"

    data class PlannerState(
        val isPlanning: Boolean = false,
        val lastResponse: CloudPlannerResponse? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(PlannerState())
    val state = _state.asStateFlow()

    /**
     * Send a health consultation request to the cloud planner.
     *
     * @param request The redacted CloudPlannerRequest.
     * @param onResult Called with the parsed CloudPlannerResponse.
     * @param onError Called if planning fails, with error message.
     * @param onFallback Called with a local fallback response.
     */
    fun plan(
        request: CloudPlannerRequest,
        onResult: (CloudPlannerResponse) -> Unit,
        onError: (String) -> Unit,
        onFallback: (CloudPlannerResponse) -> Unit = {},
    ) {
        // CRITICAL: Never send data if cloudSafe is false
        if (!request.cloudSafe) {
            Log.w(TAG, "Blocked: cloudSafe=false, not sending to cloud")
            onError("数据含有未脱敏信息，已阻止上云")
            onFallback(planLocalFallback(request))
            return
        }

        _state.value = PlannerState(isPlanning = true)

        val bridge = ElvaInferenceBridge
        if (!bridge.state.value.isModelReady) {
            Log.d(TAG, "Model not ready, using local fallback")
            _state.value = PlannerState(isPlanning = false)
            val fallback = planLocalFallback(request)
            onFallback(fallback)
            return
        }

        // Build the prompt for the cloud model
        val prompt = buildHealthPlannerPrompt(request)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                bridge.infer(
                    input = prompt,
                    onPartialResult = { /* Streaming not needed for planning */ },
                    onDone = { responseText ->
                        _state.value = PlannerState(isPlanning = false)
                        val parsed = parseCloudResponse(responseText, request)
                        _state.value = _state.value.copy(lastResponse = parsed)
                        onResult(parsed)
                    },
                    onError = { error ->
                        Log.e(TAG, "Cloud planner inference error: $error")
                        _state.value = PlannerState(isPlanning = false, lastError = error)
                        onError(error)
                        val fallback = planLocalFallback(request)
                        onFallback(fallback)
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Cloud planner failed", e)
                _state.value = PlannerState(isPlanning = false, lastError = e.message)
                onError(e.message ?: "云规划失败")
                val fallback = planLocalFallback(request)
                onFallback(fallback)
            }
        }
    }

    /**
     * Local fallback planner — gives heuristic advice based on symptoms.
     * Used when the cloud model is not available or cloudSafe is false.
     */
    fun planLocalFallback(request: CloudPlannerRequest): CloudPlannerResponse {
        val summary = request.healthSummary
        if (summary == null) {
            return CloudPlannerResponse(
                decision = "ambiguous",
                reason = "no_health_summary",
                userExplanation = "大爷，老白不太确定您的情况，建议您直接去医院让医生看看。",
                riskLevel = "medium",
                requiresConfirmation = true,
            )
        }

        // Emergency check
        val hasEmergencyFlag = summary.riskFlags.any { flag ->
            flag in listOf("chest_pain_severe", "breathing_difficulty",
                "consciousness", "stroke_signs", "severe_bleeding")
        }

        if (hasEmergencyFlag) {
            return CloudPlannerResponse(
                decision = "recommend_emergency",
                reason = "emergency_symptoms_detected",
                recommendedDepartment = "急诊科",
                userExplanation = "大爷，您说的这些症状需要立即就医！老白建议您尽快去最近的医院挂急诊，或者拨打120。",
                riskLevel = "high",
                requiresConfirmation = false,
            )
        }

        // Guess department from symptoms
        val department = guessDepartmentFromSymptoms(summary.symptoms)

        return if (summary.severity == "severe") {
            CloudPlannerResponse(
                decision = "recommend_hospital",
                reason = "severe_symptoms",
                recommendedDepartment = department,
                riskLevel = "medium",
                requiresConfirmation = true,
                userExplanation = "您的症状听起来需要尽快就医。建议去${department ?: "相关科室"}看看。",
                preparationItems = listOf("身份证", "医保卡", "既往病历（如有）"),
            )
        } else {
            CloudPlannerResponse(
                decision = "recommend_hospital",
                reason = "symptom_triage",
                recommendedDepartment = department,
                task = CloudTask(
                    intent = "book_hospital",
                    parameters = mapOf(
                        "department" to (department ?: ""),
                    ),
                ),
                riskLevel = "medium",
                requiresConfirmation = true,
                userExplanation = "根据您的情况，建议您去${department ?: "医院"}看看。要帮您挂号吗？",
                preparationItems = listOf("身份证", "医保卡"),
            )
        }
    }

    /**
     * Build the prompt for the cloud model with the health request.
     */
    private fun buildHealthPlannerPrompt(request: CloudPlannerRequest): String {
        val sb = StringBuilder()
        sb.appendLine("【健康咨询请求】")
        sb.appendLine("案例类型: ${request.caseType}")
        sb.appendLine("用户目标: ${request.userGoal}")
        sb.appendLine()

        request.healthSummary?.let { summary ->
            sb.appendLine("【脱敏健康摘要】")
            sb.appendLine("年龄段: ${summary.ageBand}")
            sb.appendLine("症状: ${summary.symptoms.joinToString(", ")}")
            sb.appendLine("持续时长: ${summary.duration}")
            sb.appendLine("严重程度: ${summary.severity}")
            if (summary.riskFlags.isNotEmpty()) {
                sb.appendLine("风险标记: ${summary.riskFlags.joinToString(", ")}")
            }
            sb.appendLine()
        }

        request.localContextSummary?.let { context ->
            sb.appendLine("【本地上下文】")
            sb.appendLine("有首选医院: ${if (context.preferredHospitalAvailable) "是" else "否"}")
            context.preferredDepartment?.let { sb.appendLine("首选科室: $it") }
            sb.appendLine()
        }

        sb.appendLine("可用工具: ${request.availableTools.joinToString(", ")}")
        sb.appendLine()

        sb.appendLine("""请以JSON格式回复（不要包含其他文字）：
{
  "decision": "recommend_hospital 或 recommend_home_care 或 recommend_emergency",
  "reason": "规划理由",
  "recommended_department": "建议科室",
  "risk_level": "low 或 medium 或 high",
  "requires_confirmation": true,
  "user_explanation": "对老人说的话（亲切温和，不做诊断）"
}""")

        return sb.toString()
    }

    /**
     * Parse the cloud model's response into a CloudPlannerResponse.
     */
    private fun parseCloudResponse(responseText: String, request: CloudPlannerRequest): CloudPlannerResponse {
        return try {
            val json = extractJson(responseText)
            if (json != null) {
                CloudPlannerResponse(
                    decision = json.optString("decision", "plan"),
                    reason = json.optString("reason", ""),
                    recommendedDepartment = json.optString("recommended_department", null),
                    task = CloudTask(
                        intent = "book_hospital",
                        parameters = mapOf(
                            "department" to (json.optString("recommended_department", "")),
                        ),
                    ),
                    riskLevel = json.optString("risk_level", "medium"),
                    requiresConfirmation = json.optBoolean("requires_confirmation", true),
                    userExplanation = json.optString("user_explanation", ""),
                )
            } else {
                // If JSON parse fails, treat entire response as user explanation
                CloudPlannerResponse(
                    decision = "plan",
                    reason = "text_response",
                    userExplanation = responseText.take(300),
                    riskLevel = "medium",
                    requiresConfirmation = true,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cloud response", e)
            planLocalFallback(request)
        }
    }

    /**
     * Try to extract JSON from the model's response.
     */
    private fun extractJson(raw: String): JSONObject? {
        raw.trim().let {
            if (it.startsWith("{")) {
                return try { JSONObject(it) } catch (_: Exception) { null }
            }
        }

        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)```")
        codeBlockRegex.find(raw)?.groupValues?.get(1)?.trim()?.let {
            return try { JSONObject(it) } catch (_: Exception) { null }
        }

        val braceRegex = Regex("\\{[\\s\\S]*\\}")
        braceRegex.find(raw)?.value?.let {
            return try { JSONObject(it) } catch (_: Exception) { null }
        }

        return null
    }

    /**
     * Simple symptom-to-department mapping for local fallback.
     */
    private fun guessDepartmentFromSymptoms(symptoms: List<String>): String? {
        val mapping = mapOf(
            "stomach" to "消化内科",
            "head" to "神经内科",
            "heart" to "心内科",
            "skin" to "皮肤科",
            "bone" to "骨科",
            "eye" to "眼科",
            "ear" to "耳鼻喉科",
            "throat" to "耳鼻喉科",
            "nose" to "耳鼻喉科",
            "fever" to "发热门诊",
            "nausea" to "消化内科",
            "chest" to "呼吸内科",
            "back" to "骨科",
            "leg" to "骨科",
            "fatigue" to "全科",
        )
        for (symptom in symptoms) {
            mapping[symptom]?.let { return it }
        }
        return "全科"
    }
}
