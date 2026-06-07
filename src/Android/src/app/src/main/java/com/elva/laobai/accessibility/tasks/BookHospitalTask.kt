/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility.tasks

import com.elva.laobai.accessibility.steps.*

/**
 * Automation task: Book hospital appointment (挂号).
 *
 * Strategy: Open WeChat (微信) → Search hospital mini-program → Select department → Book.
 *
 * Most Chinese hospitals use WeChat mini-programs for appointment booking.
 * This task navigates through WeChat to find and open the hospital's booking page.
 *
 * Fallback: Opens hospital's web appointment page directly in browser.
 */
object BookHospitalTask {

    // WeChat package name
    private const val WECHAT = "com.tencent.mm"

    fun buildSteps(params: Map<String, String>): List<Step> {
        val hospitalName = params["hospital"] ?: ""
        val department = params["department"] ?: ""
        val date = params["date"] ?: ""

        val steps = mutableListOf<Step>()

        if (hospitalName.isNotEmpty()) {
            // Strategy A: Open WeChat → Search hospital mini-program
            steps.add(launchAppStep(WECHAT, "正在打开微信..."))
            steps.add(delayStep(3000L, "等待微信加载..."))

            // Step: Click search (放大镜 icon at top)
            steps.add(clickByTextFuzzyStep("搜索", "Search"))
            steps.add(delayStep(1000L))

            // Step: Type hospital name in search
            steps.add(typeInFieldStep("搜索", hospitalName, "搜索医院: $hospitalName"))
            steps.add(delayStep(2000L, "正在搜索..."))

            // Step: Click the hospital in search results
            steps.add(clickByTextFuzzyStep(hospitalName))
            steps.add(delayStep(3000L, "正在打开医院页面..."))

            // Step: Look for "预约挂号" or "挂号" button
            steps.add(clickByTextFuzzyStep("预约挂号", "挂号"))
            steps.add(delayStep(3000L, "正在加载挂号页面..."))

            // Step: Select department if specified
            if (department.isNotEmpty()) {
                steps.add(clickByTextFuzzyStep(department))
                steps.add(delayStep(2000L, "正在选择科室..."))
            }

            // Step: Select date if specified
            if (date.isNotEmpty()) {
                steps.add(clickByTextFuzzyStep(date))
                steps.add(delayStep(1500L))
            }

            // Step: Confirm booking — stop before final click, let user confirm
            // (Do NOT auto-click "预约/确认挂号" — user must verify the details)
            steps.add(confirmationStep("请仔细核对挂号信息，确认无误后在手机上点击确认挂号"))
            steps.add(delayStep(2000L, "等待用户确认..."))
        } else {
            // Strategy B: No hospital specified — guide the user
            steps.add(launchAppStep(WECHAT, "正在打开微信..."))
            steps.add(delayStep(3000L))
            steps.add(clickByTextFuzzyStep("搜索", "Search"))
            steps.add(delayStep(1000L))
            steps.add(typeInFieldStep("搜索", "挂号", "搜索挂号小程序"))
            steps.add(delayStep(2000L))
            steps.add(clickByTextFuzzyStep("挂号"))
            steps.add(confirmationStep("请在手机上选择医院和科室"))
        }

        return steps
    }
}
