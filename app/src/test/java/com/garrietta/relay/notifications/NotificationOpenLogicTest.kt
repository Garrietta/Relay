package com.garrietta.relay.notifications

import com.garrietta.relay.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the notification-open path (Garrett 2026-10-20): a
 * tapped message notification must open ITS conversation, on the
 * message it was posted for. These cover the pure decisions — extras
 * validation, row resolution by SERVER id (never by the threadId
 * hash), the message-id derivation the chat list uses, and the
 * router's publish/consume discipline. Pure JVM.
 */
class NotificationOpenLogicTest {

    private fun row(
        threadId: Long,
        rcsConversationId: String?,
        name: String = "Chat",
    ) = Conversation(
        threadId = threadId,
        address = "+10000000000",
        displayName = name,
        snippet = "",
        date = 0L,
        unread = true,
        messageCount = 1,
        transportLabel = if (rcsConversationId != null) "RCS" else "SMS",
        photoUri = null,
        rcsConversationId = rcsConversationId,
    )

    // ---------- extras validation ----------

    @Test
    fun `valid extras produce a request`() {
        val request = NotificationOpenLogic.fromExtras("conv-1", "msg-9", nonce = 7L)
        assertEquals(
            NotificationOpenRequest("conv-1", "msg-9", 7L),
            request,
        )
    }

    @Test
    fun `missing or blank conversation id is not a request`() {
        assertNull(NotificationOpenLogic.fromExtras(null, "msg-9", 1L))
        assertNull(NotificationOpenLogic.fromExtras("", "msg-9", 1L))
        assertNull(NotificationOpenLogic.fromExtras("   ", "msg-9", 1L))
    }

    @Test
    fun `blank message id degrades to no specific message`() {
        val request = NotificationOpenLogic.fromExtras("conv-1", "  ", nonce = 3L)
        assertEquals("conv-1", request?.conversationId)
        assertNull(request?.messageId)
        assertNull(NotificationOpenLogic.fromExtras("conv-1", null, 3L)?.messageId)
    }

    // ---------- row resolution ----------

    @Test
    fun `resolves the row carrying the server id`() {
        val target = row(threadId = 4242L, rcsConversationId = "conv-1")
        val rows = listOf(
            row(threadId = 1L, rcsConversationId = "conv-2"),
            target,
            row(threadId = 2L, rcsConversationId = null),
        )
        assertEquals(target, NotificationOpenLogic.resolveConversation(rows, "conv-1"))
    }

    @Test
    fun `absent conversation resolves to null`() {
        val rows = listOf(row(threadId = 1L, rcsConversationId = "conv-2"))
        assertNull(NotificationOpenLogic.resolveConversation(rows, "conv-1"))
        assertNull(NotificationOpenLogic.resolveConversation(emptyList(), "conv-1"))
    }

    @Test
    fun `never resolves by threadId hash`() {
        // A Telephony row whose threadId numerically equals the RCS
        // row-id hash for "conv-1" must NOT be mistaken for the
        // conversation: identity is the server id, nothing else.
        val serverId = "conv-1"
        val impostorThreadId = ("rcs:" + serverId).hashCode().toLong()
        val rows = listOf(row(threadId = impostorThreadId, rcsConversationId = null))
        assertNull(NotificationOpenLogic.resolveConversation(rows, serverId))
    }

    // ---------- message target derivation ----------

    @Test
    fun `target ui id is the server id hash, as the repository maps it`() {
        // UnifiedInboxRepository maps a server message into the chat
        // list with id = serverId.hashCode().toLong(); the landing
        // target must use the identical derivation.
        val serverMessageId = "1f3e9c-message-id"
        val request = NotificationOpenRequest("conv-1", serverMessageId, 1L)
        assertEquals(
            serverMessageId.hashCode().toLong(),
            NotificationOpenLogic.targetUiMessageId(request),
        )
    }

    @Test
    fun `request without a message has no target`() {
        val request = NotificationOpenRequest("conv-1", null, 1L)
        assertNull(NotificationOpenLogic.targetUiMessageId(request))
    }

    @Test
    fun `extras round-trip into the landing target`() {
        val request = NotificationOpenLogic.fromExtras("conv-7", "srv-msg-3", nonce = 5L)!!
        assertEquals(
            "srv-msg-3".hashCode().toLong(),
            NotificationOpenLogic.targetUiMessageId(request),
        )
    }

    // ---------- router discipline ----------

    @Test
    fun `request publishes with increasing nonces`() {
        val router = NotificationOpenRouter()
        assertNull(router.requests.value)
        router.request("conv-1", "m1")
        val first = router.requests.value
        router.request("conv-2", "m2")
        val second = router.requests.value
        assertEquals("conv-1", first?.conversationId)
        assertEquals("conv-2", second?.conversationId)
        assertEquals(true, second!!.nonce > first!!.nonce)
    }

    @Test
    fun `malformed request publishes nothing`() {
        val router = NotificationOpenRouter()
        router.request(null, "m1")
        router.request("  ", "m1")
        assertNull(router.requests.value)
    }

    @Test
    fun `consume clears only the live request`() {
        val router = NotificationOpenRouter()
        router.request("conv-1", "m1")
        val stale = router.requests.value!!
        router.request("conv-2", "m2")
        val live = router.requests.value!!
        // A stale consumer must not clear the newer request.
        router.consume(stale)
        assertEquals(live, router.requests.value)
        router.consume(live)
        assertNull(router.requests.value)
    }

    @Test
    fun `discard drops an unresolvable request`() {
        val router = NotificationOpenRouter()
        router.request("conv-gone", "m1")
        val request = router.requests.value!!
        router.discard(request)
        assertNull(router.requests.value)
    }
}
