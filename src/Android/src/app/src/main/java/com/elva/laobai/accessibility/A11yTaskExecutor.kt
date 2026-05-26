/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.elva.laobai.accessibility.steps.Step
import com.elva.laobai.accessibility.steps.StepResult
import com.elva.laobai.accessibility.tasks.PayElectricBillTask
import com.elva.laobai.accessibility.tasks.BookHospitalTask
import com.elva.laobai.accessibility.tasks.PayWaterBillTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates accessibility automation tasks.
 * Executes a sequence of Steps using ElvaAccessibilityService.
 */
object A11yTaskExecutor {
    private const val TAG = "ElvaA11yExec"

    /** Delay between steps to allow UI to update (ms). */
    private const val STEP_DELAY_MS = 1500L

    data class ExecutionState(
        val taskName: String = "",
        val currentStep: Int = 0,
        val totalSteps: Int = 0,
        val stepDescription: String = "",
        val isRunning: Boolean = false,
        val isComplete: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(ExecutionState())
    val state = _state.asStateFlow()

    private var isCancelled = false

    /** Available task types. */
    enum class TaskType(val key: String) {
        PAY_ELECTRIC_BILL("pay_electric_bill"),
        PAY_WATER_BILL("pay_water_bill"),
        BOOK_HOSPITAL("book_hospital"),
    }

    /**
     * Execute an automation task.
     * @param taskType The type of task to run.
     * @param params Task-specific parameters (e.g., account number, hospital name).
     * @param context Android context for launching apps.
     * @param onComplete Callback with final result.
     */
    fun execute(
        taskType: TaskType,
        params: Map<String, String>,
        context: Context,
        onComplete: (Boolean, String) -> Unit,
    ) {
        if (_state.value.isRunning) {
            Log.w(TAG, "Another task is already running")
            return
        }

        val service = ElvaAccessibilityService.instance
        if (service == null) {
            onComplete(false, "无障碍服务未开启，请在设置中开启 Elva 的无障碍权限")
            return
        }

        val steps = when (taskType) {
            TaskType.PAY_ELECTRIC_BILL -> PayElectricBillTask.buildSteps(params)
            TaskType.PAY_WATER_BILL -> PayWaterBillTask.buildSteps(params)
            TaskType.BOOK_HOSPITAL -> BookHospitalTask.buildSteps(params)
        }

        isCancelled = false
        _state.value = ExecutionState(
            taskName = taskType.key,
            totalSteps = steps.size,
            isRunning = true,
        )

        CoroutineScope(Dispatchers.Main).launch {
            try {
                for ((index, step) in steps.withIndex()) {
                    if (isCancelled) {
                        _state.value = _state.value.copy(
                            isRunning = false, error = "任务已取消"
                        )
                        onComplete(false, "任务已取消")
                        return@launch
                    }

                    _state.value = _state.value.copy(
                        currentStep = index + 1,
                        stepDescription = step.description,
                    )
                    Log.d(TAG, "Step ${index + 1}/${steps.size}: ${step.description}")

                    val result = step.execute(service, context)
                    if (!result.success) {
                        _state.value = _state.value.copy(
                            isRunning = false, isComplete = true,
                            error = result.message,
                        )
                        onComplete(false, result.message)
                        return@launch
                    }

                    // Wait for UI to update
                    delay(step.delayMs ?: STEP_DELAY_MS)
                }

                _state.value = _state.value.copy(
                    isRunning = false, isComplete = true,
                )
                onComplete(true, "任务完成")
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed", e)
                _state.value = _state.value.copy(
                    isRunning = false, isComplete = true,
                    error = e.message,
                )
                onComplete(false, e.message ?: "执行失败")
            }
        }
    }

    /** Cancel the currently running task. */
    fun cancel() {
        isCancelled = true
        ElvaAccessibilityService.instance?.pressHome()
    }

    /** Reset state for a new task. */
    fun reset() {
        _state.value = ExecutionState()
        isCancelled = false
    }
}
