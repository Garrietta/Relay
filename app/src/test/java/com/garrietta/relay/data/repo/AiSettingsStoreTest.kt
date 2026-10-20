package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/** The persisted-provider parse: known names round-trip, junk falls back. */
class AiSettingsStoreTest {

    @Test
    fun providerNamesParse() {
        assertEquals(
            TranscriptionProvider.ON_DEVICE,
            transcriptionProviderFromName("ON_DEVICE"),
        )
        assertEquals(
            TranscriptionProvider.GEMINI,
            transcriptionProviderFromName("GEMINI"),
        )
        assertEquals(
            TranscriptionProvider.OFF,
            transcriptionProviderFromName("OFF"),
        )
    }

    @Test
    fun providerJunkFallsBackToGemini() {
        assertEquals(
            TranscriptionProvider.GEMINI,
            transcriptionProviderFromName(null),
        )
        assertEquals(
            TranscriptionProvider.GEMINI,
            transcriptionProviderFromName("whisper"),
        )
        assertEquals(
            TranscriptionProvider.OFF,
            transcriptionProviderFromName("whisper", TranscriptionProvider.OFF),
        )
    }
}
