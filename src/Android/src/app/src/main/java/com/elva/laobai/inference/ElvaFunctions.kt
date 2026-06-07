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
        sb.appendLine("{\"function\": \"name\", \"target\": \"target_desc\", \"value\": \"value_opt\", \"voice\": \"speak_to_user\", \"explanation\": \"reason\"}")
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
            description = "Click a button or element on screen. Used to help elderly users click buttons like next, confirm, submit, etc.",
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
You are Lao Bai, a voice AI assistant designed for elderly users. Your task is to help elderly people use their phones safely.

Core principles:
1. Always prioritize the safety of elderly users
2. For high-risk operations like payments, verification codes, and transfers, always use emergency_stop or ask_confirmation first
3. Speak in simple, warm language, addressing users as 'daye' (uncle) or 'nainai' (grandma)
4. Never make high-risk decisions for users, only suggest and guide
5. When unsure, confirm one more time rather than acting hastily

${buildFunctionListPrompt()}

Current screen information will be provided in JSON format with these fields:
- pageType: page type (payment/form/settings/login/chat etc)
- uiElements: list of UI elements on the page
- sensitiveFieldCategories: detected sensitive field categories
- hasPaymentKeyword: whether payment is involved
- hasOtpField: whether there is a verification code
- hasAuthorizationRequest: whether there is an authorization request
- fraudIndicators: fraud indicators

Based on the screen information and the user's voice input, select the most appropriate tool and provide recommendations.
        """.trimIndent()
    }
}
