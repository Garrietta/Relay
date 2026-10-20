package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ChatThemeStore's pure serialization. The store itself
 * is thin SharedPreferences glue (same shape as CategoryStore); the
 * logic that can break — id-map round-trips, corrupt-input tolerance,
 * blank-entry rejection — lives in the companion and is tested here.
 */
class ChatThemeStoreTest {

    @Test
    fun idsRoundTrip() {
        val map = mapOf(
            "rcs:abc123" to "ocean",
            "tel:42" to "berry",
        )
        val json = ChatThemeStore.serializeIds(map)
        assertEquals(map, ChatThemeStore.deserializeIds(json))
    }

    @Test
    fun idsEmptyRoundTrip() {
        assertEquals(
            emptyMap<String, String>(),
            ChatThemeStore.deserializeIds(ChatThemeStore.serializeIds(emptyMap())),
        )
    }

    @Test
    fun idsCorruptInputYieldsEmpty() {
        assertTrue(ChatThemeStore.deserializeIds("garbage").isEmpty())
        assertTrue(ChatThemeStore.deserializeIds("").isEmpty())
        assertTrue(ChatThemeStore.deserializeIds("[1,2,3]").isEmpty())
        assertTrue(ChatThemeStore.deserializeIds("""{"a":"b"} trailing""").isEmpty())
    }

    @Test
    fun idsDropBlankEntries() {
        val json = """{"":"ocean","tel:1":"","tel:2":"rose"}"""
        assertEquals(
            mapOf("tel:2" to "rose"),
            ChatThemeStore.deserializeIds(json),
        )
    }

    @Test
    fun idsEscapeSpecialCharacters() {
        // Conversation ids are server-issued strings — quotes/backslashes
        // must survive the round-trip, not corrupt the map.
        val map = mapOf("we\"ird\\key" to "sunset")
        assertEquals(
            map,
            ChatThemeStore.deserializeIds(ChatThemeStore.serializeIds(map)),
        )
    }

    @Test
    fun defaultThemeIsDefault() {
        assertTrue(ChatTheme().isDefault)
        assertFalse(ChatTheme(backgroundId = "ocean").isDefault)
        assertFalse(ChatTheme(colorwayId = "rose").isDefault)
    }
}
