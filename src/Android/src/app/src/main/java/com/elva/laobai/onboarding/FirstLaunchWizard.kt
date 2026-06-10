/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.onboarding

import android.content.Context
import android.util.Log
import com.elva.laobai.network.NetworkMonitor
import com.elva.laobai.network.NetworkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "FirstLaunchWizard"
private const val PREFS_NAME = "elva_first_launch"
private const val KEY_FIRST_LAUNCH_COMPLETED = "first_launch_completed"

/**
 * Represents the current step in the first-launch wizard flow.
 */
enum class FirstLaunchStep {
    /** No action needed (not first launch, or already completed). */
    IDLE,
    /** Checking network connectivity. */
    CHECKING_NETWORK,
    /** Network suitable — ready to trigger auto-download. */
    READY_TO_DOWNLOAD,
    /** On metered cellular — waiting for user confirmation. */
    AWAITING_CONFIRMATION,
    /** No network — show offline message, proceed to main screen. */
    OFFLINE,
    /** First launch setup fully completed. */
    COMPLETED,
}

/**
 * Wizard state exposed to the UI layer.
 */
data class FirstLaunchState(
    val step: FirstLaunchStep = FirstLaunchStep.IDLE,
    val recommendedModelName: String = "",
    val networkType: NetworkType = NetworkType.NONE,
)

/**
 * Manages the first-launch experience for Elva LaoBai.
 *
 * On the very first launch after installation (or after clearing app data),
 * this wizard:
 * 1. Detects whether this is the first launch via SharedPreferences.
 * 2. Checks network status via [NetworkMonitor].
 * 3. On Wi-Fi/Ethernet: recommends the best model and signals the UI
 *    to auto-trigger download.
 * 4. On metered cellular: signals the UI to show a confirmation dialog.
 * 5. On no network: signals the UI to show an offline message.
 * 6. Once completed, sets a persistent flag so the wizard never runs again.
 *
 * The actual download is NOT triggered here — the UI layer (GalleryNavGraph)
 * observes the state and calls [ModelManagerViewModel.downloadModel] as needed.
 */
object FirstLaunchWizard {

    private val _state = MutableStateFlow(FirstLaunchState())
    val state: StateFlow<FirstLaunchState> = _state.asStateFlow()

    private var isInitialized = false
    private var context: Context? = null

    /**
     * Initialize the wizard. Called once during Application.onCreate().
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        this.context = context.applicationContext
        isInitialized = true
    }

    /**
     * Begin the first-launch check. Should be called from the UI layer
     * when the main screen first appears.
     *
     * @return true if this IS the first launch (wizard flow started),
     *         false if it's already been completed before.
     */
    fun checkFirstLaunch(): Boolean {
        val ctx = context ?: run {
            Log.e(TAG, "checkFirstLaunch called before initialize()")
            return false
        }

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyCompleted = prefs.getBoolean(KEY_FIRST_LAUNCH_COMPLETED, false)

        if (alreadyCompleted) {
            Log.d(TAG, "First launch already completed, skipping wizard.")
            _state.value = FirstLaunchState(step = FirstLaunchStep.COMPLETED)
            return false
        }

        Log.d(TAG, "First launch detected. Starting wizard.")
        _state.value = FirstLaunchState(step = FirstLaunchStep.CHECKING_NETWORK)
        return true
    }

    /**
     * Evaluate network status and transition to the appropriate step.
     * Called after [checkFirstLaunch] returns true and the UI is ready.
     */
    fun evaluateNetworkStatus() {
        val networkStatus = NetworkMonitor.networkStatus.value
        Log.d(TAG, "Evaluating network: $networkStatus")

        when {
            !networkStatus.isConnected -> {
                _state.value = FirstLaunchState(
                    step = FirstLaunchStep.OFFLINE,
                    networkType = NetworkType.NONE,
                )
            }
            networkStatus.isSuitableForLargeDownload -> {
                _state.value = FirstLaunchState(
                    step = FirstLaunchStep.READY_TO_DOWNLOAD,
                    networkType = networkStatus.networkType,
                )
            }
            else -> {
                _state.value = FirstLaunchState(
                    step = FirstLaunchStep.AWAITING_CONFIRMATION,
                    networkType = networkStatus.networkType,
                )
            }
        }
    }

    /**
     * User confirmed download on metered network.
     * Transitions to [FirstLaunchStep.READY_TO_DOWNLOAD].
     */
    fun confirmMeteredDownload() {
        Log.d(TAG, "User confirmed download on metered network.")
        _state.value = _state.value.copy(step = FirstLaunchStep.READY_TO_DOWNLOAD)
    }

    /**
     * Mark the first-launch wizard as completed.
     * Persists the flag so it never runs again.
     */
    fun markCompleted() {
        val ctx = context ?: return
        Log.d(TAG, "Marking first launch as completed.")
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_LAUNCH_COMPLETED, true)
            .apply()
        _state.value = FirstLaunchState(step = FirstLaunchStep.COMPLETED)
    }
}
