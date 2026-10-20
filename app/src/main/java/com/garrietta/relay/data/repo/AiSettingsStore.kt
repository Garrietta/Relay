package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where voice-note transcripts come from (Garrett 2026-10-20, feature
 * queue 7/7 — his addition to the Gemini-key item: a real provider
 * CHOICE, not Gemini-or-nothing).
 */
enum class TranscriptionProvider {
    /** Android's on-device SpeechRecognizer: the audio never leaves the phone. */
    ON_DEVICE,

    /** Google Gemini, called with Garrett's own API key (see [AiSettingsStore]). */
    GEMINI,

    /** No transcribe affordances anywhere in the app. */
    OFF,
}

/** Parses a persisted provider name; anything unknown falls back to [fallback]. */
fun transcriptionProviderFromName(
    name: String?,
    fallback: TranscriptionProvider = TranscriptionProvider.GEMINI,
): TranscriptionProvider =
    TranscriptionProvider.entries.firstOrNull { it.name == name } ?: fallback

/**
 * AI settings (Garrett 2026-10-20, feature queue 7/7): Garrett's OWN
 * Google Gemini API key + the voice-note transcription provider choice.
 *
 * The key is entered by Garrett in Settings → AI and lives ONLY here:
 * app-private SharedPreferences, mirroring [MuteStore] / [ChatThemeStore].
 * It is never logged, never written to Diagnostics, and never leaves the
 * device except as the `x-goog-api-key` header on calls to Google's
 * Gemini API that Garrett's own transcribe taps trigger. Relay ships
 * with NO key — until he pastes his, the Gemini paths explain that
 * plainly in their UI.
 *
 * Default provider is GEMINI (the headline path of the feature): the
 * Transcribe actions appear immediately and explain the missing key when
 * tapped, which is more discoverable than defaulting to OFF.
 */
class AiSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(loadKey(prefs))
    /** Garrett's Gemini API key, trimmed; null when none is set. */
    val apiKey: StateFlow<String?> = _apiKey.asStateFlow()

    private val _provider = MutableStateFlow(
        transcriptionProviderFromName(prefs.getString(KEY_PROVIDER, null)),
    )
    /** The active voice-note transcription provider. */
    val provider: StateFlow<TranscriptionProvider> = _provider.asStateFlow()

    /** Saves (or, on blank, clears) the Gemini API key; persists immediately. */
    fun setApiKey(raw: String) {
        val key = raw.trim().ifBlank { null }
        if (key == _apiKey.value) return
        _apiKey.value = key
        val edit = prefs.edit()
        if (key == null) edit.remove(KEY_API_KEY) else edit.putString(KEY_API_KEY, key)
        edit.apply()
    }

    /** Removes the stored Gemini API key. */
    fun clearApiKey() = setApiKey("")

    /** Sets the transcription provider; persists immediately. */
    fun setProvider(provider: TranscriptionProvider) {
        if (provider == _provider.value) return
        _provider.value = provider
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    companion object {
        private const val PREFS = "relay_ai"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_PROVIDER = "transcription_provider"

        private fun loadKey(prefs: SharedPreferences): String? =
            prefs.getString(KEY_API_KEY, null)?.trim()?.ifBlank { null }
    }
}
