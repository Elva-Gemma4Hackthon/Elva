/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility.tasks

import com.elva.laobai.accessibility.steps.*

/**
 * Automation task: Pay electric bill (交电费).
 *
 * Strategy: Open Alipay (支付宝) → Life Services (生活缴费) → Electricity (电费) → Enter account → Pay.
 *
 * Alipay is the most common utility payment app in China.
 * The task navigates through the UI using accessibility clicks.
 *
 * Fallback: If Alipay is not installed, opens the browser to
 * the State Grid (国家电网) online payment page.
 */
object PayElectricBillTask {

    // Alipay package name
    private const val ALIPAY = "com.eg.android.AlipayGphone"
    // Alipay life bill payment deep link
    private const val ALIPAY_BILL_URL = "alipays://platformapi/startapp?appId=20000056"

    fun buildSteps(params: Map<String, String>): List<Step> {
        val accountNumber = params["account_number"] ?: ""
        val cityName = params["city"] ?: ""

        val steps = mutableListOf<Step>()

        // Step 1: Launch Alipay
        steps.add(launchUrlStep(
            ALIPAY_BILL_URL,
            "正在打开支付宝生活缴费...",
        ))

        // Step 2: Wait for Alipay to load
        steps.add(delayStep(3000L, "等待支付宝加载..."))

        // Step 3: Click "电费" in the bill payment page
        steps.add(clickByTextFuzzyStep(
            text = "电费",
            fallbackText = "Electricity",
        ))

        steps.add(delayStep(2000L, "正在加载电费页面..."))

        // Step 4: If city selection is needed, select city
        if (cityName.isNotEmpty()) {
            steps.add(clickByTextFuzzyStep(cityName, "选择城市"))
            steps.add(delayStep(1500L))
        }

        // Step 5: Enter account number if provided
        if (accountNumber.isNotEmpty()) {
            steps.add(clickByTextFuzzyStep("户号", "请输入户号"))
            steps.add(delayStep(500L))
            steps.add(typeInFieldStep("户号", accountNumber, "输入户号: $accountNumber"))
            steps.add(delayStep(1000L))
        }

        // Step 6: Click query/next button
        steps.add(clickByTextFuzzyStep("查询", "下一步"))
        steps.add(delayStep(3000L, "正在查询账单..."))

        // Step 7: Click pay button
        steps.add(clickByTextFuzzyStep("立即缴费", "去缴费"))
        steps.add(delayStep(2000L, "正在确认支付..."))

        // Step 8: Confirm payment (user needs to authenticate manually)
        steps.add(confirmationStep("请在手机上确认支付"))

        return steps
    }
}
