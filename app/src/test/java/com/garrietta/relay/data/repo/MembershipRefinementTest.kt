package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.rcs.proto.RcsMessage
import com.garrietta.relay.rcs.proto.RcsParticipant
import com.garrietta.relay.rcs.proto.cleanRcsParticipantList
import com.garrietta.relay.rcs.proto.deriveRcsMembership
import com.garrietta.relay.rcs.proto.isUnknownSenderGhost
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

    // ---------- the sender union (Garrett 2026-10-20, Game Night) ----
    //
    // Fixtures from his actual Diagnostics membership dump: participant
    // ids and numbers as the server shipped them. 824 = Garrett (isMe),
    // 822 = his unflagged self duplicate.

    private val selfNums = setOf("+17653419588")
    private val gnMe = RcsParticipant("824", "+17653419588", "Garrett", "Garrett Marietta", true)
    private val gnDupe = RcsParticipant("822", "+17653419588", "Garrett", "Garrett Marietta", false)

    /** Game Night (id=187) field 20, verbatim from the dump. */
    private val gameNightParts = listOf(
        gnMe,
        RcsParticipant("109", "+12197768195", "Alec", "Alec Siurek", false),
        RcsParticipant("189", "+16164142008", "Eli", "Eli VanDyke", false),
        RcsParticipant("190", "+13179879200", "Peyton", "Peyton DEAS", false),
        RcsParticipant("191", "+12243152845", "", "", false),
        RcsParticipant("192", "+12066051302", "", "", false),
        RcsParticipant("194", "+13233887100", "", "", false),
        RcsParticipant("595", "+16513347886", "", "", false),
        RcsParticipant("596", "+18562630094", "", "", false),
        RcsParticipant("597", "+19899166349", "", "", false),
        gnDupe,
    )
    private val gameNightF21 = listOf("109", "189", "190", "191")

    /** "Dad, Haley" (id=46) field 20, verbatim from the dump. */
    private val group46Parts = listOf(
        gnMe,
        RcsParticipant("25", "+17653182838", "Dad", "Dad", false),
        RcsParticipant("67", "+18125937679", "Haley", "Haley Marietta", false),
        RcsParticipant("74", "+18122017121", "Grace", "Grace Ambrose", false),
        gnDupe,
    )

    private fun cleanedPool(parts: List<RcsParticipant>) =
        cleanRcsParticipantList(parts, selfNums)
            .filterNot { it.isUnknownSenderGhost() }

    private fun msg(pid: String, conv: String = "187") = RcsMessage(
        id = "m-$pid-${counter++}", conversationId = conv, body = "x",
        timestampMs = 1L, fromMe = false, status = 100, tmpId = "",
        participantId = pid,
    )

    private var counter = 0

    private fun rowFor(id: String, name: String, parts: List<RcsParticipant>, group: Boolean) =
        Conversation(
            threadId = 1L, address = parts.firstOrNull()?.number ?: "",
            displayName = name, snippet = "", date = 3_000L, unread = false,
            messageCount = 0, transportLabel = "RCS", photoUri = null,
            rcsConversationId = id,
            participants = parts.map {
                Participant(it.number, it.fullName.ifBlank { it.firstName }.ifBlank { it.number }, null)
            },
            isGroup = group,
        )

    @Test
    fun `union gate is sealed inside the collapse shape only`() {
        // The Dad shape (no field 21, not a group): sender evidence
        // SELECTS the peer there; the union must never fire.
        assertFalse(senderUnionGoverns(field21Present = false, serverIsGroup = false))
        assertTrue(senderUnionGoverns(field21Present = true, serverIsGroup = false))
        assertTrue(senderUnionGoverns(field21Present = false, serverIsGroup = true))
        assertTrue(senderUnionGoverns(field21Present = true, serverIsGroup = true))
    }

    @Test
    fun `game night - field 21 subset grows to every evidenced sender`() {
        val membership = deriveRcsMembership(
            gameNightParts, gameNightF21, selfNums,
            serverIsGroup = true, conversationName = "Game Night",
        )
        // The derivation alone renders only the field-21 four.
        assertEquals(
            listOf("109", "189", "190", "191"),
            membership.others.map { it.participantId },
        )
        // History: the five field-21-EXCLUDED bare numbers all send,
        // alongside two of the derived four; one pid (999) resolves to
        // no field-20 entry at all.
        val byPid = gameNightParts.associateBy { it.participantId }
        val pool = cleanedPool(gameNightParts)
        val incoming = listOf(
            msg("190"), msg("190"), msg("190"),
            msg("109"), msg("109"),
            msg("192"), msg("192"), msg("192"), msg("192"),
            msg("194"),
            msg("595"), msg("595"),
            msg("596"),
            msg("597"), msg("597"), msg("597"), msg("597"), msg("597"),
            msg("999"), msg("999"),
            msg("824"), // self: resolved, but not an "other" sender
        )
        val census = computeSenderCensus(
            incoming, byPid, pool.map { it.participantId }.toSet(),
        )
        assertEquals(3, census.sentByPid["190"])
        assertEquals(4, census.sentByPid["192"])
        assertEquals(setOf("999"), census.unmatchedPids)
        assertEquals(2, census.unmatchedMessages)
        assertFalse("824" in census.sentByPid)

        val additions = unionAdditionEntries(
            pool, census.sentByPid.keys,
            membership.others.map { it.participantId }.toSet(),
        )
        // Exactly the five excluded bare numbers join; the unmatched
        // pid fabricates nothing, and 189 (derived, silent) was never
        // at risk — membership only grows by evidence.
        assertEquals(
            listOf("192", "194", "595", "596", "597"),
            additions.map { it.participantId },
        )

        val refinements = MembershipRefinements()
        refinements.recordUnion(
            "187",
            additions.map { e ->
                MembershipRefinements.RefinedPeer(
                    e,
                    Participant(
                        e.number,
                        e.fullName.ifBlank { e.firstName }.ifBlank { e.number },
                        null,
                    ),
                )
            },
        )
        val row = rowFor("187", "Game Night", membership.others, group = true)
        val out = refinements.applyTo(listOf(row)).single()
        assertEquals(9, out.participants.size)
        assertEquals(
            setOf(
                "+12197768195", "+16164142008", "+13179879200", "+12243152845",
                "+12066051302", "+13233887100", "+16513347886", "+18562630094",
                "+19899166349",
            ),
            out.participants.map { it.address }.toSet(),
        )
        assertTrue(out.isGroup)
    }

    @Test
    fun `group 46 - silent field 20 entry never joins by union`() {
        val membership = deriveRcsMembership(
            group46Parts, listOf("25", "67"), selfNums,
            serverIsGroup = true, conversationName = "Dad, Haley Marietta",
        )
        assertEquals(listOf("25", "67"), membership.others.map { it.participantId })
        // Dad and Haley send; Grace (74) is in field 20 but silent.
        val pool = cleanedPool(group46Parts)
        val additions = unionAdditionEntries(
            pool, setOf("25", "67"),
            membership.others.map { it.participantId }.toSet(),
        )
        assertTrue(additions.isEmpty())
        // And the retirement coverage reads true for this evidence:
        // the derivation already covers every evidenced sender.
        assertTrue(
            unionCoveredByDerivation(
                setOf("25", "67"),
                membership.others.map { it.participantId }.toSet(),
            ),
        )
    }

    @Test
    fun `dad shape - census exists but the union cannot fire inside the collapse`() {
        // Even with conclusive sender evidence (19 incoming, all Dad),
        // the NO-F21 + not-a-group shape routes evidence to the peer
        // decision — union additions are never even computed there
        // because the gate is closed.
        val dadParts = listOf(
            gnMe,
            RcsParticipant("25", "+17653182838", "Dad", "Dad", false),
            RcsParticipant("74", "+18122017121", "Grace", "Grace Ambrose", false),
            gnDupe,
        )
        val membership = deriveRcsMembership(
            dadParts, emptyList(), selfNums,
            serverIsGroup = false, conversationName = "Dad",
        )
        assertEquals(listOf("25"), membership.others.map { it.participantId })
        assertFalse(membership.isGroup)
        assertFalse(
            senderUnionGoverns(
                field21Present = false,
                serverIsGroup = membership.isGroup,
            ),
        )
    }

    @Test
    fun `union retires when a later payload covers the senders`() {
        val refinements = MembershipRefinements()
        val extra = RcsParticipant("192", "+12066051302", "", "", false)
        refinements.recordUnion(
            "187",
            listOf(
                MembershipRefinements.RefinedPeer(
                    extra, Participant(extra.number, extra.number, null),
                ),
            ),
        )
        assertTrue(refinements.isUnionRecorded("187"))
        // The payload catches up: field 21 now names 192, so the
        // derivation covers the evidenced sender and the repository
        // retires the record (the coverage decision it consults):
        assertTrue(
            unionCoveredByDerivation(
                setOf("109", "192"),
                setOf("109", "189", "190", "191", "192"),
            ),
        )
        assertFalse(
            unionCoveredByDerivation(
                setOf("109", "192"),
                setOf("109", "189", "190", "191"),
            ),
        )
        refinements.clearUnion("187")
        assertFalse(refinements.isUnionRecorded("187"))
        val rows = listOf(provisionalRow())
        assertSame(rows, refinements.applyTo(rows))
    }

    @Test
    fun `union applyTo absorbs additions the row already carries`() {
        // Partial coverage: a refresh's derivation picked up two of
        // the five additions (field 21 grew); applyTo adds only the
        // three still missing — no duplicates.
        val refinements = MembershipRefinements()
        val additions = listOf("192", "194", "595", "596", "597").map { pid ->
            val num = "+1" + pid.padStart(10, '0')
            MembershipRefinements.RefinedPeer(
                RcsParticipant(pid, num, "", "", false),
                Participant(num, num, null),
            )
        }
        refinements.recordUnion("187", additions)
        val alreadyThere = listOf(
            Participant("+12197768195", "Alec Siurek", null),
            Participant("+10000000192", "+10000000192", null),
            Participant("+10000000194", "+10000000194", null),
        )
        val row = rowFor("187", "Game Night", emptyList(), group = true)
            .copy(participants = alreadyThere)
        val out = refinements.applyTo(listOf(row)).single()
        assertEquals(6, out.participants.size)
        assertEquals(
            1, out.participants.count { it.address == "+10000000192" },
        )
        assertTrue(out.isGroup)
        // A second application is a no-op (same instances back out).
        val again = refinements.applyTo(listOf(out))
        assertSame(out, again.single())
    }

    @Test
    fun `union grows a one-other derivation into a group`() {
        // The coherence case: field 21 names ONE other, but a second
        // person actively sends — membership is the union, and the
        // grown count makes it a group.
        val refinements = MembershipRefinements()
        val third = RcsParticipant("88", "+15550008800", "Third", "Third Person", false)
        refinements.recordUnion(
            "55",
            listOf(
                MembershipRefinements.RefinedPeer(
                    third, Participant(third.number, "Third Person", null),
                ),
            ),
        )
        val row = rowFor(
            "55", "Duo",
            listOf(RcsParticipant("25", "+17653182838", "Dad", "Dad", false)),
            group = false,
        )
        val out = refinements.applyTo(listOf(row)).single()
        assertEquals(2, out.participants.size)
        assertTrue(out.isGroup)
    }

    @Test
    fun `census merges monotonically across loads`() {
        val a = SenderCensus(mapOf("190" to 3, "192" to 1), setOf("999"), 1)
        val b = SenderCensus(mapOf("190" to 2, "595" to 5), setOf("998"), 3)
        val merged = a.mergedWith(b)
        // Overlapping loads re-read the same messages: max, not sum.
        assertEquals(mapOf("190" to 3, "192" to 1, "595" to 5), merged.sentByPid)
        assertEquals(setOf("998", "999"), merged.unmatchedPids)
        assertEquals(3, merged.unmatchedMessages)
    }
}
