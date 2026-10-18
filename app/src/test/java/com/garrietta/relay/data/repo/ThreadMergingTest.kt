package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure thread merger. These simulate the duplicate-row
 * cases seen on device (notably the "Alec Siurek" two-row case) without any
 * Android framework — mergeCandidates takes pre-resolved candidates.
 * Run on CI via testDebugUnitTest.
 */
class ThreadMergingTest {

    private fun leg(
        threadId: Long,
        address: String,
        contactId: Long? = null,
        messageCount: Int = 5,
        snippet: String = "hello",
        unread: Boolean = false,
        recipients: Set<String>? = null,
    ) = MergeCandidate(
        threadId = threadId,
        address = address,
        recipients = recipients ?: setOf(address.filter { it.isDigit() }),
        date = threadId * 1000L,
        snippet = snippet,
        unread = unread,
        messageCount = messageCount,
        transportLabel = "SMS",
        contactId = contactId,
        displayName = address,
        photoUri = null,
    )

    @Test fun `two legs same contact different numbers merge`() {
        val out = mergeCandidates(
            listOf(
                leg(1, "+15551234567", contactId = 42),
                leg(2, "5559876543", contactId = 42),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(listOf(2L, 1L), out[0].threadIds)
    }

    @Test fun `two different contact ids NEVER merge`() {
        val out = mergeCandidates(
            listOf(
                leg(1, "+15551234567", contactId = 42),
                leg(2, "+15557654321", contactId = 43),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test fun `phantom leg with empty address is hidden`() {
        // The "Mar 23 / No messages" case: an empty-address, zero-message,
        // unresolvable thread is provider noise, not a conversation.
        val out = mergeCandidates(
            listOf(
                leg(1, "", contactId = null, messageCount = 0, snippet = ""),
                leg(2, "+15551234567", contactId = 42),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(2L, out[0].threadId)
    }

    @Test fun `zero-message unresolvable leg is hidden as provider noise`() {
        val out = mergeCandidates(
            listOf(
                leg(1, "+15550001111", contactId = null, messageCount = 0, snippet = ""),
                leg(2, "+15551234567", contactId = 42),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(2L, out[0].threadId)
    }

    @Test fun `zero-message leg WITH a contact is kept and merged`() {
        // An empty leg that resolves to the same contact is a real split
        // (e.g. an MMS leg with no text), not noise — it merges.
        val out = mergeCandidates(
            listOf(
                leg(1, "+15551234567", contactId = 42, messageCount = 0, snippet = ""),
                leg(2, "+15551234567", contactId = 42),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(2, out[0].threadIds.size)
    }

    @Test fun `same normalized address in different formats merges`() {
        val out = mergeCandidates(
            listOf(
                leg(1, "+15551234567"),
                leg(2, "5551234567"),
                leg(3, "15551234567"),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(3, out[0].threadIds.size)
    }

    @Test fun `group threads never merge`() {
        val group = setOf("5551234567", "5559876543")
        val out = mergeCandidates(
            listOf(
                leg(1, "+15551234567", recipients = group),
                leg(2, "+15551234567", recipients = group),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test fun `short codes never merge`() {
        val out = mergeCandidates(
            listOf(leg(1, "12345"), leg(2, "12345")),
        )
        assertEquals(2, out.size)
    }

    @Test fun `unread is OR-ed and message counts summed on merge`() {
        val out = mergeCandidates(
            listOf(
                leg(1, "+15551234567", contactId = 42, messageCount = 3, unread = false),
                leg(2, "+15551234567", contactId = 42, messageCount = 7, unread = true),
            ),
        )
        assertEquals(1, out.size)
        assertTrue(out[0].unread)
        assertEquals(10, out[0].messageCount)
    }

    @Test fun `mergeKey rejects short codes and blanks`() {
        assertEquals(null, mergeKey(""))
        assertEquals(null, mergeKey("12345"))
        assertEquals(null, mergeKey("GOOGLE"))
        assertEquals("5551234567", mergeKey("+15551234567"))
        assertEquals("5551234567", mergeKey("(555) 123-4567"))
    }
}
