package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TranscriptStore's pure serialization and the message
 * key rule. The store itself is thin SharedPreferences glue (same
 * shape as ChatThemeStore); what can break — transcript text surviving
 * a round trip byte-for-byte, corrupt-input tolerance — is here.
 */
class TranscriptStoreTest {

    @Test
    fun stringsRoundTripWithHostileText() {
        val map = mapOf(
            "srv:abc123" to "He said \"meet at 5\"\nthen left — café ☕",
            "local:7:99:1700000000000" to "Backslash \\ and tab\there",
        )
        val json = TranscriptStore.serializeStrings(map)
        assertEquals(map, TranscriptStore.deserializeStrings(json))
    }

    @Test
    fun stringsEmptyRoundTrip() {
        assertEquals(
            emptyMap<String, String>(),
            TranscriptStore.deserializeStrings(
                TranscriptStore.serializeStrings(emptyMap()),
            ),
        )
    }

    @Test
    fun deserializeToleratesCorruption() {
        assertTrue(TranscriptStore.deserializeStrings("not json").isEmpty())
        assertTrue(TranscriptStore.deserializeStrings("").isEmpty())
        assertTrue(TranscriptStore.deserializeStrings("{\"a\":}").isEmpty())
        // Blank keys/values are dropped, real entries survive.
        val m = TranscriptStore.deserializeStrings("{\"\":\"x\",\"k\":\"v\",\"e\":\"\"}")
        assertEquals(mapOf("k" to "v"), m)
    }

    @Test
    fun keyPrefersServerId() {
        val msg = Message(
            id = 1234L, threadId = 7L, address = "+1", body = "",
            date = 1700000000000L, isOutgoing = false, isMms = false,
            read = true, serverId = "server-id-9",
        )
        assertEquals("srv:server-id-9", transcriptKeyFor(msg))
    }

    @Test
    fun keyFallsBackToLocalIdentity() {
        val msg = Message(
            id = 1234L, threadId = 7L, address = "+1", body = "",
            date = 1700000000000L, isOutgoing = false, isMms = false,
            read = true, serverId = null,
        )
        assertEquals("local:7:1234:1700000000000", transcriptKeyFor(msg))
        // Blank server ids fall back too.
        assertEquals(
            "local:7:1234:1700000000000",
            transcriptKeyFor(msg.copy(serverId = "  ")),
        )
    }
}
