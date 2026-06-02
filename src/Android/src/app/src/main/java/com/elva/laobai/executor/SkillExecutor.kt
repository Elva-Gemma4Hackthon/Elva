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
    private val executionHistory = mutableListOf<Pair<SkillDef, Map<String, String>>>>()
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
                                voicePrompt = "姝ｅ湪鎵ц $name...",
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
                Regex("(?i)${Regex.escape(param)}[锛?\\s]+(\\S+)"),
                Regex("(?i)${Regex.escape(param)}\\s*鏄?\\s*(\\S+)"),
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
                message = "鏈壘鍒版妧鑳? $skillId",
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
                message = "鎶€鑳?$skillId 娌℃湁鍙墽琛岀殑鍔ㄤ綔",
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
                    message = "鍔ㄤ綔琚畨鍏ㄧ瓥鐣ラ樆姝? ${validation.reason}",
                    failedAction = action,
                ))
                return
            }

            // Step 2: Check with SafetyGuard
            val guardResult = SafetyGuard.evaluate(action)
            if (guardResult.decision == com.elva.laobai.models.GuardDecision.GuardResult.DENY) {
                Log.w(TAG, "Skill action denied by SafetyGuard: ${guardResult.reason}")
                onComplete(SkillResult(
                    skillId = skillId,
                    success = false,
                    completedActions = completedCount,
                    totalActions = totalActions,
                    message = "瀹夊叏瀹堝崼鎷掔粷: ${guardResult.reason}",
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
                        message = "鍔ㄤ綔鎵ц澶辫触: ${result.message}",
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
                        message = "鎶€鑳?${skill.displayName} 鎵ц瀹屾垚",
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
                message = "鏈尮閰嶅埌浠讳綍鎶€鑳?,
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
        return ElvaFunctions.buildToolsJsonArray()
    }

    // ===== Built-in Skill Definitions =====

    private fun registerBuiltInSkills() {
        // Skill 1: Pay Electric Bill
        registerSkill(SkillDef(
            id = "pay_electric_bill",
            displayName = "浜ょ數璐?,
            description = "閫氳繃鏀粯瀹濈即绾崇數璐?,
            requiredParams = listOf("account_number"),
            optionalParams = mapOf("city" to ""),
            riskLevel = RiskLevel.MEDIUM,
            triggerKeywords = listOf("浜ょ數璐?, "鐢佃垂", "缂寸數璐?, "缂寸撼鐢佃垂"),
            buildActions = { params ->
                val account = params["account_number"] ?: ""
                val actions = mutableListOf<NextAction>()
                actions.add(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = "鏀粯瀹?,
                    voicePrompt = "姝ｅ湪鎵撳紑鏀粯瀹濈敓娲荤即璐?..",
                    explanation = "鎵撳紑鏀粯瀹濈敓娲荤即璐?,
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.SPEAK_ONLY,
                    targetDescription = "",
                    voicePrompt = "璇风◢鍊欙紝姝ｅ湪鍔犺浇缂磋垂椤甸潰...",
                    explanation = "绛夊緟鍔犺浇",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "鐢佃垂",
                    voicePrompt = "姝ｅ湪閫夋嫨鐢佃垂...",
                    explanation = "鐐瑰嚮鐢佃垂閫夐」",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                if (account.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.TYPE_TEXT,
                        targetDescription = "鎴峰彿", value = account,
                        voicePrompt = "姝ｅ湪杈撳叆鎴峰彿...",
                        explanation = "杈撳叆鐢佃垂鎴峰彿",
                        riskLevel = RiskLevel.MEDIUM, source = "skill",
                    ))
                }
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "鏌ヨ",
                    voicePrompt = "姝ｅ湪鏌ヨ璐﹀崟...",
                    explanation = "鐐瑰嚮鏌ヨ",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.ASK_CONFIRMATION,
                    targetDescription = "",
                    voicePrompt = "璐﹀崟宸叉煡璇㈠埌锛岃鍦ㄦ墜鏈轰笂纭骞跺畬鎴愭敮浠?,
                    explanation = "绛夊緟鐢ㄦ埛纭",
                    riskLevel = RiskLevel.HIGH, source = "skill",
                ))
                actions
            },
        ))

        // Skill 2: Pay Water Bill
        registerSkill(SkillDef(
            id = "pay_water_bill",
            displayName = "浜ゆ按璐?,
            description = "閫氳繃鏀粯瀹濈即绾虫按璐?,
            requiredParams = listOf("account_number"),
            optionalParams = mapOf("city" to ""),
            riskLevel = RiskLevel.MEDIUM,
            triggerKeywords = listOf("浜ゆ按璐?, "姘磋垂", "缂存按璐?, "缂寸撼姘磋垂"),
            buildActions = { params ->
                val account = params["account_number"] ?: ""
                val actions = mutableListOf<NextAction>()
                actions.add(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = "鏀粯瀹?,
                    voicePrompt = "姝ｅ湪鎵撳紑鏀粯瀹濈敓娲荤即璐?..",
                    explanation = "鎵撳紑鏀粯瀹?,
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "姘磋垂",
                    voicePrompt = "姝ｅ湪閫夋嫨姘磋垂...",
                    explanation = "鐐瑰嚮姘磋垂閫夐」",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                if (account.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.TYPE_TEXT,
                        targetDescription = "鎴峰彿", value = account,
                        voicePrompt = "姝ｅ湪杈撳叆姘磋垂鎴峰彿...",
                        explanation = "杈撳叆姘磋垂鎴峰彿",
                        riskLevel = RiskLevel.MEDIUM, source = "skill",
                    ))
                }
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "鏌ヨ",
                    voicePrompt = "姝ｅ湪鏌ヨ姘磋垂璐﹀崟...",
                    explanation = "鏌ヨ璐﹀崟",
                    riskLevel = RiskLevel.LOW, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.ASK_CONFIRMATION,
                    targetDescription = "",
                    voicePrompt = "姘磋垂璐﹀崟宸叉煡璇㈠埌锛岃鍦ㄦ墜鏈轰笂纭骞跺畬鎴愭敮浠?,
                    explanation = "绛夊緟鐢ㄦ埛纭",
                    riskLevel = RiskLevel.HIGH, source = "skill",
                ))
                actions
            },
        ))

        // Skill 3: Book Hospital
        registerSkill(SkillDef(
            id = "book_hospital",
            displayName = "棰勭害鎸傚彿",
            description = "棰勭害鍖婚櫌鎸傚彿",
            requiredParams = listOf("hospital"),
            optionalParams = mapOf("department" to "", "date" to ""),
            riskLevel = RiskLevel.LOW,
            triggerKeywords = listOf("鎸傚彿", "棰勭害鎸傚彿", "棰勭害鍖婚櫌", "鐪嬬梾鎸傚彿"),
            buildActions = { params ->
                val hospital = params["hospital"] ?: ""
                val department = params["department"] ?: ""
                val actions = mutableListOf<NextAction>()
                actions.add(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = "鏀粯瀹?,
                    voicePrompt = "姝ｅ湪鎵撳紑鎸傚彿鏈嶅姟...",
                    explanation = "鎵撳紑鍖荤枟鎸傚彿",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                actions.add(NextAction(
                    action = ActionType.CLICK_ELEMENT,
                    targetDescription = "鍖荤枟",
                    voicePrompt = "姝ｅ湪杩涘叆鍖荤枟鏈嶅姟...",
                    explanation = "鐐瑰嚮鍖荤枟鍋ュ悍",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
                if (hospital.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.TYPE_TEXT,
                        targetDescription = "鎼滅储", value = hospital,
                        voicePrompt = "姝ｅ湪鎼滅储 $hospital ...",
                        explanation = "鎼滅储鍖婚櫌",
                        riskLevel = RiskLevel.LOW, source = "skill",
                    ))
                }
                if (department.isNotEmpty()) {
                    actions.add(NextAction(
                        action = ActionType.CLICK_ELEMENT,
                        targetDescription = department,
                        voicePrompt = "姝ｅ湪閫夋嫨 $department 绉戝...",
                        explanation = "閫夋嫨绉戝",
                        riskLevel = RiskLevel.LOW, source = "skill",
                    ))
                }
                actions.add(NextAction(
                    action = ActionType.ASK_CONFIRMATION,
                    targetDescription = "",
                    voicePrompt = "璇烽€夋嫨灏辫瘖鏃ユ湡鍜屽尰鐢燂紝鐒跺悗鍦ㄦ墜鏈轰笂瀹屾垚棰勭害",
                    explanation = "绛夊緟鐢ㄦ埛瀹屾垚棰勭害",
                    riskLevel = RiskLevel.MEDIUM, source = "skill",
                ))
                actions
            },
        ))

        // Skill 4: Open App (generic)
        registerSkill(SkillDef(
            id = "open_app",
            displayName = "鎵撳紑搴旂敤",
            description = "鎵撳紑鎸囧畾鐨勬墜鏈哄簲鐢?,
            requiredParams = listOf("app_name"),
            riskLevel = RiskLevel.ZERO,
            triggerKeywords = listOf("鎵撳紑", "寮€鍚?, "鍚姩", "杩愯"),
            buildActions = { params ->
                val appName = params["app_name"] ?: ""
                listOf(NextAction(
                    action = ActionType.OPEN_APP,
                    targetDescription = appName,
                    voicePrompt = "姝ｅ湪鎵撳紑 $appName ...",
                    explanation = "鎵撳紑搴旂敤: $appName",
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
            },
        ))

        // Skill 5: Go Home
        registerSkill(SkillDef(
            id = "go_home",
            displayName = "鍥炲埌妗岄潰",
            description = "鎸変笅Home閿洖鍒版墜鏈烘闈?,
            requiredParams = emptyList(),
            riskLevel = RiskLevel.ZERO,
            triggerKeywords = listOf("鍥炲埌妗岄潰", "杩斿洖妗岄潰", "涓诲睆骞?, "home"),
            buildActions = { _ ->
                listOf(NextAction(
                    action = ActionType.NAVIGATE_HOME,
                    targetDescription = "Home",
                    voicePrompt = "姝ｅ湪鍥炲埌妗岄潰...",
                    explanation = "鎸変笅Home閿?,
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
            },
        ))

        // Skill 6: Go Back
        registerSkill(SkillDef(
            id = "go_back",
            displayName = "杩斿洖涓婁竴椤?,
            description = "鎸変笅杩斿洖閿洖鍒颁笂涓€椤?,
            requiredParams = emptyList(),
            riskLevel = RiskLevel.ZERO,
            triggerKeywords = listOf("杩斿洖", "杩斿洖涓婁竴椤?, "鍚庨€€", "涓婁竴椤?),
            buildActions = { _ ->
                listOf(NextAction(
                    action = ActionType.NAVIGATE_BACK,
                    targetDescription = "Back",
                    voicePrompt = "姝ｅ湪杩斿洖涓婁竴椤?..",
                    explanation = "鎸変笅杩斿洖閿?,
                    riskLevel = RiskLevel.ZERO, source = "skill",
                ))
            },
        ))

        Log.d(TAG, "Registered ${skills.size} built-in skills")
    }
}
