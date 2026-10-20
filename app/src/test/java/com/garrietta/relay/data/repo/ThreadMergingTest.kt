package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- Participant integrity (Garrett 2026-10-20) ----

    // ---- No cross-conversation merge (Garrett 2026-10-20 teardown) ----

    @Test fun `two group threads with identical participant sets stay two separate rows`() {
        // The Dad 1:1 vs Dad+Grace shape that broke the old merge: two
        // conversations whose participant sets are IDENTICAL are still
        // two separate chats. The participant-set merge that once
        // combined such rows is REMOVED; the only merger left (provider
        // 1:1 legs) never touches groups. Each row keeps its OWN
        // snippet and its OWN thread id — disjoint message lists.
        val members = setOf("8125550101", "8125550102")
        val out = mergeCandidates(
            listOf(
                leg(1, "+18125550101", recipients = members, snippet = "Hell yeah"),
                leg(2, "+18125550101", recipients = members, snippet = "I'm good with that"),
            ),
        )
        assertEquals(2, out.size)
        val bySnippet = out.associateBy { it.snippet }
        assertEquals(listOf(1L), bySnippet.getValue("Hell yeah").threadIds)
        assertEquals(listOf(2L), bySnippet.getValue("I'm good with that").threadIds)
    }

    @Test fun `a one-to-one thread never absorbs a group thread sharing a participant`() {
        val out = mergeCandidates(
            listOf(
                leg(1, "+18125550101", contactId = 7),
                leg(2, "+18125550101", recipients = setOf("8125550101", "8125550102")),
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.threadIds.size == 1 })
    }

    @Test fun `participant anomaly needs a group claim and one-sided evidence`() {
        // The Dad/Grace shape: list claims 2 others, only Dad ever sends.
        assertTrue(participantAnomaly(claimedOthers = 2, distinctOtherSenders = 1, incomingMessages = 10))
        // Nobody else has spoken yet in the loaded window — still one-sided.
        assertTrue(participantAnomaly(claimedOthers = 3, distinctOtherSenders = 0, incomingMessages = 5))
        // A second other sender HAS spoken: the claim checks out.
        assertFalse(participantAnomaly(claimedOthers = 2, distinctOtherSenders = 2, incomingMessages = 10))
        // Too little message evidence to judge.
        assertFalse(participantAnomaly(claimedOthers = 2, distinctOtherSenders = 1, incomingMessages = 2))
        // A 1:1 claim can never be anomalous.
        assertFalse(participantAnomaly(claimedOthers = 1, distinctOtherSenders = 1, incomingMessages = 10))
        assertFalse(participantAnomaly(claimedOthers = 0, distinctOtherSenders = 0, incomingMessages = 10))
    }
}
