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
 * Health Local Planner — uses on-device Gemma 4 model for health consultation
 * planning (Case 2).
 *
 * NOTE: Despite the class name containing "Cloud", ALL inference runs locally
 * on the device via ElvaInferenceBridge + Gemma 4. No data is ever sent to
 * any cloud service. The name is retained for codebase compatibility.
 *
 * Key principles:
 * - ONLY processes strictly redacted data (age band, symptom categories, etc.)
 * - NEVER sends raw screenshots, names, ID numbers, phone numbers, verification codes
 * - Falls back to local heuristic advice if model is not ready
 * - Parses response into CloudPlannerResponse for on-device action
 */
object HealthCloudPlanner {
    private const val TAG = "HealthCloudPlanner"

    /**
     * CloudAdapter — pluggable interface for cloud communication.
     * Allows swapping between real HTTP, local model inference, or mock.
     */
    interface CloudAdapter {
        /** Send a prompt to the cloud and get a response string. */
        suspend fun infer(prompt: String): String?
    }

    /** On-device adapter using ElvaInferenceBridge for Gemma 4 inference. */
    private class DefaultCloudAdapter : CloudAdapter {
        override suspend fun infer(prompt: String): String? {
            val bridge = ElvaInferenceBridge
            if (!bridge.state.value.isModelReady) return null
            var result: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            bridge.infer(
                input = prompt,
                onPartialResult = { },
                onDone = { responseText ->
                    result = responseText
                    latch.countDown()
                },
                onError = { _ ->
                    latch.countDown()
                },
            )
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return result
        }
    }

    /** Current adapter instance. Can be swapped for testing. */
    var adapter: CloudAdapter = DefaultCloudAdapter()

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

        // Build the prompt for the cloud model
        val prompt = buildHealthPlannerPrompt(request)

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val responseText = adapter.infer(prompt)
                if (responseText != null) {
                    _state.value = PlannerState(isPlanning = false)
                    val parsed = parseCloudResponse(responseText, request)
                    _state.value = _state.value.copy(lastResponse = parsed)
                    onResult(parsed)
                } else {
                    Log.d(TAG, "Adapter returned null, using local fallback")
                    _state.value = PlannerState(isPlanning = false)
                    val fallback = planLocalFallback(request)
                    onFallback(fallback)
                }
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
        sb.appendLine("\u3010\u5065\u5eb7\u54a8\u8be2\u8bf7\u6c42\u3011")
        sb.appendLine("\u6848\u4f8b\u7c7b\u578b: ${request.caseType}")
        sb.appendLine("\u7528\u6237\u76ee\u6807: ${request.userGoal}")
        sb.appendLine()

        request.healthSummary?.let { summary ->
            sb.appendLine("\u3010\u8131\u654f\u5065\u5eb7\u6458\u8981\u3011")
            sb.appendLine("\u5e74\u9f84\u6bb5: ${summary.ageBand}")
            sb.appendLine("\u75c7\u72b6: ${summary.symptoms.joinToString(", ")}")
            sb.appendLine("\u6301\u7eed\u65f6\u957f: ${summary.duration}")
            sb.appendLine("\u4e25\u91cd\u7a0b\u5ea6: ${summary.severity}")
            if (summary.riskFlags.isNotEmpty()) {
                sb.appendLine("\u98ce\u9669\u6807\u8bb0: ${summary.riskFlags.joinToString(", ")}")
            }
            sb.appendLine()
        }

        request.localContextSummary?.let { context ->
            sb.appendLine("\u3010\u672c\u5730\u4e0a\u4e0b\u6587\u3011")
            sb.appendLine("\u6709\u9996\u9009\u533b\u9662: ${if (context.preferredHospitalAvailable) "\u662f" else "\u5426"}")
            context.preferredDepartment?.let { sb.appendLine("\u9996\u9009\u79d1\u5ba4: $it") }
            sb.appendLine()
        }

        sb.appendLine("\u53ef\u7528\u5de5\u5177: ${request.availableTools.joinToString(", ")}")
        sb.appendLine()

        sb.appendLine("""\u8bf7\u4ee5JSON\u683c\u5f0f\u56de\u590d\uff08\u4e0d\u8981\u5305\u542b\u5176\u4ed6\u6587\u5b57\uff09\uff1a
{
  "decision": "recommend_hospital \u6216 recommend_home_care \u6216 recommend_emergency",
  "reason": "\u89c4\u5212\u7406\u7531",
  "recommended_department": "\u5efa\u8bae\u79d1\u5ba4",
  "risk_level": "low \u6216 medium \u6216 high",
  "requires_confirmation": true,
  "user_explanation": "\u5bf9\u8001\u4eba\u8bf4\u7684\u8bdd\uff08\u4eb2\u5207\u6e29\u548c\uff0c\u4e0d\u505a\u8bca\u65ad\uff09"
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
                    recommendedDepartment = json.optString("recommended_department").ifBlank { null },
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
