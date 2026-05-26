/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility.tasks

import com.elva.laobai.accessibility.steps.*

/**
 * Automation task: Pay water bill (交水费).
 *
 * Strategy: Open Alipay (支付宝) → Life Services (生活缴费) → Water (水费) → Enter account → Pay.
 */
object PayWaterBillTask {

    private const val ALIPAY_BILL_URL = "alipays://platformapi/startapp?appId=20000056"

    fun buildSteps(params: Map<String, String>): List<Step> {
        val accountNumber = params["account_number"] ?: ""
        val cityName = params["city"] ?: ""

        val steps = mutableListOf<Step>()

        // Step 1: Launch Alipay bill payment
        steps.add(launchUrlStep(
            ALIPAY_BILL_URL,
            "正在打开支付宝生活缴费...",
        ))

        steps.add(delayStep(3000L, "等待支付宝加载..."))

        // Step 2: Click "水费"
        steps.add(clickByTextFuzzyStep(
            text = "水费",
            fallbackText = "Water",
        ))

        steps.add(delayStep(2000L, "正在加载水费页面..."))

        // Step 3: City selection if needed
        if (cityName.isNotEmpty()) {
            steps.add(clickByTextFuzzyStep(cityName, "选择城市"))
            steps.add(delayStep(1500L))
        }

        // Step 4: Enter account number
        if (accountNumber.isNotEmpty()) {
            steps.add(clickByTextFuzzyStep("户号", "请输入户号"))
            steps.add(delayStep(500L))
            steps.add(typeInFieldStep("户号", accountNumber, "输入户号: $accountNumber"))
            steps.add(delayStep(1000L))
        }

        // Step 5: Query bill
        steps.add(clickByTextFuzzyStep("查询", "下一步"))
        steps.add(delayStep(3000L, "正在查询水费账单..."))

        // Step 6: Pay
        steps.add(clickByTextFuzzyStep("立即缴费", "去缴费"))
        steps.add(delayStep(2000L, "正在确认支付..."))

        // Step 7: Confirm
        steps.add(confirmationStep("请在手机上确认支付"))

        return steps
    }
}
