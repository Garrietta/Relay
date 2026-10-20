package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the optimistic-read latch (Garrett 2026-10-20): the tap
 * on an unread chat marks it read locally at once, and a stale refresh
 * must not resurrect the unread flag before the server's mark-read
 * propagates. Pure JVM — rows are plain [Conversation] values and the
 * latch keys come from [CategoryStore.conversationKey], the same helper
 * the repository uses.
 * Run on CI via testDebugUnitTest.
 */
class ReadLatchTest {

    private fun row(
        threadId: Long = 7L,
        rcsConversationId: String? = null,
        date: Long = 1_000L,
        unread: Boolean = true,
    ) = Conversation(
        threadId = threadId,
        address = "5551234567",
        displayName = "Dad",
        snippet = "hello",
        date = date,
        unread = unread,
        messageCount = 3,
        transportLabel = "RCS",
        photoUri = null,
        rcsConversationId = rcsConversationId,
    )

    private fun keyOf(c: Conversation): String =
        CategoryStore.conversationKey(c.rcsConversationId, c.threadId)

    @Test
    fun `stale refresh is held read after open`() {
        val latch = ReadLatch()
        val opened = row(date = 1_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        // A refresh carrying pre-mark-read server metadata (still
        // unread, same newest message) must not resurrect the flag.
        val out = latch.applyTo(listOf(row(date = 1_000L, unread = true)))
        assertFalse(out.single().unread)
        assertTrue(latch.isLatched(keyOf(opened)))
    }

    @Test
    fun `repeated stale refreshes stay held`() {
        val latch = ReadLatch()
        val opened = row(date = 5_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        repeat(3) {
            val out = latch.applyTo(listOf(row(date = 5_000L, unread = true)))
            assertFalse(out.single().unread)
        }
        assertTrue(latch.isLatched(keyOf(opened)))
    }

    @Test
    fun `server reporting read satisfies and releases the latch`() {
        val latch = ReadLatch()
        val opened = row(date = 1_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        val out = latch.applyTo(listOf(row(date = 1_000L, unread = false)))
        assertFalse(out.single().unread)
        assertFalse(latch.isLatched(keyOf(opened)))
        // And it stays released: a later unread emission is genuine.
        val later = latch.applyTo(listOf(row(date = 1_000L, unread = true)))
        assertTrue(later.single().unread)
    }

    @Test
    fun `newer message releases the latch and unread returns`() {
        val latch = ReadLatch()
        val opened = row(date = 1_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        // A message newer than the one observed at open arrived.
        val out = latch.applyTo(listOf(row(date = 2_000L, unread = true)))
        assertTrue(out.single().unread)
        assertFalse(latch.isLatched(keyOf(opened)))
    }

    @Test
    fun `manual clear lets manual unread stick against refresh echoes`() {
        val latch = ReadLatch()
        val opened = row(date = 1_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        // Mark-as-unread drops the latch first (repository behavior).
        latch.clear(keyOf(opened))
        val out = latch.applyTo(listOf(row(date = 1_000L, unread = true)))
        assertTrue(out.single().unread)
    }

    @Test
    fun `unlatched rows pass through untouched`() {
        val latch = ReadLatch()
        val rows = listOf(
            row(threadId = 1L, unread = true),
            row(threadId = 2L, unread = false),
        )
        assertEquals(rows, latch.applyTo(rows))
    }

    @Test
    fun `latch on one conversation does not touch another`() {
        val latch = ReadLatch()
        val opened = row(threadId = 7L, date = 1_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        val other = row(threadId = 9L, date = 1_000L, unread = true)
        val out = latch.applyTo(listOf(row(threadId = 7L, date = 1_000L, unread = true), other))
        assertFalse(out[0].unread)
        assertTrue(out[1].unread)
    }

    @Test
    fun `rcs rows key on the server conversation id`() {
        val latch = ReadLatch()
        // RCS threadIds are hashes of the server id; the latch must
        // follow the server id, not any single hash value.
        val opened = row(threadId = 111L, rcsConversationId = "conv-A", date = 3_000L, unread = true)
        latch.latch(keyOf(opened), opened.date)
        val refreshed = row(threadId = 111L, rcsConversationId = "conv-A", date = 3_000L, unread = true)
        assertFalse(latch.applyTo(listOf(refreshed)).single().unread)
        // A different conversation reusing neither id nor key is free.
        val other = row(threadId = 222L, rcsConversationId = "conv-B", date = 3_000L, unread = true)
        assertTrue(latch.applyTo(listOf(other)).single().unread)
    }
}
