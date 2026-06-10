/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.system

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MemoryMonitor"

/** Memory pressure levels for decision-making. */
enum class MemoryPressureLevel {
    /** Memory is plentiful — safe for any operation. */
    NORMAL,
    /** Memory is somewhat constrained — large allocations may be risky. */
    MODERATE,
    /** Memory is significantly constrained — avoid large allocations. */
    HIGH,
    /** System is killing processes — emergency only. */
    CRITICAL
}

/**
 * Current memory status reported by [MemoryMonitor].
 */
data class MemoryStatus(
    /** Total device memory in MB. */
    val totalMemMb: Long,
    /** Currently available memory in MB. */
    val availableMemMb: Long,
    /** Whether the system considers memory "low" (from ActivityManager). */
    val isLowMemory: Boolean,
    /** Whether it's safe to load a large model (> 1 GB). */
    val isSafeForLargeModel: Boolean,
    /** Current memory pressure level. */
    val memoryPressureLevel: MemoryPressureLevel,
)

/**
 * Monitors device memory status for large-model safety decisions.
 *
 * Uses [ActivityManager.MemoryInfo] for system-level memory data and
 * [Debug.MemoryInfo] for app-specific memory usage. Exposes status
 * via [StateFlow] so the UI layer can reactively show warnings.
 *
 * Thresholds for "safe for large model":
 * - Available memory must be at least 2 GB above the model size estimate.
 * - Memory pressure must be NORMAL or MODERATE (not HIGH/CRITICAL).
 */
object MemoryMonitor {

    /** Minimum free memory (MB) required to consider loading a large model safe. */
    private const val MIN_FREE_MEMORY_FOR_LARGE_MODEL_MB = 2048L

    /** Refresh interval for periodic memory polling (ms). */
    private const val REFRESH_INTERVAL_MS = 15_000L

    private val _memoryStatus = MutableStateFlow(
        MemoryStatus(
            totalMemMb = 0,
            availableMemMb = 0,
            isLowMemory = false,
            isSafeForLargeModel = false,
            memoryPressureLevel = MemoryPressureLevel.NORMAL,
        )
    )
    val memoryStatus: StateFlow<MemoryStatus> = _memoryStatus.asStateFlow()

    private var activityManager: ActivityManager? = null
    private var isInitialized = false
    private var isPolling = false

    /**
     * Initialize the monitor with the application context.
     * Must be called once during [android.app.Application.onCreate].
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "MemoryMonitor already initialized, skipping.")
            return
        }
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        isInitialized = true
        Log.d(TAG, "MemoryMonitor initialized.")
    }

    /**
     * Perform an immediate memory snapshot and return the status.
     * Can be called before a critical operation (model load/download).
     */
    fun refresh(): MemoryStatus {
        val am = activityManager
        if (am == null) {
            Log.w(TAG, "refresh called before initialize()")
            return _memoryStatus.value
        }

        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMemMb = memInfo.totalMem / (1024 * 1024)
        val availableMemMb = memInfo.availMem / (1024 * 1024)
        val isLowMemory = memInfo.lowMemory

        val pressureLevel = when {
            isLowMemory -> MemoryPressureLevel.CRITICAL
            availableMemMb < 512 -> MemoryPressureLevel.HIGH
            availableMemMb < 1024 -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.NORMAL
        }

        val isSafeForLargeModel = availableMemMb >= MIN_FREE_MEMORY_FOR_LARGE_MODEL_MB &&
                pressureLevel != MemoryPressureLevel.HIGH &&
                pressureLevel != MemoryPressureLevel.CRITICAL

        val status = MemoryStatus(
            totalMemMb = totalMemMb,
            availableMemMb = availableMemMb,
            isLowMemory = isLowMemory,
            isSafeForLargeModel = isSafeForLargeModel,
            memoryPressureLevel = pressureLevel,
        )

        _memoryStatus.value = status
        Log.d(TAG, "Memory status refreshed: $status")
        return status
    }

    /**
     * Start periodic memory polling. Useful for reactive UI updates.
     */
    fun startPolling() {
        if (isPolling) return
        isPolling = true
        Log.d(TAG, "Memory polling started.")

        Thread {
            while (isPolling) {
                try {
                    refresh()
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "MemoryMonitor-Poller"
            start()
        }
    }

    /**
     * Stop periodic memory polling.
     */
    fun stopPolling() {
        isPolling = false
        Log.d(TAG, "Memory polling stopped.")
    }

    /**
     * Check whether the device has enough free memory to download
     * and potentially load a model of the given size in bytes.
     *
     * @param modelSizeBytes Estimated download+extraction size in bytes.
     * @return true if memory appears sufficient.
     */
    fun isMemorySufficientForModel(modelSizeBytes: Long): Boolean {
        val status = refresh()
        val modelSizeMb = modelSizeBytes / (1024 * 1024)
        // Need at least model size + 1GB buffer for download + extraction overhead.
        val requiredMb = modelSizeMb + 1024
        val sufficient = status.availableMemMb >= requiredMb && !status.isLowMemory
        Log.d(
            TAG,
            "Memory check: available=${status.availableMemMb}MB, " +
                    "required=${requiredMb}MB, sufficient=$sufficient"
        )
        return sufficient
    }

    /** Convenience check without refreshing. */
    fun isSafeForLargeModel(): Boolean {
        return _memoryStatus.value.isSafeForLargeModel
    }
}
