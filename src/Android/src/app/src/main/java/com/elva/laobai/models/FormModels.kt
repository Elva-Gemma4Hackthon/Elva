/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.models

/**
 * Definition of a single form field to be filled.
 *
 * Maps a UI label (what the user sees on screen) to a memory key
 * (where the value is stored in LocalUserMemory).
 */
data class FormFieldDef(
    /** Label text on the screen (e.g., "姓名", "手机号"). */
    val uiLabel: String,

    /** Key in LocalUserMemory (e.g., "display_name", "phone_masked"). */
    val memoryKey: String,

    /** Type of the field: "text", "phone", "id_card", "select". */
    val fieldType: String = "text",

    /** Whether this field is required for the form template. */
    val required: Boolean = true,
)

/**
 * A fixed form template for the always-on form filling assistant (Case 1).
 *
 * V1 scope: only handles fixed templates, not arbitrary form generalization.
 * Each template defines which UI fields map to which local memory keys,
 * and which on-screen targets must never be auto-clicked.
 */
data class FormTemplate(
    /** Unique template identifier (e.g., "community_event_signup"). */
    val templateId: String,

    /** Human-readable display name (e.g., "社区活动报名表"). */
    val displayName: String,

    /** Keywords used to identify this form page type. */
    val pageKeywords: List<String>,

    /** Fields that need to be filled, in order. */
    val requiredFields: List<FormFieldDef>,

    /**
     * Mapping from UI label text to LocalUserMemory key.
     * Example: "姓名" -> "display_name", "手机号" -> "phone_masked"
     */
    val fieldMapping: Map<String, String> = emptyMap(),

    /**
     * On-screen targets that must NEVER be auto-clicked.
     * The system must stop before clicking any of these and require user confirmation.
     */
    val blockedTargets: List<String> = listOf(
        "提交", "支付", "付款", "授权", "删除",
        "发送验证码", "获取验证码", "输入验证码",
    ),

    /**
     * Targets that require explicit user confirmation before proceeding.
     * These are less dangerous than blockedTargets but still need user awareness.
     */
    val confirmationTargets: List<String> = listOf(
        "提交", "确认提交", "确认报名", "确认预约",
    ),
)

/**
 * Result of matching a screen observation against registered form templates.
 */
data class FormTemplateMatch(
    /** Whether a template was matched. */
    val matched: Boolean,

    /** The matched template, or null. */
    val template: FormTemplate? = null,

    /** Match confidence (0.0 - 1.0). */
    val confidence: Float = 0f,

    /** Fields from the template that were found on the current page. */
    val matchedFields: List<FormFieldDef> = emptyList(),

    /** Blocked targets that are present on the current page. */
    val blockedElementsPresent: List<String> = emptyList(),
)

/**
 * V1 built-in form templates.
 */
object FormTemplates {

    /** Template: Community event sign-up form. */
    val COMMUNITY_EVENT_SIGNUP = FormTemplate(
        templateId = "community_event_signup",
        displayName = "社区活动报名表",
        pageKeywords = listOf(
            "活动报名", "社区活动", "报名表", "参加活动",
            "姓名", "联系方式", "住址", "活动名称",
            "身份证", "手机号",
        ),
        requiredFields = listOf(
            FormFieldDef("姓名", "display_name", "text", required = true),
            FormFieldDef("手机号", "phone_masked", "phone", required = true),
            FormFieldDef("家庭住址", "address_label", "text", required = false),
        ),
        fieldMapping = mapOf(
            "姓名" to "display_name",
            "联系方式" to "phone_masked",
            "手机号" to "phone_masked",
            "联系电话" to "phone_masked",
            "地址" to "address_label",
            "家庭住址" to "address_label",
            "住址" to "address_label",
        ),
        blockedTargets = listOf(
            "提交", "确认报名", "支付", "付款",
            "立即报名", "获取验证码", "发送验证码",
        ),
        confirmationTargets = listOf("提交", "确认报名"),
    )

    /** Template: Pre-visit basic patient information form. */
    val PRE_VISIT_BASIC_INFO = FormTemplate(
        templateId = "pre_visit_basic_info",
        displayName = "就诊前基础信息表",
        pageKeywords = listOf(
            "就诊信息", "挂号信息", "患者信息", "就诊人",
            "身份证", "医保卡", "姓名", "性别", "出生日期",
        ),
        requiredFields = listOf(
            FormFieldDef("姓名", "display_name", "text", required = true),
            FormFieldDef("手机号", "phone_masked", "phone", required = true),
            FormFieldDef("医保卡号", "medical_card_label", "text", required = false),
            FormFieldDef("紧急联系人", "emergency_contact_label", "text", required = false),
        ),
        fieldMapping = mapOf(
            "姓名" to "display_name",
            "手机号" to "phone_masked",
            "联系方式" to "phone_masked",
            "紧急联系人" to "emergency_contact_label",
            "医保卡" to "medical_card_label",
            "社保卡" to "medical_card_label",
        ),
        blockedTargets = listOf(
            "确认提交", "提交信息", "预约", "确认预约",
            "支付", "付款", "获取验证码", "发送验证码",
            "删除患者信息",
        ),
        confirmationTargets = listOf("确认提交", "提交信息", "预约", "确认预约"),
    )

    /** All registered V1 templates. */
    val ALL_TEMPLATES: List<FormTemplate> = listOf(
        COMMUNITY_EVENT_SIGNUP,
        PRE_VISIT_BASIC_INFO,
    )
}
