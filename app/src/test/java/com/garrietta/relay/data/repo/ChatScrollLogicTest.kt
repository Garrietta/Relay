package com.garrietta.relay.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the chat scroll decisions (Garrett 2026-10-20): a chat
 * whose tail grew after the scroll position was saved must open at the
 * NEW bottom; an unchanged chat restores the exact saved spot; his own
 * send always scrolls to the bottom; an incoming arrival pins only
 * when he's already at the bottom. Pure JVM — positions are plain
 * [ScrollPosition] values. Run on CI via testDebugUnitTest.
 */
class ChatScrollLogicTest {

    // ---------- position persistence ----------

    @Test
    fun `legacy two-field position parses with unknown newest`() {
        val parsed = ChatScrollLogic.parsePosition("12345:64")
        assertEquals(ScrollPosition(12345L, 64, -1L, -1L), parsed)
    }

    @Test
    fun `current four-field position round-trips`() {
        val position = ScrollPosition(
            messageId = 4242L,
            scrollOffset = 12,
            newestMessageId = 7777L,
            newestMessageDate = 1_700_000_000_000L,
        )
        val parsed = ChatScrollLogic.parsePosition(
            ChatScrollLogic.formatPosition(position),
        )
        assertEquals(position, parsed)
    }

    @Test
    fun `negative ids round-trip (RCS ids are hashCodes)`() {
        val position = ScrollPosition(
            messageId = -987654321L,
            scrollOffset = 0,
            newestMessageId = -123456789L,
            newestMessageDate = 1_700_000_000_000L,
        )
        val parsed = ChatScrollLogic.parsePosition(
            ChatScrollLogic.formatPosition(position),
        )
        assertEquals(position, parsed)
    }

    @Test
    fun `malformed positions are rejected`() {
        assertNull(ChatScrollLogic.parsePosition(""))
        assertNull(ChatScrollLogic.parsePosition("abc"))
        assertNull(ChatScrollLogic.parsePosition("1:2:3"))
        assertNull(ChatScrollLogic.parsePosition("1:2:3:4:5"))
        assertNull(ChatScrollLogic.parsePosition("1:x"))
        assertNull(ChatScrollLogic.parsePosition("1:2:x:4"))
    }

    // ---------- open-time: hasNewerThanSaved ----------

    @Test
    fun `restore with no newer message keeps the saved position`() {
        // Left mid-history; the newest message is unchanged since.
        val saved = ScrollPosition(
            messageId = 100L, scrollOffset = 24,
            newestMessageId = 200L, newestMessageDate = 5_000L,
        )
        assertFalse(ChatScrollLogic.hasNewerThanSaved(saved, 200L, 5_000L))
    }

    @Test
    fun `newer message present opens at the bottom`() {
        val saved = ScrollPosition(
            messageId = 100L, scrollOffset = 0,
            newestMessageId = 200L, newestMessageDate = 5_000L,
        )
        assertTrue(ChatScrollLogic.hasNewerThanSaved(saved, 201L, 6_000L))
    }

    @Test
    fun `anchor was the newest at save, then one more arrived`() {
        // Garrett's repro: he left at the bottom (anchor == newest),
        // a new message landed afterwards.
        val saved = ScrollPosition(
            messageId = 200L, scrollOffset = 0,
            newestMessageId = 200L, newestMessageDate = 5_000L,
        )
        assertTrue(ChatScrollLogic.hasNewerThanSaved(saved, 201L, 6_000L))
    }

    @Test
    fun `same-millisecond arrival still counts as newer`() {
        val saved = ScrollPosition(
            messageId = 200L, scrollOffset = 0,
            newestMessageId = 200L, newestMessageDate = 5_000L,
        )
        // Different id, identical timestamp: a genuine new tail.
        assertTrue(ChatScrollLogic.hasNewerThanSaved(saved, 201L, 5_000L))
    }

    @Test
    fun `shrunk tail (newest deleted) is not newer`() {
        val saved = ScrollPosition(
            messageId = 100L, scrollOffset = 0,
            newestMessageId = 200L, newestMessageDate = 5_000L,
        )
        // The save-time newest is gone; loaded newest is older.
        assertFalse(ChatScrollLogic.hasNewerThanSaved(saved, 150L, 4_000L))
    }

    @Test
    fun `negative newest id works (hashCode ids)`() {
        val saved = ScrollPosition(
            messageId = -50L, scrollOffset = 0,
            newestMessageId = -50L, newestMessageDate = 5_000L,
        )
        assertFalse(ChatScrollLogic.hasNewerThanSaved(saved, -50L, 5_000L))
        assertTrue(ChatScrollLogic.hasNewerThanSaved(saved, -49L, 6_000L))
    }

    @Test
    fun `legacy position - anchor still newest restores`() {
        val saved = ScrollPosition(messageId = 200L, scrollOffset = 0)
        assertFalse(ChatScrollLogic.hasNewerThanSaved(saved, 200L, 9_999L))
    }

    @Test
    fun `legacy position - anchor no longer newest opens at bottom`() {
        val saved = ScrollPosition(messageId = 200L, scrollOffset = 0)
        assertTrue(ChatScrollLogic.hasNewerThanSaved(saved, 201L, 9_999L))
    }

    // ---------- live tail: onTailChanged ----------

    @Test
    fun `own send always scrolls, even scrolled up`() {
        assertEquals(
            ChatScrollLogic.TailAction.TO_BOTTOM,
            ChatScrollLogic.onTailChanged(isOwnSend = true, atBottom = false),
        )
        assertEquals(
            ChatScrollLogic.TailAction.TO_BOTTOM,
            ChatScrollLogic.onTailChanged(isOwnSend = true, atBottom = true),
        )
    }

    @Test
    fun `incoming arrival at the bottom pins`() {
        assertEquals(
            ChatScrollLogic.TailAction.TO_BOTTOM,
            ChatScrollLogic.onTailChanged(isOwnSend = false, atBottom = true),
        )
    }

    @Test
    fun `incoming arrival while scrolled up never yanks`() {
        assertEquals(
            ChatScrollLogic.TailAction.NONE,
            ChatScrollLogic.onTailChanged(isOwnSend = false, atBottom = false),
        )
    }

    // ---------- travel style ----------

    @Test
    fun `short hops animate, long hauls teleport`() {
        assertTrue(ChatScrollLogic.useAnimatedScroll(0))
        assertTrue(ChatScrollLogic.useAnimatedScroll(ChatScrollLogic.MAX_ANIMATED_ITEMS))
        assertFalse(ChatScrollLogic.useAnimatedScroll(ChatScrollLogic.MAX_ANIMATED_ITEMS + 1))
        assertFalse(ChatScrollLogic.useAnimatedScroll(300))
    }
}
