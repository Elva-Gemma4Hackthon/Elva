/*
 * Copyright 2026 Elva LaoBai Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.elva.laobai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TextToSpeech manager for Elva LaoBai.
 * Speaks model responses aloud for elderly users.
 */
object ElvaTtsManager : TextToSpeech.OnInitListener {
    private const val TAG = "ElvaTts"

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isEnabled = true
    private var pendingText: String? = null

    /** Initialize TTS engine. Call once from Application.onCreate or Activity. */
    fun initialize(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext, this)
    }

    /** Called when TTS engine is initialized. */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.language = Locale.CHINESE
            Log.d(TAG, "TTS initialized successfully with Chinese locale")

            // Speak any pending text
            pendingText?.let {
                speak(it)
                pendingText = null
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /** Speak the given text aloud. If TTS is not ready, queues the text. */
    fun speak(text: String) {
        if (!isEnabled) return

        // Strip markdown and special characters for cleaner speech
        val cleanText = text
            .replace(Regex("[*_#`~\\[\\]\\(\\)]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanText.isEmpty()) return

        if (!isInitialized) {
            pendingText = cleanText
            Log.d(TAG, "TTS not ready, queuing text: ${cleanText.take(50)}...")
            return
        }

        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "elva_response_${System.currentTimeMillis()}")
        Log.d(TAG, "Speaking: ${cleanText.take(80)}...")
    }

    /** Stop current speech. */
    fun stop() {
        tts?.stop()
    }

    /** Enable or disable TTS. */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) stop()
    }

    /** Check if TTS is enabled. */
    fun enabled(): Boolean = isEnabled

    /** Shut down TTS engine. Call when done. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
