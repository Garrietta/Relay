package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CategoryStore's pure serialization + key helpers.
 * The Android-backed class is thin SharedPreferences glue; the logic that
 * can break (round-trips, corrupt-input tolerance, key stability) lives in
 * the companion and is tested here without Robolectric.
 */
class CategoryStoreTest {

    @Test
    fun categoriesRoundTrip() {
        val cats = listOf(
            ChatCategory("id-1", "Work"),
            ChatCategory("id-2", "Family & Friends"),
        )
        val json = CategoryStore.serializeCategories(cats)
        assertEquals(cats, CategoryStore.deserializeCategories(json))
    }

    @Test
    fun categoriesEmptyRoundTrip() {
        assertEquals(
            emptyList<ChatCategory>(),
            CategoryStore.deserializeCategories(CategoryStore.serializeCategories(emptyList())),
        )
    }

    @Test
    fun categoriesCorruptInputYieldsEmpty() {
        assertTrue(CategoryStore.deserializeCategories("not json").isEmpty())
        assertTrue(CategoryStore.deserializeCategories("").isEmpty())
        assertTrue(CategoryStore.deserializeCategories("[1,2,3]").isEmpty())
    }

    @Test
    fun categoriesDropBlankEntries() {
        val json = """[{"id":"","name":"x"},{"id":"a","name":""},{"id":"b","name":"Ok"}]"""
        assertEquals(
            listOf(ChatCategory("b", "Ok")),
            CategoryStore.deserializeCategories(json),
        )
    }

    @Test
    fun assignmentsRoundTrip() {
        val map = mapOf("rcs:abc123" to "cat-1", "tel:42" to "cat-2")
        val json = CategoryStore.serializeAssignments(map)
        assertEquals(map, CategoryStore.deserializeAssignments(json))
    }

    @Test
    fun assignmentsCorruptInputYieldsEmpty() {
        assertTrue(CategoryStore.deserializeAssignments("garbage").isEmpty())
        assertTrue(CategoryStore.deserializeAssignments("").isEmpty())
    }

    @Test
    fun conversationKeyPrefersRcsId() {
        assertEquals(
            "rcs:xyz",
            CategoryStore.conversationKey("rcs:xyz", 12345L),
        )
    }

    @Test
    fun conversationKeyFallsBackToThreadId() {
        assertEquals(
            "tel:12345",
            CategoryStore.conversationKey(null, 12345L),
        )
    }

    @Test
    fun conversationKeyStable() {
        // Same inputs must always produce the same key — assignments are
        // keyed on this across restarts.
        assertEquals(
            CategoryStore.conversationKey("rcs:xyz", 999L),
            CategoryStore.conversationKey("rcs:xyz", 111L),
        )
    }
}
