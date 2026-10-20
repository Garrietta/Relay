package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Diagnostics membership dump (Garrett 2026-10-20,
 * Dad-chat deep dive): selection must surface the conversations that
 * adjudicate a membership bug (anomaly-flagged, or field 20 / field 21
 * disagreement) ahead of the merely recent, and the formatting must
 * print the raw wire fields verbatim next to the derived result.
 */
class MembershipDumpTest {

    private val me = MembershipEntry("p-me", "+15550001111", true, "Me")
    private val dad = MembershipEntry("p-dad", "+15550002222", false, "Dad")
    private val grace = MembershipEntry("p-grace", "+15550003333", false, "Grace Ambrose")

    private fun snap(
        id: String,
        title: String,
        ts: Long,
        field21: List<String>,
        field20: List<MembershipEntry>,
        derived: List<MembershipEntry>,
        anomaly: Boolean = false,
    ) = MembershipSnapshot(
        conversationId = id,
        title = title,
        timestampMs = ts,
        type = 2,
        serverIsGroup = derived.size > 1,
        field21 = field21,
        field20 = field20,
        derivedOthers = derived,
        derivedIsGroup = derived.size > 1,
        anomaly = anomaly,
    )

    @Test fun `field sets mismatch only when field 21 present and disagreeing`() {
        // Field 21 names only Dad while field 20 still lists Grace:
        // the two server fields disagree — mismatch.
        val dadChat = snap(
            "c-dad", "Dad", 100,
            field21 = listOf("p-dad"),
            field20 = listOf(me, dad, grace),
            derived = listOf(dad),
        )
        assertTrue(dadChat.fieldSetsMismatch)
        assertFalse(dadChat.field21Absent)
        assertTrue(dadChat.marked)

        // Field 21 absent: NO mismatch flag (nothing to disagree with)
        // — the NO-F21 state is reported separately instead.
        val noF21 = snap(
            "c-x", "X", 100,
            field21 = emptyList(),
            field20 = listOf(me, dad, grace),
            derived = listOf(dad, grace),
        )
        assertFalse(noF21.fieldSetsMismatch)
        assertTrue(noF21.field21Absent)
        assertFalse(noF21.marked)

        // Field 21 agrees with field 20: unmarked.
        val group = snap(
            "c-g", "Dad, Grace", 100,
            field21 = listOf("p-dad", "p-grace"),
            field20 = listOf(me, dad, grace),
            derived = listOf(dad, grace),
        )
        assertFalse(group.fieldSetsMismatch)
        assertFalse(group.marked)
    }

    @Test fun `selection puts marked first and never cuts them`() {
        val plain = (1..40).map { i ->
            snap(
                "c-$i", "Chat $i", ts = i.toLong(),
                field21 = listOf("p-dad"),
                field20 = listOf(me, dad),
                derived = listOf(dad),
            )
        }
        val anomaly = snap(
            "c-dad", "Dad", ts = 1,
            field21 = listOf("p-dad", "p-grace"),
            field20 = listOf(me, dad, grace),
            derived = listOf(dad, grace),
            anomaly = true,
        )
        val mismatch = snap(
            "c-m", "Mismatch", ts = 2,
            field21 = listOf("p-dad"),
            field20 = listOf(me, dad, grace),
            derived = listOf(dad),
        )
        val selected = selectMembershipSnapshots(plain + anomaly + mismatch, cap = 30)
        // Both marked survive despite the cap and their old timestamps
        // (marked block itself is newest-first: c-m ts=2, c-dad ts=1).
        assertEquals(listOf("c-m", "c-dad"), selected.take(2).map { it.conversationId })
        assertEquals(30, selected.size)
        // The unmarked remainder is newest-first.
        assertEquals("c-40", selected[2].conversationId)
    }

    @Test fun `format prints raw fields derived result and detector inputs`() {
        val dadChat = MembershipSnapshot(
            conversationId = "conv-dad-1",
            title = "Dad",
            timestampMs = 100,
            type = 2,
            serverIsGroup = true,
            field21 = listOf("p-dad", "p-grace"),
            field20 = listOf(me, dad, grace),
            derivedOthers = listOf(dad, grace),
            derivedIsGroup = true,
            anomaly = true,
            detectorClaimedOthers = 2,
            detectorDistinctSenders = 1,
            detectorIncoming = 14,
        )
        val text = formatMembershipDump(listOf(dadChat), totalConversations = 194)
        assertTrue(text.contains("1 of 194"))
        assertTrue(text.contains("\"Dad\" [ANOMALY]"))
        assertTrue(text.contains("id=conv-dad-1"))
        assertTrue(text.contains("type(22)=2 isGroup(10)=true derivedGroup=true"))
        assertTrue(text.contains("f21(2): p-dad, p-grace"))
        assertTrue(text.contains("id=p-grace num=+15550003333 name=Grace Ambrose"))
        assertTrue(text.contains("id=p-me num=+15550001111 name=Me isMe"))
        assertTrue(text.contains("derivedOthers(2): p-dad:+15550002222, p-grace:+15550003333"))
        assertTrue(text.contains("detector: claimedOthers=2 distinctSenders=1 incoming=14 -> ANOMALY"))
    }

    @Test fun `format prints SmallInfo types sent counts and the census line`() {
        // The Game Night adjudication (Garrett 2026-10-20): field 21
        // excludes Grace here, yet her entry carries a sent count —
        // the excluded entries are the ones who actually send, which
        // is what proved field 21 a subset rather than the roster.
        val withCensus = MembershipSnapshot(
            conversationId = "c-gn",
            title = "Game Night",
            timestampMs = 100,
            type = 2,
            serverIsGroup = true,
            field21 = listOf("p-dad"),
            field20 = listOf(
                me.copy(infoType = 1),
                dad.copy(infoType = 2, sentCount = 5),
                grace.copy(infoType = 2, sentCount = 3),
            ),
            derivedOthers = listOf(dad, grace),
            derivedIsGroup = true,
            anomaly = false,
            senderCensus = SenderCensus(
                sentByPid = mapOf("p-dad" to 5, "p-grace" to 3),
                unmatchedPids = setOf("p-stranger"),
                unmatchedMessages = 2,
            ),
        )
        val text = formatMembershipDump(listOf(withCensus), totalConversations = 1)
        assertTrue(text.contains("id=p-me num=+15550001111 name=Me isMe type=1"))
        assertTrue(text.contains("id=p-dad num=+15550002222 name=Dad type=2 sent=5"))
        assertTrue(text.contains("id=p-grace num=+15550003333 name=Grace Ambrose type=2 sent=3"))
        assertTrue(
            text.contains(
                "census: otherSenders=2 unmatchedPids=1 unmatchedMsgs=2 " +
                    "(from loaded history)",
            ),
        )
        // No census recorded (chat never opened): no census line, and
        // entries carry a type but no sent marker.
        val bare = formatMembershipDump(
            listOf(snap("c-b", "Bare", 1, listOf("p-dad"), listOf(me, dad), listOf(dad))),
            totalConversations = 1,
        )
        assertFalse(bare.contains("census:"))
        assertTrue(bare.contains("type=0"))
        assertFalse(bare.contains("sent="))
    }

    @Test fun `format marks a missing field 21 as NO-F21 with absent list`() {
        val s = snap(
            "c-x", "X", ts = 5,
            field21 = emptyList(),
            field20 = listOf(me, dad, grace),
            derived = listOf(dad, grace),
        )
        val text = formatMembershipDump(listOf(s), totalConversations = 1)
        assertTrue(text.contains("\"X\" [NO-F21]"))
        assertTrue(text.contains("f21(0): (absent)"))
        // No message load has run for this conversation: no detector line.
        assertFalse(text.contains("detector:"))
    }
}
