package com.garrietta.relay.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the missed-message backlog behind conversation
 * notifications (Garrett 2026-10-20: the shade must show ALL missed
 * messages, not just the latest). Covers the pure rules: append order
 * (oldest → newest), per-conversation isolation, cap eviction
 * (oldest drops first), clear-on-read with no resurrection, group
 * sender attribution surviving the round trip, and the notification
 * body placeholders (media / location / fallback). Pure JVM.
 */
class MissedMessagesTest {

    private fun msg(
        id: String,
        sender: String = "Mom",
        number: String = "+15550001",
        body: String = "hello $id",
        ts: Long = id.hashCode().toLong(),
    ) = MissedMessage(
        messageId = id,
        senderName = sender,
        senderNumber = number,
        body = body,
        timestampMs = ts,
    )

    // ---------- append order ----------

    @Test
    fun `appends accumulate oldest to newest`() {
        val store = MissedMessageStore()
        store.append("conv", msg("1", ts = 100))
        store.append("conv", msg("2", ts = 200))
        val snapshot = store.append("conv", msg("3", ts = 300))
        assertEquals(listOf("1", "2", "3"), snapshot.map { it.messageId })
        assertEquals(snapshot, store.missedFor("conv"))
    }

    @Test
    fun `snapshot returned by append is a copy, not live state`() {
        val store = MissedMessageStore()
        val first = store.append("conv", msg("1"))
        store.append("conv", msg("2"))
        assertEquals(1, first.size)
        assertEquals(2, store.missedFor("conv").size)
    }

    // ---------- per-conversation isolation ----------

    @Test
    fun `conversations accumulate independently`() {
        val store = MissedMessageStore()
        store.append("a", msg("a1"))
        store.append("b", msg("b1"))
        store.append("a", msg("a2"))
        assertEquals(listOf("a1", "a2"), store.missedFor("a").map { it.messageId })
        assertEquals(listOf("b1"), store.missedFor("b").map { it.messageId })
    }

    @Test
    fun `unknown conversation has an empty backlog`() {
        assertTrue(MissedMessageStore().missedFor("nope").isEmpty())
    }

    // ---------- cap eviction ----------

    @Test
    fun `cap evicts the oldest and keeps the most recent`() {
        val store = MissedMessageStore(cap = 3)
        for (i in 1..5) store.append("conv", msg("$i", ts = i.toLong()))
        val backlog = store.missedFor("conv")
        assertEquals(3, backlog.size)
        assertEquals(listOf("3", "4", "5"), backlog.map { it.messageId })
    }

    @Test
    fun `default cap is 15`() {
        val store = MissedMessageStore()
        for (i in 1..20) store.append("conv", msg("$i", ts = i.toLong()))
        val backlog = store.missedFor("conv")
        assertEquals(MissedMessageStore.MAX_MISSED_MESSAGES, backlog.size)
        assertEquals("6", backlog.first().messageId)
        assertEquals("20", backlog.last().messageId)
    }

    // ---------- clear seams ----------

    @Test
    fun `clear drops the backlog and only that conversation`() {
        val store = MissedMessageStore()
        store.append("a", msg("a1"))
        store.append("b", msg("b1"))
        store.clear("a")
        assertTrue(store.missedFor("a").isEmpty())
        assertEquals(listOf("b1"), store.missedFor("b").map { it.messageId })
    }

    @Test
    fun `clearing an unknown conversation is a no-op`() {
        val store = MissedMessageStore()
        store.append("a", msg("a1"))
        store.clear("nope")
        assertEquals(1, store.missedFor("a").size)
    }

    @Test
    fun `append after clear starts a fresh backlog, nothing resurrects`() {
        val store = MissedMessageStore()
        store.append("conv", msg("old1"))
        store.append("conv", msg("old2"))
        store.clear("conv")
        val snapshot = store.append("conv", msg("new1"))
        assertEquals(listOf("new1"), snapshot.map { it.messageId })
    }

    // ---------- group sender attribution ----------

    @Test
    fun `each message keeps its own sender for group attribution`() {
        val store = MissedMessageStore()
        store.append("group", msg("1", sender = "Mom", number = "+15550001"))
        store.append("group", msg("2", sender = "Haley Marietta", number = "+15550002"))
        store.append("group", msg("3", sender = "Mom", number = "+15550001"))
        val backlog = store.missedFor("group")
        assertEquals(
            listOf("Mom", "Haley Marietta", "Mom"),
            backlog.map { it.senderName },
        )
        assertEquals(
            listOf("+15550001", "+15550002", "+15550001"),
            backlog.map { it.senderNumber },
        )
    }

    // ---------- body placeholders ----------

    @Test
    fun `real text passes through untouched`() {
        assertEquals(
            "Running late",
            notificationBodyText("Running late", hasMedia = false, hasLocation = false, locationName = null),
        )
        assertEquals(
            "Running late",
            notificationBodyText("Running late", hasMedia = true, hasLocation = false, locationName = null),
        )
    }

    @Test
    fun `blank body with media reads as the inbox photo placeholder`() {
        assertEquals(
            "\uD83D\uDCF7 Photo",
            notificationBodyText("", hasMedia = true, hasLocation = false, locationName = null),
        )
        assertEquals(
            "\uD83D\uDCF7 Photo",
            notificationBodyText("   ", hasMedia = true, hasLocation = true, locationName = "Home"),
        )
    }

    @Test
    fun `blank body with a location names the pin`() {
        assertEquals(
            "\uD83D\uDCCD Home",
            notificationBodyText("", hasMedia = false, hasLocation = true, locationName = "Home"),
        )
        assertEquals(
            "\uD83D\uDCCD Location",
            notificationBodyText("", hasMedia = false, hasLocation = true, locationName = null),
        )
        assertEquals(
            "\uD83D\uDCCD Location",
            notificationBodyText("", hasMedia = false, hasLocation = true, locationName = "  "),
        )
    }

    @Test
    fun `blank body with nothing falls back to the generic label`() {
        assertEquals(
            "New message",
            notificationBodyText("", hasMedia = false, hasLocation = false, locationName = null),
        )
    }
}
