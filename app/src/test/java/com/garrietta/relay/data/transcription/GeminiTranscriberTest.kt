package com.garrietta.relay.data.transcription

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Gemini wire shape, pinned: the request must carry the audio as
 * base64 inline data with its mime type (the generateContent audio
 * contract), and response parsing must extract the transcript text —
 * or surface the API's own error verbatim.
 */
class GeminiTranscriberTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestCarriesBase64AudioAndPrompt() {
        val audio = byteArrayOf(1, 2, 3, 4, 5)
        val root = json.parseToJsonElement(
            GeminiTranscriber.buildTranscribeRequestJson(audio, "audio/mp4"),
        ).jsonObject
        val parts = root["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        val prompt = parts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("Transcribe"))
        val inline = parts[1].jsonObject["inline_data"]!!.jsonObject
        assertEquals("audio/mp4", inline["mime_type"]!!.jsonPrimitive.content)
        assertEquals(
            Base64.getEncoder().encodeToString(audio),
            inline["data"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun responseTextIsExtracted() {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"See you at "},{"text":"five."}]},"finishReason":"STOP"}]}
        """.trimIndent()
        assertEquals("See you at five.", GeminiTranscriber.parseTranscriptResponse(body))
    }

    @Test
    fun apiErrorSurfacesVerbatim() {
        val body = """
            {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
              "status":"INVALID_ARGUMENT"}}
        """.trimIndent()
        assertEquals(
            "API key not valid. Please pass a valid API key.",
            GeminiTranscriber.parseApiError(body),
        )
        try {
            GeminiTranscriber.parseTranscriptResponse(body)
            fail("expected the API error to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("API key not valid"))
        }
    }

    @Test
    fun blockedResponseNamesTheReason() {
        val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        try {
            GeminiTranscriber.parseTranscriptResponse(body)
            fail("expected a blocked response to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("SAFETY"))
        }
    }

    @Test
    fun modelCountParses() {
        assertEquals(
            2,
            GeminiTranscriber.parseModelCount(
                """{"models":[{"name":"models/a"},{"name":"models/b"}]}""",
            ),
        )
        assertNull(GeminiTranscriber.parseModelCount("""{"error":{}}"""))
    }

    @Test
    fun directMimeGate() {
        // Gemini accepts these inline as-is.
        assertTrue(GeminiTranscriber.isDirectMimeType("audio/mp4"))
        assertTrue(GeminiTranscriber.isDirectMimeType("audio/mpeg"))
        assertTrue(GeminiTranscriber.isDirectMimeType("audio/ogg; codecs=opus"))
        assertTrue(GeminiTranscriber.isDirectMimeType("AUDIO/WAV"))
        // AMR (Google Messages' voice-note classic) is NOT accepted —
        // it transcodes to WAV first.
        assertFalse(GeminiTranscriber.isDirectMimeType("audio/amr"))
        assertFalse(GeminiTranscriber.isDirectMimeType("audio/3gpp"))
        assertFalse(GeminiTranscriber.isDirectMimeType("audio/opus"))
    }
}
