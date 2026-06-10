/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NetworkMonitor"

/** Type of network connection. */
enum class NetworkType {
    /** Wi-Fi connection. */
    WIFI,
    /** Cellular/mobile data connection. */
    CELLULAR,
    /** Wired Ethernet connection. */
    ETHERNET,
    /** Cellular connection that is not metered (e.g. unlimited plan). */
    UNMETERED_CELLULAR,
    /** No network connection. */
    NONE
}

/**
 * Current network status reported by [NetworkMonitor].
 */
data class NetworkStatus(
    /** Whether the device has any active network connection. */
    val isConnected: Boolean,
    /** The type of the active network. */
    val networkType: NetworkType,
    /**
     * Whether the current network is suitable for downloading large files.
     * True for Wi-Fi, Ethernet, or unmetered cellular connections.
     */
    val isSuitableForLargeDownload: Boolean,
)

/**
 * Monitors network connectivity status and reports whether the current
 * connection is suitable for large file downloads (e.g. model files).
 *
 * Uses Android's [ConnectivityManager] with a [NetworkCallback] to
 * reactively track network changes. Exposes status via [StateFlow].
 */
object NetworkMonitor {

    private val _networkStatus = MutableStateFlow(
        NetworkStatus(
            isConnected = false,
            networkType = NetworkType.NONE,
            isSuitableForLargeDownload = false,
        )
    )
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isInitialized = false

    /**
     * Initialize the monitor with the application context.
     * Must be called once during [android.app.Application.onCreate].
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "NetworkMonitor already initialized, skipping.")
            return
        }

        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Report current status immediately.
        val currentNetwork = connectivityManager?.activeNetwork
        if (currentNetwork != null) {
            val caps = connectivityManager?.getNetworkCapabilities(currentNetwork)
            updateStatus(currentNetwork, caps)
        } else {
            _networkStatus.value = NetworkStatus(
                isConnected = false,
                networkType = NetworkType.NONE,
                isSuitableForLargeDownload = false,
            )
        }

        // Register callback for future network changes.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                val caps = connectivityManager?.getNetworkCapabilities(network)
                updateStatus(network, caps)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                // Check if we still have any active network.
                val activeNetwork = connectivityManager?.activeNetwork
                if (activeNetwork == null) {
                    _networkStatus.value = NetworkStatus(
                        isConnected = false,
                        networkType = NetworkType.NONE,
                        isSuitableForLargeDownload = false,
                    )
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                Log.d(TAG, "Network capabilities changed: $network")
                updateStatus(network, networkCapabilities)
            }
        }

        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, callback)

        isInitialized = true
        Log.d(TAG, "NetworkMonitor initialized. Current status: ${_networkStatus.value}")
    }

    /**
     * Unregister the network callback. Call during cleanup.
     */
    fun shutdown() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
        connectivityManager = null
        isInitialized = false
    }

    /** Convenience check for the current status. */
    fun isSuitableForLargeDownload(): Boolean {
        return _networkStatus.value.isSuitableForLargeDownload
    }

    /** Convenience check for connectivity. */
    fun isConnected(): Boolean {
        return _networkStatus.value.isConnected
    }

    private fun updateStatus(network: Network, capabilities: NetworkCapabilities?) {
        if (capabilities == null) {
            _networkStatus.value = NetworkStatus(
                isConnected = false,
                networkType = NetworkType.NONE,
                isSuitableForLargeDownload = false,
            )
            return
        }

        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    NetworkType.CELLULAR
                } else {
                    NetworkType.UNMETERED_CELLULAR
                }
            }
            else -> NetworkType.NONE
        }

        val isSuitable = networkType == NetworkType.WIFI ||
                networkType == NetworkType.ETHERNET ||
                networkType == NetworkType.UNMETERED_CELLULAR

        _networkStatus.value = NetworkStatus(
            isConnected = true,
            networkType = networkType,
            isSuitableForLargeDownload = isSuitable,
        )

        Log.d(TAG, "Network status updated: ${_networkStatus.value}")
    }
}
