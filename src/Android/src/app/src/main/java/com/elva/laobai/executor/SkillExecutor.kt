/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.executor

import android.content.Context
import android.util.Log
import com.elva.laobai.inference.ElvaFunctions
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import com.elva.laobai.models.NextAction.RiskLevel
import com.elva.laobai.guard.SafetyGuard
import com.elva.laobai.forms.FormTemplateMatcher
import com.elva.laobai.forms.FormFillEngine
import com.elva.laobai.health.HealthTriageEngine
import com.elva.laobai.health.HealthCloudPlanner
import com.elva.laobai.memory.LocalUserMemory

/**
 * Skill Executor - dynamic skill system that bridges Function Calling
 * with the Action execution layer.
 *
 * Responsibilities:
 * 1. Migrates hardcoded A11yTaskExecutor tasks into dynamic skills
 * 2. Each Skill maps to a whitelisted ToolRegistry tool
 * 3. Converts LLM Function Calling output into executable NextActions
 * 4. Supports multi-step skill composition (a skill = sequence of NextActions)
 * 5. Supports dynamic registration of new skills at runtime
 *
 * From Plan Task 4.7: Skill system integration
 */
object SkillExecutor {
    private const val TAG = "SkillExec"

    /**
     * Definition of a skill - a named, parameterized action sequence.
     */
    data class SkillDef(
        /** Unique skill identifier. */
        val id: String,
        /** Human-readable skill name (Chinese). */
        val displayName: String,
        /** Description of what this skill does. */
        val description: String,
        /** Required parameter names. */
        val requiredParams: List<String>,
        /** Optional parameter names with defaults. */
        val optionalParams: Map<String, String> = emptyMap(),
        /** Risk level of this skill. */
        val riskLevel: RiskLevel = RiskLevel.LOW,
        /** Keywords that trigger this skill (for intent matching). */
        val triggerKeywords: List<String>,
        /** Builder function: params -> list of NextActions. */
        val buildActions: (Map<String, String>) -> List<NextAction>,
    )

    /**
     * Result of skill execution.
     */
    data class SkillResult(
        val skillId: String,
        val success: Boolean,
        val completedActions: Int,
        val totalActions: Int,
        val message: String,
        val failedAction: NextAction? = null,
    )

    /** All registered skills. */
    private val skills = mutableMapOf<String, SkillDef>()

    /** Execution history for replay support. */
    private val executionHistory = mutableListOf<Pair<SkillDef, Map<String, String>>>()
    private const val MAX_HISTORY = 50

    init {
        // Register built-in skills migrated from A11yTaskExecutor
        registerBuiltInSkills()
    }

    /**
     * Register a new skill dynamically.
     */
    fun registerSkill(skill: SkillDef) {
        skills[skill.id] = skill
        Log.d(TAG, "Registered skill: ${skill.id} (${skill.displayName})")
    }

    /**
     * Register skills from a SKILL.md-style definition string.
     */
    fun registerFromDefinition(definition: String) {
        val sections = definition.split(Regex("(?=\\n## )")).filter { it.trim().startsWith("##") }
        for (section in sections) {
            try {
                val lines = section.trim().lines()
                val id = lines[0].removePrefix("##").trim()
                var name = ""
                var desc = ""
                var params = listOf<String>()
                var keywords = listOf<String>()
                var risk = RiskLevel.LOW

                for (line in lines.drop(1)) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("name:") -> name = trimmed.removePrefix("name:").trim()
                        trimmed.startsWith("description:") -> desc = trimmed.removePrefix("description:").trim()
                        trimmed.startsWith("params:") -> params = trimmed.removePrefix("params:").trim()
                            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        trimmed.startsWith("keywords:") -> keywords = trimmed.removePrefix("keywords:").trim()
                            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        trimmed.startsWith("risk:") -> {
                            val riskStr = trimmed.removePrefix("risk:").trim().uppercase()
                            risk = try { RiskLevel.valueOf(riskStr) } catch (_: Exception) { RiskLevel.LOW }
                        }
                    }
                }

                if (id.isNotEmpty() && name.isNotEmpty()) {
                    registerSkill(SkillDef(
                        id = id,
                        displayName = name,
                        description = desc,
                        requiredParams = params,
                        riskLevel = risk,
                        triggerKeywords = keywords,
                        buildActions = { _ ->
                            listOf(NextAction(
                                action = ActionType.SPEAK_ONLY,
                                targetDescription = name,
                                voicePrompt = "正在执行 $name...",
                                explanation = desc,
                                riskLevel = risk,
                                source = "skill",
                            ))
                        },
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse skill definition: ${e.message}")
            }
        }
    }

    // ===== Skill Matching =====

    /**
     * Find the best matching skill for the given user text.
     * Returns the skill with the most keyword matches, or null.
     */
    fun matchSkill(userText: String): SkillDef? {
        val normalized = userText.lowercase().trim()
        var bestMatch: SkillDef? = null
        var bestScore = 0

        for (skill in skills.values) {
            var score = 0
            for (keyword in skill.triggerKeywords) {
                if (normalized.contains(keyword)) {
                    score++
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = skill
            }
        }

        if (bestMatch != null) {
            Log.d(TAG, "Matched skill: ${bestMatch.id} (score=$bestScore) for '$userText'")
        }
        return bestMatch
    }

    /**
     * Extract parameters from user text for a given skill.
     */
    fun extractParams(skill: SkillDef, userText: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        for (param in skill.requiredParams + skill.optionalParams.keys) {
            val patterns = listOf(
                Regex("(?i)${Regex.escape(param)}[：是\\s]+(\\S+)"),
                Regex("(?i)${Regex.escape(param)}\\s*是\\s*(\\S+)"),
            )
            for (pattern in patterns) {
                val match = pattern.find(userText)
                if (match != null) {
                    params[param] = match.groupValues[1]
                    break
                }
            }
            if (param !in params && param in skill.optionalParams) {
                params[param] = skill.optionalParams[param] ?: ""
            }
        }
        return params
    }

    // ===== Skill Execution =====

    /**
     * Execute a skill by ID with the given parameters.
     * Each NextAction is validated through ToolRegistry and SafetyGuard.
     */
    fun executeSkill(
        skillId: String,
        params: Map<String, String>,
        context: Context,
        onProgress: (Int, Int, String) -> Unit,
        onComplete: (SkillResult) -> Unit,
    ) {
        val skill = skills[skillId]
        if (skill == null) {
            onComplete(SkillResult(
                skillId = skillId,
                success = false,
                completedActions = 0,
                totalActions = 0,
                message = "未找到技能: $skillId",
            ))
            return
        }

        val actions = skill.buildActions(params)
        if (actions.isEmpty()) {
            onComplete(SkillResult(
                skillId = skillId,
                success = false,
                completedActions = 0,
                totalActions = 0,
                message = "技能 $skillId 没有可执行的动作",
            ))
            return
        }

        // Record for replay
        executionHistory.add(skill to params)
        if (executionHistory.size > MAX_HISTORY) {
            executionHistory.removeAt(0)
        }

        var completedCount = 0
        val totalActions = actions.size

        for ((index, action) in actions.withIndex()) {
            // Step 1: Validate against ToolRegistry
            val validation = ToolRegistry.validateAction(action)
            if (!validation.allowed) {
                Log.w(TAG, "Skill action blocked: ${validation.reason}")
                onComplete(SkillResult(
                    skillId = skillId,
                    success = false,
                    completedActions = completedCount,
                    totalActions = totalActions,
                    message = "动作被安全策略阻止: ${validation.reason}",
                    failedAction = action,
                ))
                return
            }

            // Step 2: Check with SafetyGuard
            val guardResult = SafetyGuard.evaluate(action, null)
            if (guardResult.decision == com.elva.laobai.models.GuardDecision.GuardResult.DENY) {
                Log.w(TAG, "Skill action denied by SafetyGuard: ${guardResult.reason}")
                onComplete(SkillResult(
                    skillId = skillId,
                    success = false,
                    completedActions = completedCount,
                    totalActions = totalActions,
                    message = "安全守卫拒绝: ${guardResult.reason}",
                    failedAction = action,
                ))
                return
            }

            // Step 3: Execute via ActionExecutor
            onProgress(index + 1, totalActions, action.voicePrompt)

            ActionExecutor.execute(action, context) { result ->
                if (result.success) {
                    completedCount++
                } else {
                    onComplete(SkillResult(
                        skillId = skillId,
                        success = false,
                        completedActions = completedCount,
                        totalActions = totalActions,
                        message = "动作执行失败: ${result.message}",
                        failedAction = action,
                    ))
                    return@execute
                }

                if (completedCount == totalActions) {
                    onComplete(SkillResult(
                        skillId = skillId,
                        success = true,
                        completedActions = completedCount,
                        totalActions = totalActions,
                        message = "技能${skill.displayName} 执行完成",
                    ))
                }
            }
        }
    }

    /**
     * Execute a matched skill from user text input.
     */
    fun executeFromText(
        userText: String,
        context: Context,
        onProgress: (Int, Int, String) -> Unit,
        onComplete: (SkillResult) -> Unit,
    ) {
        val skill = matchSkill(userText)
        if (skill == null) {
            onComplete(SkillResult(
                skillId = "unknown",
                success = false,
                completedActions = 0,
                totalActions = 0,
                message = "未匹配到任何技能",
            ))
            return
        }
        val params = extractParams(skill, userText)
        executeSkill(skill.id, params, context, onProgress, onComplete)
    }

    // ===== Query Methods =====

    /** Get all registered skill definitions. */
    fun getAllSkills(): List<SkillDef> = skills.values.toList()

    /** Get a specific skill by ID. */
    fun getSkill(id: String): SkillDef? = skills[id]

    /** Get the execution history. */
    fun getHistory(): List<Pair<SkillDef, Map<String, String>>> = executionHistory.toList()

    /** Get function calling tool definitions for LLM. */
    fun getToolDefinitionsForLlm(): String {
        return ElvaFunctions.buildToolsJsonArray().toString()
    }

    // ===== Built-in Skill Definitions =====

    private fun registerBuiltInSkills() {
        // Skill 1: Pay Electric Bill
        registerSkill(SkillDef(
            id = "pay_electric_bill",
            displayName = "交电费",
            description = "通过支付宝缴纳电费",
            requiredParams = listOf("account_number"),
            optionalParams = mapOf("city" to ""),
            riskLevel = RiskLevel.MEDIUM,
            triggerKeywords = listOf("交电费", "电费", "缴电费", "缴纳电费"),
            buildActions = { params ->
                val account = params["account_number"] ?: ""
                val actions = mutableListOf<NextAction>()
                actions.add(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = "支付宝",
                    voicePrompt = "正在打开支付宝生活缴费...",
                    explanation = "打开支付宝生活缴费",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.SPEAK_ONLY,
                    targetDescription = "",
                    voicePrompt = "请稍候，正在加载缴费页面...",
                    explanation = "等待加载",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "电费",
                    voicePrompt = "正在选择电费...",
                    explanation = "点击电费选项",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                if (account.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.TYPE_TEXT,
                        targetDescription = "户号", value = account,
                        voicePrompt = "正在输入户号...",
                        explanation = "输入电费户号",
                        riskLevel = RiskLevel.MEDIUM, source = "skill",
                    ))
                }
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "查询",
                    voicePrompt = "正在查询账单...",
                    explanation = "点击查询",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.ASK_CONFIRMATION,
                    targetDescription = "",
                    voicePrompt = "账单已查询到，请在手机上确认并完成支付",
                    explanation = "等待用户确认",
                    riskLevel = RiskLevel.HIGH, source = "skill",
                ))
                actions
            },
        ))

        // Skill 2: Pay Water Bill
        registerSkill(SkillDef(
            id = "pay_water_bill",
            displayName = "交水费",
            description = "通过支付宝缴纳水费",
            requiredParams = listOf("account_number"),
            optionalParams = mapOf("city" to ""),
            riskLevel = RiskLevel.MEDIUM,
            triggerKeywords = listOf("交水费", "水费", "缴水费", "缴纳水费"),
            buildActions = { params ->
                val account = params["account_number"] ?: ""
                val actions = mutableListOf<NextAction>()
                actions.add(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = "支付宝",
                    voicePrompt = "正在打开支付宝生活缴费...",
                    explanation = "打开支付宝",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "水费",
                    voicePrompt = "正在选择水费...",
                    explanation = "点击水费选项",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                if (account.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.TYPE_TEXT,
                        targetDescription = "户号", value = account,
                        voicePrompt = "正在输入水费户号...",
                        explanation = "输入水费户号",
                        riskLevel = RiskLevel.MEDIUM, source = "skill",
                    ))
                }
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "查询",
                    voicePrompt = "正在查询水费账单...",
                    explanation = "查询账单",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.ASK_CONFIRMATION,
                    targetDescription = "",
                    voicePrompt = "水费账单已查询到，请在手机上确认并完成支付",
                    explanation = "等待用户确认",
                    riskLevel = RiskLevel.HIGH, source = "skill",
                ))
                actions
            },
        ))

        // Skill 3: Book Hospital
        registerSkill(SkillDef(
            id = "book_hospital",
            displayName = "预约挂号",
            description = "预约医院挂号",
            requiredParams = listOf("hospital"),
            optionalParams = mapOf("department" to "", "date" to ""),
            riskLevel = RiskLevel.LOW,
            triggerKeywords = listOf("挂号", "预约挂号", "预约医院", "看病挂号"),
            buildActions = { params ->
                val hospital = params["hospital"] ?: ""
                val department = params["department"] ?: ""
                val actions = mutableListOf<NextAction>()
                actions.add(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = "支付宝",
                    voicePrompt = "正在打开挂号服务...",
                    explanation = "打开医疗挂号",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "医疗",
                    voicePrompt = "正在进入医疗服务...",
                    explanation = "点击医疗健康",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                if (hospital.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.TYPE_TEXT,
                        targetDescription = "搜索", value = hospital,
                        voicePrompt = "正在搜索 $hospital ...",
                        explanation = "搜索医院",
                        riskLevel = RiskLevel.LOW, source = "skill",
                    ))
                }
                if (department.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.CLICK_ELEMENT,
                        targetDescription = department,
                        voicePrompt = "正在选择 $department 科室...",
                        explanation = "选择科室",
                        riskLevel = RiskLevel.LOW, source = "skill",
                    ))
                }
                actions.add(NextAction(
                    action = ActionType.ASK_CONFIRMATION,
                    targetDescription = "",
                    voicePrompt = "请选择就诊日期和医生，然后在手机上完成预约",
                    explanation = "等待用户完成预约",
                    riskLevel = RiskLevel.MEDIUM, source = "skill",
                ))
                actions
            },
        ))

        // Skill 4: Open App (generic)
        registerSkill(SkillDef(
            id = "open_app",
            displayName = "打开应用",
            description = "打开指定的手机应用",
            requiredParams = listOf("app_name"),
            riskLevel = RiskLevel.ZERO,
            triggerKeywords = listOf("打开", "开启", "启动", "运行"),
            buildActions = { params ->
                val appName = params["app_name"] ?: ""
                listOf(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = appName,
                    voicePrompt = "正在打开 $appName ...",
                    explanation = "打开应用: $appName",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
            },
        ))

        // Skill 5: Go Home
        registerSkill(SkillDef(
            id = "go_home",
            displayName = "回到桌面",
            description = "按下Home键回到手机桌面",
            requiredParams = emptyList(),
            riskLevel = RiskLevel.ZERO,
            triggerKeywords = listOf("回到桌面", "返回桌面", "主屏幕", "home"),
            buildActions = { _ ->
                listOf(NextAction(
                    action = ActionType.NAVIGATE_HOME,
                    targetDescription = "Home",
                    voicePrompt = "正在回到桌面...",
                    explanation = "按下Home键",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
            },
        ))

        // Skill 6: Go Back
        registerSkill(SkillDef(
            id = "go_back",
            displayName = "返回上一页",
            description = "按下返回键回到上一页",
            requiredParams = emptyList(),
            riskLevel = RiskLevel.ZERO,
            triggerKeywords = listOf("返回", "返回上一页", "后退", "上一页"),
            buildActions = { _ ->
                listOf(NextAction(
                    action = ActionType.NAVIGATE_BACK,
                    targetDescription = "Back",
                    voicePrompt = "正在返回上一页...",
                    explanation = "按下返回键",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
            },
        ))

        // Skill 7: Fill Form (Case 1 — Always-on form filling assistant)
        registerSkill(SkillDef(
            id = "fill_form",
            displayName = "填写表单",
            description = "自动识别并填写固定模板表单，辅助老人完成报名、登记等操作",
            requiredParams = emptyList(),
            riskLevel = RiskLevel.LOW,
            triggerKeywords = listOf("填表", "填写", "报名", "注册", "表单", "登记"),
            buildActions = { _ ->
                val fillState = com.elva.laobai.sentinel.AlwaysOnSentinel.startFormFilling()
                if (fillState == null) {
                    listOf(NextAction(
                        action = ActionType.SPEAK_ONLY,
                        targetDescription = "form_no_match",
                        voicePrompt = "抱歉，我还不认识这个表单。您可以手动填写，或者告诉家人帮忙设置模板。",
                        explanation = "未匹配到已知表单模板",
                        riskLevel = RiskLevel.ZERO,
                        source = "skill",
                    ))
                } else {
                    val templateName = fillState.templateName ?: "表单"
                    val progress = "${fillState.filledFields}/${fillState.totalFields}"
                    listOf(NextAction(
                        action = ActionType.SPEAK_ONLY,
                        targetDescription = "form_fill_start",
                        voicePrompt = "好的，老白来帮您填写${templateName}。已填写${progress}项，您看着就行~",
                        explanation = "开始填写表单: $templateName",
                        riskLevel = RiskLevel.LOW,
                        source = "skill",
                    ))
                }
            },
        ))

        // Skill 8: Health Consultation (Case 2 — Trigger health consultation via on-device Gemma 4)
        registerSkill(SkillDef(
            id = "health_consultation",
            displayName = "看病咨询",
            description = "通过6阶段问询了解病情，使用本地 Gemma 4 推荐科室，并协助挂号",
            requiredParams = emptyList(),
            optionalParams = mapOf("symptom" to ""),
            riskLevel = RiskLevel.LOW,
            triggerKeywords = listOf(
                "不舒服", "疼", "痛", "难受", "头晕", "恶心",
                "发烧", "咳嗽", "胸闷", "看病", "挂号",
            ),
            buildActions = { _ ->
                listOf(NextAction(
                    action = ActionType.SPEAK_ONLY,
                    targetDescription = "health_start",
                    voicePrompt = "好的，老白来帮您看看。请告诉我您哪里不舒服？",
                    explanation = "启动6阶段健康问询状态机",
                    riskLevel = RiskLevel.ZERO,
                    source = "skill",
                ))
            },
        ))

        Log.d(TAG, "Registered ${skills.size} built-in skills")
    }
}
