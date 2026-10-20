package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.rcs.proto.RcsParticipant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the sender-evidence membership refinement
 * (Garrett 2026-10-20, the Dad fix): a field-21-absent, not-a-group
 * conversation whose own messages conclusively name one sender is
 * refined to that sender on the stored row, and the refinement rides
 * refreshes. Pure JVM.
 * Run on CI via testDebugUnitTest.
 */
class MembershipRefinementTest {

    private val gracePeer = Participant(
        address = "+18122017121", displayName = "Grace Ambrose", photoUri = null,
    )
    private val dadPeer = Participant(
        address = "+17653182838", displayName = "Dad", photoUri = null,
    )
    private val dadEntry = RcsParticipant(
        "25", "+17653182838", "Dad", "Dad", false,
    )

    /** The Dad row as the derivation leaves it when the name can't
     *  resolve: provisional first cleaned other (Grace), not a group. */
    private fun provisionalRow() = Conversation(
        threadId = 34L,
        address = gracePeer.address,
        displayName = "Dad",
        snippet = "Hell yeah",
        date = 2_000L,
        unread = false,
        messageCount = 0,
        transportLabel = "RCS",
        photoUri = null,
        rcsConversationId = "34",
        participants = listOf(gracePeer),
        isGroup = false,
    )

    @Test
    fun `sender evidence refines the row to the actual sender`() {
        // History showed one distinct sender (Dad) who is NOT the
        // provisional pick: the recorded refinement rewrites the row.
        val refinements = MembershipRefinements()
        refinements.record("34", dadEntry, dadPeer)
        val out = refinements.applyTo(listOf(provisionalRow())).single()
        assertEquals(listOf(dadPeer), out.participants)
        assertFalse(out.isGroup)
        assertEquals(dadPeer.address, out.address)
        assertTrue(refinements.isRecorded("34"))
        assertEquals(dadEntry, refinements.peerEntry("34"))
    }

    @Test
    fun `refinement rides refreshes and leaves other rows untouched`() {
        val refinements = MembershipRefinements()
        refinements.record("34", dadEntry, dadPeer)
        val other = provisionalRow().copy(
            threadId = 99L, rcsConversationId = "99",
        )
        repeat(2) {
            val out = refinements.applyTo(listOf(provisionalRow(), other))
            assertEquals(listOf(dadPeer), out[0].participants)
            assertSame(other, out[1])
        }
    }

    @Test
    fun `no record means no rewrite`() {
        val refinements = MembershipRefinements()
        val rows = listOf(provisionalRow())
        assertSame(rows, refinements.applyTo(rows))
    }

    @Test
    fun `clear retires the refinement`() {
        val refinements = MembershipRefinements()
        refinements.record("34", dadEntry, dadPeer)
        refinements.clear("34")
        assertFalse(refinements.isRecorded("34"))
        val rows = listOf(provisionalRow())
        assertSame(rows, refinements.applyTo(rows))
    }

    // ---------- the pure decision: senderEvidencePeerNumber ----------

    @Test
    fun `decision fires for one distinct sender with enough incoming`() {
        assertEquals(
            "17653182838",
            senderEvidencePeerNumber(
                field21Present = false, serverIsGroup = false,
                incomingCount = 19,
                distinctSenderNumbers = listOf("17653182838"),
            ),
        )
    }

    @Test
    fun `decision refuses two or more distinct senders`() {
        // The task's negative case: history with 2+ distinct senders
        // is a real multi-party history — no refinement.
        assertNull(
            senderEvidencePeerNumber(
                field21Present = false, serverIsGroup = false,
                incomingCount = 19,
                distinctSenderNumbers = listOf("17653182838", "18122017121"),
            ),
        )
    }

    @Test
    fun `decision refuses when field 21 is present or the server flags a group`() {
        assertNull(
            senderEvidencePeerNumber(
                field21Present = true, serverIsGroup = false,
                incomingCount = 19,
                distinctSenderNumbers = listOf("17653182838"),
            ),
        )
        assertNull(
            senderEvidencePeerNumber(
                field21Present = false, serverIsGroup = true,
                incomingCount = 19,
                distinctSenderNumbers = listOf("17653182838"),
            ),
        )
    }

    @Test
    fun `decision refuses thin or senderless history`() {
        assertNull(
            senderEvidencePeerNumber(
                field21Present = false, serverIsGroup = false,
                incomingCount = 2,
                distinctSenderNumbers = listOf("17653182838"),
            ),
        )
        assertNull(
            senderEvidencePeerNumber(
                field21Present = false, serverIsGroup = false,
                incomingCount = 19,
                distinctSenderNumbers = emptyList(),
            ),
        )
    }
}
