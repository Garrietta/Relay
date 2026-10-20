package com.garrietta.relay.data.transcription

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Voice-note transcription through Google Gemini (Garrett 2026-10-20,
 * feature queue 7/7) — called ONLY with Garrett's own API key from
 * [com.garrietta.relay.data.repo.AiSettingsStore]. The key rides in the
 * `x-goog-api-key` header (never in a URL, never logged), and every
 * failure — HTTP status, the API's own error message, an empty or
 * blocked response — surfaces VERBATIM to the chat snackbar, Relay's
 * standing no-logcat rule.
 *
 * The request is the documented generateContent audio shape: a text
 * part ("transcribe verbatim") + the audio as inline base64. Voice
 * notes are small, so no Files API upload dance is needed. When the
 * note's own container is NOT one Gemini accepts (AMR — Google
 * Messages' classic voice-note format — is not on its list), the audio
 * is decoded on-device to PCM and sent as WAV instead (see
 * [PcmAudioDecoder]); the decode happening locally does not change
 * where the transcription happens.
 */
object GeminiTranscriber {

    /**
     * The transcription model: Google's current audio-capable Flash
     * (the same default Garrett's Koda is configured with). Audio
     * understanding is a documented generateContent capability of this
     * model.
     */
    const val MODEL = "gemini-3.8-flash"

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"

    private const val TRANSCRIBE_PROMPT =
        "Transcribe this voice message verbatim. Output only the " +
            "transcript text — no preamble, no summary, no speaker " +
            "labels, no timestamps."

    /**
     * Audio containers Gemini's generateContent accepts directly.
     * Anything else (audio/amr, audio/3gpp, audio/opus, …) is
     * transcoded to WAV from the on-device decode.
     */
    private val DIRECT_MIME_TYPES = setOf(
        "audio/wav", "audio/x-wav", "audio/wave",
        "audio/mp3", "audio/mpeg",
        "audio/aac",
        "audio/ogg",
        "audio/flac", "audio/x-flac",
        "audio/aiff", "audio/x-aiff",
        "audio/mp4", "audio/m4a", "audio/x-m4a",
    )

    /** True when [mimeType] can be inlined to Gemini without transcoding. */
    fun isDirectMimeType(mimeType: String): Boolean =
        mimeType.substringBefore(';').trim().lowercase() in DIRECT_MIME_TYPES

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The Settings "Test key" action: ONE minimal call (list models —
     * validates the key, spends no generation quota). Returns the
     * success line for the snackbar; throws IllegalStateException with
     * the verbatim failure otherwise.
     */
    fun testKey(apiKey: String): String {
        val request = Request.Builder()
            .url("$API_BASE/models?pageSize=1")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(apiFailure("Gemini key test", response.code, body))
            }
            val count = parseModelCount(body)
            return if (count != null) {
                "Gemini key works — the API answered (models list OK, model $MODEL ready)"
            } else {
                "Gemini key works — the API answered (HTTP ${response.code})"
            }
        }
    }

    /**
     * Transcribes [audio] (a whole voice note). [stagingDir] is a
     * scratch directory for the transcode path only. Returns the
     * transcript text; throws IllegalStateException with the verbatim
     * reason on any failure.
     */
    fun transcribe(apiKey: String, audio: ByteArray, mimeType: String, stagingDir: File): String {
        if (audio.isEmpty()) {
            throw IllegalStateException("Gemini transcription failed: the voice note is empty")
        }
        var sendBytes = audio
        var sendMime = mimeType.substringBefore(';').trim().lowercase()
        if (!isDirectMimeType(sendMime)) {
            // AMR & friends: decode on-device and send WAV. A decode
            // failure is itself the verbatim error.
            stagingDir.mkdirs()
            val staged = File(stagingDir, "note-${System.nanoTime()}.bin")
            try {
                staged.writeBytes(audio)
                val pcm = PcmAudioDecoder.decodeToMonoPcm(staged)
                sendBytes = PcmAudioDecoder.wavBytes(pcm)
                sendMime = "audio/wav"
            } finally {
                staged.delete()
            }
        }
        val request = Request.Builder()
            .url("$API_BASE/models/$MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(buildTranscribeRequestJson(sendBytes, sendMime)
                .toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(apiFailure("Gemini transcription", response.code, body))
            }
            return parseTranscriptResponse(body)
        }
    }

    // ---- Pure helpers (unit-tested) ----

    /** The generateContent request body: prompt text part + inline audio part. */
    fun buildTranscribeRequestJson(audio: ByteArray, mimeType: String): String {
        val root = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", TRANSCRIBE_PROMPT) })
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", mimeType)
                                put("data", Base64.getEncoder().encodeToString(audio))
                            }
                        })
                    })
                })
            })
        }
        return root.toString()
    }

    /**
     * Extracts the transcript from a generateContent response: the
     * first candidate's text parts, concatenated. A blocked/empty
     * response throws with the API's own reason verbatim
     * (promptFeedback.blockReason, the candidate's finishReason).
     */
    fun parseTranscriptResponse(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse {
                throw IllegalStateException(
                    "Gemini transcription failed: unreadable response from the API",
                )
            }
        parseApiError(root)?.let {
            throw IllegalStateException("Gemini transcription failed: $it")
        }
        val candidate = runCatching {
            root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull()
        if (candidate == null) {
            val blockReason = root["promptFeedback"]?.jsonObject
                ?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(
                "Gemini transcription failed: no transcript returned" +
                    (blockReason?.let { " (blocked: $it)" } ?: ""),
            )
        }
        val parts = runCatching {
            candidate["content"]?.jsonObject?.get("parts")?.jsonArray
        }.getOrNull().orEmpty()
        val text = parts.mapNotNull {
            runCatching { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }.joinToString("").trim()
        if (text.isEmpty()) {
            val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(
                "Gemini transcription failed: the model returned an empty transcript" +
                    (finish?.let { " (finishReason: $it)" } ?: ""),
            )
        }
        return text
    }

    /** Model count from a models-list response; null when unparseable. */
    fun parseModelCount(body: String): Int? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["models"]?.jsonArray?.size
            ?: root["totalSize"]?.jsonPrimitive?.intOrNull
    }.getOrNull()

    /** The API's own error message from an error body, verbatim; null when absent. */
    fun parseApiError(body: String): String? = runCatching {
        parseApiError(json.parseToJsonElement(body).jsonObject)
    }.getOrNull()

    private fun parseApiError(root: JsonObject): String? =
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull

    /** Formats a failed HTTP call: our label + status + the API's verbatim message. */
    private fun apiFailure(label: String, code: Int, body: String): String {
        val detail = parseApiError(body)
            ?: body.trim().take(200).ifBlank { "no response body" }
        return "$label failed (HTTP $code): $detail"
    }
}
