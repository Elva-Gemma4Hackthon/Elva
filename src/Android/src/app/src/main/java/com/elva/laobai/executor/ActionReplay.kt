/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.executor

import android.util.Log
import com.elva.laobai.guard.SafetyGuard
import com.elva.laobai.models.NextAction
import com.elva.laobai.models.NextAction.ActionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Action Replay — records and replays action sequences.
 *
 * Records executed actions so users can ask Elva to "再做一次".
 * Before replay, every action still passes through SafetyGuard.
 *
 * From PPT Slide 5: "动作重放"
 */
object ActionReplay {
    private const val TAG = "ActionReplay"
    private const val MAX_RECORDED_ACTIONS = 100

    /**
     * A recorded action sequence.
     */
    data class RecordedSequence(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val actions: List<NextAction>,
        val timestamp: Long = System.currentTimeMillis(),
        val replayCount: Int = 0,
    )

    /**
     * A single recorded action step.
     */
    data class RecordedAction(
        val action: NextAction,
        val timestamp: Long = System.currentTimeMillis(),
    )

    // In-memory recording buffer
    private val currentRecording = mutableListOf<RecordedAction>()
    private var isRecording = false

    // Saved sequences
    private val savedSequences = mutableListOf<RecordedSequence>()

    /**
     * Start recording a new action sequence.
     */
    fun startRecording(name: String = "未命名操作") {
        currentRecording.clear()
        isRecording = true
        Log.d(TAG, "Started recording: $name")
    }

    /**
     * Stop recording and save the sequence.
     */
    fun stopRecording(name: String? = null): RecordedSequence? {
        if (!isRecording || currentRecording.isEmpty()) {
            isRecording = false
            return null
        }

        isRecording = false
        val sequence = RecordedSequence(
            name = name ?: "操作${savedSequences.size + 1}",
            actions = currentRecording.map { it.action },
        )

        savedSequences.add(sequence)

        // Keep only last MAX_RECORDED_ACTIONS sequences
        while (savedSequences.size > MAX_RECORDED_ACTIONS) {
            savedSequences.removeAt(0)
        }

        Log.d(TAG, "Recording stopped: ${sequence.name}, ${sequence.actions.size} actions")
        return sequence
    }

    /**
     * Record a single action during execution.
     */
    fun record(action: NextAction) {
        if (!isRecording) return
        currentRecording.add(RecordedAction(action))
        Log.d(TAG, "Recorded action: ${action.action} -> ${action.targetDescription}")
    }

    /**
     * Get all saved sequences.
     */
    fun getSavedSequences(): List<RecordedSequence> {
        return savedSequences.toList()
    }

    /**
     * Replay a saved sequence.
     * Each action is validated through SafetyGuard before execution.
     *
     * @param sequenceId The ID of the sequence to replay.
     * @param context Android context.
     * @param onProgress Called for each action with progress info.
     * @param onComplete Called when replay is done.
     */
    fun replay(
        sequenceId: String,
        context: android.content.Context,
        onProgress: (Int, Int, NextAction) -> Unit,
        onComplete: (Boolean, String) -> Unit,
    ) {
        val sequence = savedSequences.find { it.id == sequenceId }
        if (sequence == null) {
            onComplete(false, "找不到操作记录")
            return
        }

        val actions = sequence.actions
        val total = actions.size
        val results = mutableListOf<ActionExecutor.ExecutionResult>()

        CoroutineScope(Dispatchers.Main).launch {
            for ((index, action) in actions.withIndex()) {
                // Safety check before each replay action
                val observation = com.elva.laobai.observer.ScreenObserver.observe()
                val guardDecision = SafetyGuard.evaluate(action, observation)

                if (guardDecision.decision == com.elva.laobai.models.GuardDecision.GuardResult.DENY) {
                    onComplete(false, "重放中止: 第${index + 1}步被安全守卫拦截 - ${guardDecision.reason}")
                    return@launch
                }

                onProgress(index + 1, total, action)

                // Execute via ActionExecutor
                var executionDone = false
                ActionExecutor.execute(action, context) { result ->
                    results.add(result)
                    executionDone = true
                }

                // Wait for execution to complete
                while (!executionDone) {
                    kotlinx.coroutines.delay(200)
                }

                // Check last result
                val lastResult = results.last()
                if (!lastResult.success) {
                    onComplete(false, "重放失败于第${index + 1}步: ${lastResult.message}")
                    return@launch
                }

                kotlinx.coroutines.delay(1500) // Wait between actions
            }

            // Update replay count
            val updatedSequence = sequence.copy(replayCount = sequence.replayCount + 1)
            val idx = savedSequences.indexOfFirst { it.id == sequenceId }
            if (idx >= 0) savedSequences[idx] = updatedSequence

            onComplete(true, "操作重放完成，共${total}步")
        }
    }

    /**
     * Delete a saved sequence.
     */
    fun deleteSequence(sequenceId: String) {
        savedSequences.removeAll { it.id == sequenceId }
    }

    /**
     * Clear all saved sequences.
     */
    fun clearAll() {
        savedSequences.clear()
        currentRecording.clear()
        isRecording = false
    }
}
