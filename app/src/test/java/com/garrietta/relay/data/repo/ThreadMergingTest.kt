package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.model.allRcsIds
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

    // ---- Duplicate participant-set merge (Garrett 2026-10-20) ----

    private fun row(
        name: String,
        date: Long,
        snippet: String = "hi",
        unread: Boolean = false,
        messageCount: Int = 0,
        transportLabel: String = "RCS",
        rcsId: String? = null,
        threadIds: List<Long> = emptyList(),
        members: List<Pair<String, String>>,
    ) = Conversation(
        threadId = ("rcs:" + (rcsId ?: name)).hashCode().toLong(),
        address = members.firstOrNull()?.first ?: "",
        displayName = name,
        snippet = snippet,
        date = date,
        unread = unread,
        messageCount = messageCount,
        transportLabel = transportLabel,
        photoUri = null,
        threadIds = threadIds,
        rcsConversationId = rcsId,
        participants = members.map { (addr, disp) ->
            Participant(address = addr, displayName = disp, photoUri = null)
        },
        isGroup = members.size > 1,
    )

    @Test fun `dad grace duplicate server rows merge into one`() {
        // Garrett's device evidence: a "Dad" row (participants Dad +
        // Grace) and a "Dad, Grace Ambrose" row — same participant set.
        val dadOnly = row(
            name = "Dad", date = 100L, snippet = "Hell yeah", rcsId = "convA",
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val named = row(
            name = "Dad, Grace Ambrose", date = 200L,
            snippet = "I'm good with that", rcsId = "convB", unread = true,
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val out = mergeDuplicateConversations(listOf(dadOnly, named))
        assertEquals(1, out.size)
        val merged = out[0]
        // Canonical = most recently active server conversation.
        assertEquals("convB", merged.rcsConversationId)
        assertEquals(listOf("convB", "convA"), merged.allRcsIds())
        // Snippet/date from the newest message overall.
        assertEquals("I'm good with that", merged.snippet)
        assertEquals(200L, merged.date)
        assertTrue(merged.unread)
        assertTrue(merged.isGroup)
        assertEquals(2, merged.participants.size)
    }

    @Test fun `subset participant sets never merge`() {
        val one = row(
            name = "Dad", date = 100L, rcsId = "a",
            members = listOf("+18125550101" to "Dad"),
        )
        val two = row(
            name = "Dad, Grace Ambrose", date = 200L, rcsId = "b",
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        assertEquals(2, mergeDuplicateConversations(listOf(one, two)).size)
    }

    @Test fun `different participant sets never merge`() {
        val a = row(
            name = "A, B", date = 100L, rcsId = "a",
            members = listOf("+18125550101" to "A", "+18125550102" to "B"),
        )
        val b = row(
            name = "A, C", date = 200L, rcsId = "b",
            members = listOf("+18125550101" to "A", "+18125550103" to "C"),
        )
        assertEquals(2, mergeDuplicateConversations(listOf(a, b)).size)
    }

    @Test fun `address format variants of the same set merge`() {
        val a = row(
            name = "Dad, Grace Ambrose", date = 100L, rcsId = "a",
            members = listOf("+18125550101" to "Dad", "8125550102" to "Grace Ambrose"),
        )
        val b = row(
            name = "Dad, Grace Ambrose", date = 200L, rcsId = "b",
            members = listOf("18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val out = mergeDuplicateConversations(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals(listOf("b", "a"), out[0].allRcsIds())
    }

    @Test fun `short code rows never merge`() {
        val a = row(
            name = "22395", date = 100L, rcsId = "a",
            members = listOf("22395" to "22395"),
        )
        val b = row(
            name = "22395", date = 200L, rcsId = "b",
            members = listOf("22395" to "22395"),
        )
        assertEquals(2, mergeDuplicateConversations(listOf(a, b)).size)
    }

    @Test fun `canonical prefers server RCS row over telephony row`() {
        val tel = row(
            name = "Dad, Grace Ambrose", date = 300L, rcsId = null,
            transportLabel = "SMS", threadIds = listOf(7L),
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val rcs = row(
            name = "Dad, Grace Ambrose", date = 100L, rcsId = "srv",
            transportLabel = "RCS",
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val out = mergeDuplicateConversations(listOf(tel, rcs))
        assertEquals(1, out.size)
        // Server id wins routing even though the Telephony row is newer;
        // snippet/date still come from the newest message overall.
        assertEquals("srv", out[0].rcsConversationId)
        assertEquals(listOf("srv"), out[0].allRcsIds())
        assertEquals(300L, out[0].date)
        assertTrue(out[0].threadIds.contains(7L))
    }

    @Test fun `telephony group rows merge and union thread ids`() {
        val a = row(
            name = "Dad, Grace Ambrose", date = 100L, rcsId = null,
            transportLabel = "MMS", threadIds = listOf(11L), messageCount = 3,
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val b = row(
            name = "Dad, Grace Ambrose", date = 200L, rcsId = null,
            transportLabel = "MMS", threadIds = listOf(12L), messageCount = 4,
            members = listOf("+18125550101" to "Dad", "+18125550102" to "Grace Ambrose"),
        )
        val out = mergeDuplicateConversations(listOf(a, b))
        assertEquals(1, out.size)
        assertEquals(listOf(11L, 12L), out[0].threadIds.sorted())
        assertEquals(7, out[0].messageCount)
        assertEquals(null, out[0].rcsConversationId)
        assertEquals(emptyList<String>(), out[0].allRcsIds())
    }

    @Test fun `fullest participant list provides the display fields`() {
        val thin = row(
            name = "Dad", date = 300L, rcsId = "a",
            members = listOf(
                "+18125550101" to "+18125550101",
                "+18125550102" to "+18125550102",
            ),
        )
        val full = row(
            name = "Dad, Grace Ambrose", date = 100L, rcsId = "b",
            members = listOf(
                "+18125550101" to "Dad",
                "+18125550102" to "Grace Ambrose",
            ),
        )
        val out = mergeDuplicateConversations(listOf(thin, full))
        assertEquals(1, out.size)
        assertEquals("Dad, Grace Ambrose", out[0].displayName)
        assertEquals("Dad", out[0].participants[0].displayName)
    }

    // ---- Participant integrity (Garrett 2026-10-20) ----

    @Test fun `group row with empty participant list never merges`() {
        // A group that cannot name its members from its OWN payload
        // must not key on its fallback address and merge into a real
        // 1:1 with that person.
        fun headlessGroup(id: String, date: Long) = Conversation(
            threadId = ("rcs:" + id).hashCode().toLong(),
            address = "+18125550101",
            displayName = "Dad, Grace Ambrose",
            snippet = "hi",
            date = date,
            unread = false,
            messageCount = 3,
            transportLabel = "RCS",
            photoUri = null,
            rcsConversationId = id,
            participants = emptyList(),
            isGroup = true,
        )
        val dadChat = row(
            name = "Dad", date = 300L, rcsId = "dad1to1",
            members = listOf("+18125550101" to "Dad"),
        )
        assertEquals(null, participantSetKey(headlessGroup("g1", 100L)))
        val out = mergeDuplicateConversations(
            listOf(headlessGroup("g1", 100L), headlessGroup("g2", 200L), dadChat),
        )
        assertEquals(3, out.size)
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
