/*
 * Copyright 2026 Elva LaoBai Contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.elva.laobai.system

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private const val TAG = "HashVerifier"

/**
 * Utility for verifying file integrity using SHA-256 hashing.
 *
 * After downloading large model files (often several GB), the file content
 * is verified against a known SHA-256 checksum to detect corruption or
 * malicious tampering. Verification is performed on IO dispatcher using
 * streaming reads to avoid loading the entire file into memory.
 */
object HashVerifier {

    /** Size of the read buffer used during streaming hash computation. */
    private const val BUFFER_SIZE = 8192

    /**
     * Computes the SHA-256 digest of [file] and compares it against [expectedHash].
     *
     * @param file         The file to verify.
     * @param expectedHash The expected SHA-256 hex string (case-insensitive).
     * @return true if the computed hash matches [expectedHash], false otherwise.
     */
    suspend fun verifySha256(file: File, expectedHash: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!file.exists() || !file.isFile) {
                    Log.e(TAG, "File does not exist or is not a regular file: ${file.absolutePath}")
                    return@withContext false
                }

                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }

                val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }
                val matches = calculatedHash.equals(expectedHash, ignoreCase = true)
                if (matches) {
                    Log.d(TAG, "SHA-256 verification PASSED for ${file.name}")
                } else {
                    Log.e(
                        TAG,
                        "SHA-256 MISMATCH for ${file.name}: expected=$expectedHash, calculated=$calculatedHash",
                    )
                }
                matches
            } catch (e: Exception) {
                Log.e(TAG, "SHA-256 verification failed with exception for ${file.name}", e)
                false
            }
        }
    }
}
