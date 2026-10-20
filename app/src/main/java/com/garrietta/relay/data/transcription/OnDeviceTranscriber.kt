package com.garrietta.relay.data.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Voice-note transcription with Android's ON-DEVICE SpeechRecognizer
 * (Garrett 2026-10-20, feature queue 7/7 — his on-device option).
 *
 * Why this shape — the platform facts, verified against the Android
 * API reference while building this:
 *  - There is NO Galaxy AI API for third-party apps, and Relay takes
 *    no Play-services dependency for this. The platform path is
 *    [SpeechRecognizer.createOnDeviceSpeechRecognizer] (API 31+),
 *    gated on [SpeechRecognizer.isOnDeviceRecognitionAvailable]: when
 *    it returns true, recognition runs in the phone's on-device
 *    speech service and the audio never leaves the device.
 *  - SpeechRecognizer is designed for LIVE MICROPHONE audio. File
 *    input exists only as RecognizerIntent.EXTRA_AUDIO_SOURCE
 *    (API 33+): a ParcelFileDescriptor of RAW PCM plus its encoding /
 *    sample-rate / channel extras. The note's own container (AMR,
 *    AAC…) is therefore decoded to mono PCM first ([PcmAudioDecoder]).
 *  - Whether a given phone's on-device service HONORS the audio
 *    source is implementation-defined — the API contract says a
 *    recognizer that doesn't support it falls back to the microphone.
 *    Relay never requests RECORD_AUDIO, so that fallback fails loudly
 *    (ERROR_INSUFFICIENT_PERMISSIONS) or hears nothing (ERROR_NO_MATCH
 *    / ERROR_SPEECH_TIMEOUT) — and those failures surface VERBATIM
 *    with the explanation below, never silently. On a phone whose
 *    service doesn't accept files, the honest answer is the Gemini
 *    provider, and the error says so.
 *
 * No bundled model: a Whisper-class engine would add tens of MB to a
 * ~14 MB release APK for a fallback the platform may already provide.
 */
object OnDeviceTranscriber {

    private const val RECOGNITION_TIMEOUT_MS = 120_000L

    /**
     * Transcribes [audio] (a whole voice note) fully on-device.
     * [stagingDir] is scratch space for the decode. Returns the
     * transcript text; throws IllegalStateException with the plain,
     * verbatim reason on any failure.
     */
    suspend fun transcribe(
        context: Context,
        audio: ByteArray,
        mimeType: String,
        stagingDir: File,
    ): String {
        if (Build.VERSION.SDK_INT < 31) {
            throw IllegalStateException(
                "On-device transcription needs Android 12 or newer — this phone " +
                    "reports API ${Build.VERSION.SDK_INT}. Switch the transcription " +
                    "provider to Gemini in Settings → AI.",
            )
        }
        if (Build.VERSION.SDK_INT < 33) {
            throw IllegalStateException(
                "On-device transcription of an audio FILE needs Android 13 or " +
                    "newer (the recognizer's audio-source API) — this phone " +
                    "reports API ${Build.VERSION.SDK_INT}. Switch the " +
                    "transcription provider to Gemini in Settings → AI.",
            )
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IllegalStateException(
                "No on-device speech recognition service is available on this " +
                    "phone, so Relay can't transcribe on-device. Switch the " +
                    "transcription provider to Gemini in Settings → AI.",
            )
        }
        if (audio.isEmpty()) {
            throw IllegalStateException("On-device transcription failed: the voice note is empty")
        }
        stagingDir.mkdirs()
        val pcm = withContext(Dispatchers.Default) {
            val staged = File(stagingDir, "note-${System.nanoTime()}.bin")
            try {
                staged.writeBytes(audio)
                PcmAudioDecoder.decodeToMonoPcm(staged)
            } finally {
                staged.delete()
            }
        }
        val pcmFile = File(stagingDir, "note-${System.nanoTime()}.pcm")
        try {
            pcmFile.writeBytes(pcm.bytes)
            // SpeechRecognizer must be created and driven from the main
            // thread (platform contract).
            return withContext(Dispatchers.Main) {
                recognize(context, pcmFile, pcm.sampleRate)
            }
        } finally {
            pcmFile.delete()
        }
    }

    private suspend fun recognize(context: Context, pcmFile: File, sampleRate: Int): String {
        val pfd = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val text = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    cont.invokeOnCancellation { runCatching { recognizer.destroy() } }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            val first = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            runCatching { recognizer.destroy() }
                            if (!cont.isActive) return
                            if (first.isEmpty()) {
                                cont.resumeWithException(
                                    IllegalStateException(noSpeechMessage(SpeechRecognizer.ERROR_NO_MATCH)),
                                )
                            } else {
                                cont.resume(first)
                            }
                        }

                        override fun onError(error: Int) {
                            runCatching { recognizer.destroy() }
                            if (!cont.isActive) return
                            cont.resumeWithException(
                                IllegalStateException(errorMessage(error)),
                            )
                        }

                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        // The file path: raw PCM through an already-opened
                        // descriptor, its format declared alongside.
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                    }
                    recognizer.startListening(intent)
                }
            }
            return text ?: throw IllegalStateException(
                "On-device transcription timed out — the recognizer produced " +
                    "nothing for this voice note. The Gemini provider in " +
                    "Settings → AI transcribes files directly.",
            )
        } finally {
            runCatching { pfd.close() }
        }
    }

    private fun errorMessage(code: Int): String {
        val name = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            else -> "ERROR_UNKNOWN"
        }
        val base = "On-device transcription failed: recognizer error $code ($name)"
        return when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "$base. ${noSpeechGuidance()}"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "$base — the recognizer tried to open the microphone instead " +
                    "of reading the audio file (Relay never records audio), " +
                    "so this phone's on-device service doesn't accept audio " +
                    "files. ${geminiGuidance()}"
            else -> base
        }
    }

    private fun noSpeechMessage(code: Int): String = errorMessage(code)

    private fun noSpeechGuidance(): String =
        "The recognizer heard no speech in this file — on some phones the " +
            "on-device service accepts only live microphone input, not audio " +
            "files. ${geminiGuidance()}"

    private fun geminiGuidance(): String =
        "The Gemini provider in Settings → AI transcribes voice-note files directly."
}
