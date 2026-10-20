package com.garrietta.relay.data.transcription

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** The WAV wrapper the Gemini transcode path sends: a canonical header. */
class PcmAudioDecoderTest {

    @Test
    fun wavHeaderIsCanonical() {
        val pcm = PcmAudio(ByteArray(3200) { (it % 251).toByte() }, sampleRate = 16_000)
        val wav = PcmAudioDecoder.wavBytes(pcm)
        assertEquals(44 + 3200, wav.size)
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 3200, bb.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16, bb.getInt(16))
        assertEquals(1, bb.getShort(20)) // PCM
        assertEquals(1, bb.getShort(22)) // mono
        assertEquals(16_000, bb.getInt(24))
        assertEquals(32_000, bb.getInt(28)) // byte rate
        assertEquals(2, bb.getShort(32)) // block align
        assertEquals(16, bb.getShort(34)) // bits per sample
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(3200, bb.getInt(40))
        // Payload passes through untouched.
        assertEquals(pcm.bytes.toList(), wav.copyOfRange(44, wav.size).toList())
    }
}
