package com.garrietta.relay.notifications

import com.garrietta.relay.data.model.Conversation
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A notification tap's request to open one conversation (Garrett
 * 2026-10-20: "Tapping a notification for a message I received doesn't
 * open to the chat that that notification came from").
 *
 * [conversationId] is ALWAYS the server's RCS conversation id — the one
 * identity every surface agrees on. The row's threadId is the 32-bit
 * hashCode of that id and must never be used to look the conversation
 * up. [messageId] is the server message id the notification was posted
 * for (null = the open carried no specific message; land on the
 * newest). [nonce] makes every tap a distinct request even when the
 * same notification is tapped twice, so an already-open chat can tell
 * a NEW request from the one it already handled.
 */
data class NotificationOpenRequest(
    val conversationId: String,
    val messageId: String?,
    val nonce: Long,
)

/** Pure decisions of the notification-open path; unit-tested. */
object NotificationOpenLogic {

    /**
     * Validates the raw intent extras into a request. Returns null for
     * anything malformed (missing/blank conversation id) — the caller
     * then simply brings the app forward to wherever it was, no crash,
     * no navigation. A blank message id degrades to "no specific
     * message" rather than killing the open.
     */
    fun fromExtras(
        conversationId: String?,
        messageId: String?,
        nonce: Long,
    ): NotificationOpenRequest? {
        val id = conversationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return NotificationOpenRequest(
            conversationId = id,
            messageId = messageId?.trim()?.takeIf { it.isNotEmpty() },
            nonce = nonce,
        )
    }

    /**
     * Resolves the inbox row for a server conversation id. Matches on
     * [Conversation.rcsConversationId] ONLY — a Telephony row whose
     * threadId happens to equal the id's hash is NOT a match (that
     * confusion is exactly the class of bug this path must never
     * reintroduce).
     */
    fun resolveConversation(
        rows: List<Conversation>,
        conversationId: String,
    ): Conversation? = rows.firstOrNull { it.rcsConversationId == conversationId }

    /**
     * The chat list's id for the tapped message. The repository maps a
     * server message to a UI [com.garrietta.relay.data.model.Message]
     * with `id = serverId.hashCode().toLong()` (RCS message ids are
     * server-id hashCodes), so the target is derived with the identical
     * hash — never parsed, never looked up by threadId.
     */
    fun targetUiMessageId(request: NotificationOpenRequest): Long? =
        request.messageId?.let { it.hashCode().toLong() }
}

/**
 * The single pending notification-open request, app-scoped (held by
 * RelayApp). MainActivity writes it from the launch / onNewIntent
 * intent; the navigation hosts observe it to route into the chat; the
 * ChatScreen observes it to perform the scroll landing and then
 * consumes it. Exactly one request is live at a time — a newer tap
 * supersedes an older unconsumed one, matching the user's latest
 * intent. Consumption is nonce-checked: a stale consumer can never
 * clear a newer request.
 */
class NotificationOpenRouter {

    private val nonces = AtomicLong(0L)

    private val _requests = MutableStateFlow<NotificationOpenRequest?>(null)
    val requests: StateFlow<NotificationOpenRequest?> = _requests.asStateFlow()

    /** Publishes a new request from validated intent extras. */
    fun request(conversationId: String?, messageId: String?) {
        val parsed = NotificationOpenLogic.fromExtras(
            conversationId, messageId, nonces.incrementAndGet(),
        ) ?: return
        _requests.value = parsed
    }

    /** Clears [request] if it is still the live one (it landed). */
    fun consume(request: NotificationOpenRequest) {
        if (_requests.value?.nonce == request.nonce) _requests.value = null
    }

    /** Clears [request] without navigating (unresolvable — dropped). */
    fun discard(request: NotificationOpenRequest) = consume(request)
}
