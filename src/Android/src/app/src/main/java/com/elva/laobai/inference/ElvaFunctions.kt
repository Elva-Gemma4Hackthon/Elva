/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.inference

import com.elva.laobai.models.NextAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Function Calling definitions for Elva LaoBai.
 *
 * Defines the structured tool interface that the Gemma model uses
 * to generate actionable NextAction objects. Each function corresponds
 * to an ActionType in the Elva five-layer pipeline.
 *
 * These definitions follow the Function Calling schema format:
 * name, description, and JSON Schema parameters.
 */
object ElvaFunctions {

    /**
     * A single function definition with its JSON Schema.
     */
    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: JSONObject,
        /** Map of ActionType that this function produces. */
        val actionType: NextAction.ActionType,
        /** Default risk level for this function. */
        val defaultRiskLevel: NextAction.RiskLevel,
    )

    /**
     * All available functions for the Elva planner.
     * The model can only call functions from this list (whitelist).
     */
    val ALL_FUNCTIONS: List<FunctionDef> = listOf(
        defineClickElement(),
        defineTypeText(),
        defineScroll(),
        defineNavigateBack(),
        defineNavigateHome(),
        defineOpenApp(),
        defineHighlightElement(),
        defineSpeakOnly(),
        defineEmergencyStop(),
        defineAskConfirmation(),
        defineGenerateSummary(),
    )

    /**
     * Build the complete tools JSON array for the model prompt.
     * This is the tools[] block that gets appended to the system prompt.
     */
    fun buildToolsJsonArray(): JSONArray {
        val tools = JSONArray()
        for (fn in ALL_FUNCTIONS) {
            val tool = JSONObject().apply {
                put("name", fn.name)
                put("description", fn.description)
                put("parameters", fn.parameters)
            }
            tools.put(tool)
        }
        return tools
    }

    /**
     * Build a condensed text summary of available functions for the prompt.
     * Used when the model doesn't support native Function Calling but
     * we still want structured output.
     */
    fun buildFunctionListPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("你可以使用以下工具来帮助老人：")
        sb.appendLine()
        for (fn in ALL_FUNCTIONS) {
            sb.appendLine("- ${fn.name}: ${fn.description}")
        }
        sb.appendLine()
        sb.appendLine("请以JSON格式输出你的建议，格式如下：")
        sb.appendLine("""{"function": "函数名", "target": "目标描述", "value": "输入值(可选)", "voice": "对老人说的话", "explanation": "为什么这样做"}")
        return sb.toString()
    }

    /**
     * Map a function name back to its FunctionDef.
     */
    fun getByName(name: String): FunctionDef? {
        return ALL_FUNCTIONS.find { it.name == name }
    }

    // ===== Function Definitions =====

    private fun defineClickElement(): FunctionDef {
        return FunctionDef(
            name = "click_element",
            description = "点击屏幕上的一个按钮或元素。用于帮老人点击"下一步"、"确认"、"提交"等按钮。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "要点击的元素文本，例如'下一步'、'确认支付'")
                    })
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话，例如'帮您点击下一步'")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么建议点击这个元素")
                    })
                })
                put("required", JSONArray().put("target").put("voice"))
            },
            actionType = NextAction.ActionType.CLICK_ELEMENT,
            defaultRiskLevel = NextAction.RiskLevel.MEDIUM,
        )
    }

    private fun defineTypeText(): FunctionDef {
        return FunctionDef(
            name = "type_text",
            description = "在输入框中输入文字。用于帮老人填写表单字段，如姓名、地址等。注意：绝不输入密码、验证码等敏感信息。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "输入框的提示文字或标签，例如'手机号'、'收货地址'")
                    })
                    put("value", JSONObject().apply {
                        put("type", "string")
                        put("description", "要输入的文字内容")
                    })
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么建议输入这个内容")
                    })
                })
                put("required", JSONArray().put("target").put("value").put("voice"))
            },
            actionType = NextAction.ActionType.TYPE_TEXT,
            defaultRiskLevel = NextAction.RiskLevel.MEDIUM,
        )
    }

    private fun defineScroll(): FunctionDef {
        return FunctionDef(
            name = "scroll",
            description = "滚动页面。用于帮老人浏览列表或查找内容。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "滚动方向：'up'向上, 'down'向下")
                    })
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么建议滚动")
                    })
                })
                put("required", JSONArray().put("target").put("voice"))
            },
            actionType = NextAction.ActionType.SCROLL,
            defaultRiskLevel = NextAction.RiskLevel.ZERO,
        )
    }

    private fun defineNavigateBack(): FunctionDef {
        return FunctionDef(
            name = "navigate_back",
            description = "返回上一页。用于帮老人退出当前页面或取消操作。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话，例如'帮您返回上一页'")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么建议返回")
                    })
                })
                put("required", JSONArray().put("voice"))
            },
            actionType = NextAction.ActionType.NAVIGATE_BACK,
            defaultRiskLevel = NextAction.RiskLevel.ZERO,
        )
    }

    private fun defineNavigateHome(): FunctionDef {
        return FunctionDef(
            name = "navigate_home",
            description = "回到手机桌面。用于帮老人退出应用回到主屏幕。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么建议回到桌面")
                    })
                })
                put("required", JSONArray().put("voice"))
            },
            actionType = NextAction.ActionType.NAVIGATE_HOME,
            defaultRiskLevel = NextAction.RiskLevel.ZERO,
        )
    }

    private fun defineOpenApp(): FunctionDef {
        return FunctionDef(
            name = "open_app",
            description = "打开一个应用。用于帮老人打开微信、支付宝、相机等应用。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "应用名称，例如'微信'、'支付宝'、'相机'")
                    })
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么建议打开这个应用")
                    })
                })
                put("required", JSONArray().put("target").put("voice"))
            },
            actionType = NextAction.ActionType.OPEN_APP,
            defaultRiskLevel = NextAction.RiskLevel.ZERO,
        )
    }

    private fun defineHighlightElement(): FunctionDef {
        return FunctionDef(
            name = "highlight_element",
            description = "高亮显示屏幕上的一个元素，引导老人看到它。不执行任何操作，只是视觉引导。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "要高亮的元素文本")
                    })
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话，例如'您看这里，点这个按钮'")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么要引导老人看这个元素")
                    })
                })
                put("required", JSONArray().put("target").put("voice"))
            },
            actionType = NextAction.ActionType.HIGHLIGHT_ELEMENT,
            defaultRiskLevel = NextAction.RiskLevel.ZERO,
        )
    }

    private fun defineSpeakOnly(): FunctionDef {
        return FunctionDef(
            name = "speak",
            description = "只说话，不执行任何操作。用于回答老人问题、解释页面内容、提供建议。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话（必需）")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么给出这个回答")
                    })
                })
                put("required", JSONArray().put("voice"))
            },
            actionType = NextAction.ActionType.SPEAK_ONLY,
            defaultRiskLevel = NextAction.RiskLevel.ZERO,
        )
    }

    private fun defineEmergencyStop(): FunctionDef {
        return FunctionDef(
            name = "emergency_stop",
            description = "紧急停止！当检测到诈骗、高风险操作时立即阻止。用于验证码+付款、转账等危险场景。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "紧急警告语，例如'大爷别点！这是诈骗！'")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "检测到的风险类型")
                    })
                })
                put("required", JSONArray().put("voice"))
            },
            actionType = NextAction.ActionType.EMERGENCY_STOP,
            defaultRiskLevel = NextAction.RiskLevel.HIGH,
        )
    }

    private fun defineAskConfirmation(): FunctionDef {
        return FunctionDef(
            name = "ask_confirmation",
            description = "要求老人确认操作。用于提交、发送、授权等需要二次确认的场景。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("description", "需要确认的操作描述")
                    })
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话，例如'您确认要提交吗？'")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么需要确认")
                    })
                })
                put("required", JSONArray().put("target").put("voice"))
            },
            actionType = NextAction.ActionType.ASK_CONFIRMATION,
            defaultRiskLevel = NextAction.RiskLevel.MEDIUM,
        )
    }

    private fun defineGenerateSummary(): FunctionDef {
        return FunctionDef(
            name = "generate_summary",
            description = "生成脱敏摘要卡片。用于家人协助模式，将当前页面情况安全地发给家人。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("voice", JSONObject().apply {
                        put("type", "string")
                        put("description", "对老人说的话，例如'老白帮您做了一张求助卡片'")
                    })
                    put("explanation", JSONObject().apply {
                        put("type", "string")
                        put("description", "为什么要生成求助卡片")
                    })
                })
                put("required", JSONArray().put("voice"))
            },
            actionType = NextAction.ActionType.GENERATE_SUMMARY,
            defaultRiskLevel = NextAction.RiskLevel.LOW,
        )
    }

    /**
     * System prompt fragment that instructs the model how to use these functions.
     */
    fun buildSystemPromptFragment(): String {
        return """
你是「老白」，一个专为老年人设计的语音AI助手。你的任务是帮助老人安全地使用手机。

核心原则：
1. 永远把老人安全放在第一位
2. 遇到付款、验证码、转账等高风险操作时，必须先用 emergency_stop 或 ask_confirmation
3. 用简单亲切的语言和老人说话，称呼他们"大爷"或"奶奶"
4. 不要替老人做高风险决定，只能建议和引导
5. 如果不确定，宁可多说一句确认，也不要贸然操作

${buildFunctionListPrompt()}

当前屏幕信息会以JSON格式提供给你，包含以下字段：
- pageType: 页面类型（payment/form/settings/login/chat等）
- uiElements: 页面上的UI元素列表
- sensitiveFieldCategories: 检测到的敏感字段类别
- hasPaymentKeyword: 是否涉及付款
- hasOtpField: 是否有验证码
- hasAuthorizationRequest: 是否有授权请求
- fraudIndicators: 诈骗指标

请根据屏幕信息和老人的语音输入，选择最合适的工具并给出建议。
        """.trimIndent()
    }
}
