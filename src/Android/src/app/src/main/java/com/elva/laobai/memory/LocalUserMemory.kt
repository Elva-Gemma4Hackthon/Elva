@file:Suppress("DEPRECATION")

/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Local User Memory — encrypted on-device storage for user-authorized
 * personal information used by the form-filling assistant (Case 1).
 *
 * Fields stored:
 * - display_name: Display name / preferred name
 * - phone_masked: Masked phone number (not full number in V1)
 * - address_label: Address label (not full address)
 * - emergency_contact_label: Emergency contact label
 * - medical_card_label: Medical insurance card label
 * - preferred_hospital: Preferred hospital for bookings
 * - preferred_department: Preferred department
 *
 * SECURITY:
 * - All values are encrypted using AndroidX Security Crypto.
 * - Only authorized fields are readable by the assistant.
 * - Cloud requests NEVER see raw values — only authorized/masked labels.
 */
object LocalUserMemory {
    private const val TAG = "LocalUserMemory"

    /** SharedPreferences file name. */
    private const val PREFS_NAME = "elva_user_memory_encrypted"

    /** Authorization preferences file name (plain text for authorization flags). */
    private const val AUTH_PREFS_NAME = "elva_user_memory_auth"

    /** Field keys used for storage and retrieval. */
    object FieldKeys {
        const val DISPLAY_NAME = "display_name"
        const val PHONE_MASKED = "phone_masked"
        const val ADDRESS_LABEL = "address_label"
        const val EMERGENCY_CONTACT_LABEL = "emergency_contact_label"
        const val MEDICAL_CARD_LABEL = "medical_card_label"
        const val PREFERRED_HOSPITAL = "preferred_hospital"
        const val PREFERRED_DEPARTMENT = "preferred_department"

        /** All field keys. */
        val ALL_KEYS = listOf(
            DISPLAY_NAME, PHONE_MASKED, ADDRESS_LABEL,
            EMERGENCY_CONTACT_LABEL, MEDICAL_CARD_LABEL,
            PREFERRED_HOSPITAL, PREFERRED_DEPARTMENT,
        )
    }

    private var encryptedPrefs: SharedPreferences? = null
    private var authPrefs: SharedPreferences? = null

    private val _state = MutableStateFlow<Map<String, String>>(emptyMap())
    val state: StateFlow<Map<String, String>> = _state.asStateFlow()

    /**
     * Initialize the encrypted storage.
     * Must be called once before any read/write operations.
     */
    fun initialize(context: Context) {
        if (encryptedPrefs != null) return

        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)

            // Load existing data into state
            CoroutineScope(Dispatchers.IO).launch {
                refreshState()
            }

            Log.d(TAG, "Encrypted LocalUserMemory initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encrypted storage", e)
        }
    }

    /**
     * Get all stored user information fields.
     * Only returns fields that have been authorized.
     */
    suspend fun getUserInfo(): Map<String, String> {
        val prefs = encryptedPrefs ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in FieldKeys.ALL_KEYS) {
            if (isAuthorized(key)) {
                val value = prefs.getString(key, null)
                if (value != null) {
                    result[key] = value
                }
            }
        }
        return result
    }

    /**
     * Get a single field value by key.
     * Returns null if the field is not authorized, not stored, or empty.
     */
    suspend fun getField(key: String): String? {
        if (!isAuthorized(key)) return null
        val prefs = encryptedPrefs ?: return null
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Store a single field value (encrypted).
     */
    suspend fun setField(key: String, value: String) {
        val prefs = encryptedPrefs ?: return
        prefs.edit().putString(key, value).apply()
        Log.d(TAG, "Field stored: $key")
        refreshState()
    }

    /**
     * Batch store multiple field values.
     */
    suspend fun setFields(fields: Map<String, String>) {
        val prefs = encryptedPrefs ?: return
        val editor = prefs.edit()
        for ((key, value) in fields) {
            editor.putString(key, value)
        }
        editor.apply()
        Log.d(TAG, "Batch stored ${fields.size} fields")
        refreshState()
    }

    /**
     * Check if a field has been authorized by the user for use
     * by the assistant.
     */
    suspend fun isAuthorized(fieldKey: String): Boolean {
        val prefs = authPrefs ?: return false
        return prefs.getBoolean("auth_$fieldKey", false)
    }

    /**
     * Mark a field as authorized for assistant use.
     * This should be triggered by explicit user consent.
     */
    suspend fun authorizeField(fieldKey: String) {
        val prefs = authPrefs ?: return
        prefs.edit().putBoolean("auth_$fieldKey", true).apply()
        Log.d(TAG, "Field authorized: $fieldKey")
    }

    /**
     * Revoke authorization for a field.
     */
    suspend fun revokeField(fieldKey: String) {
        val prefs = authPrefs ?: return
        prefs.edit().putBoolean("auth_$fieldKey", false).apply()
        Log.d(TAG, "Field authorization revoked: $fieldKey")
    }

    /**
     * Delete a stored field value.
     */
    suspend fun deleteField(fieldKey: String) {
        val prefs = encryptedPrefs ?: return
        prefs.edit().remove(fieldKey).apply()
        // Also revoke authorization
        revokeField(fieldKey)
        refreshState()
    }

    /**
     * Refresh the state flow with current data.
     */
    private suspend fun refreshState() {
        _state.value = getUserInfo()
    }
}
