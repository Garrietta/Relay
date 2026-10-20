package com.garrietta.relay.notifications

/**
 * Missed-message accumulation for conversation notifications (Garrett
 * 2026-10-20: "I would like to see all the new messages I've missed,
 * not just the most recent one"). One notification per conversation
 * stays, but its expanded MessagingStyle now lists EVERY message
 * missed since the conversation was last read, oldest first — the
 * single-latest-message style was the whole complaint.
 *
 * Pure JVM (no Android types) so the accumulation rules are unit
 * tested: RelayApp appends at its single notification choke point
 * (handleIncomingRcsMessage, after every skip decision — muted, open
 * chat, own echo, unpaired, toggle-off never reach the append, so
 * reactions/typing/system traffic that never notifies never
 * accumulates either), and the backlog is cleared at exactly the
 * seams that cancel the conversation's posted notification today:
 * opening the chat by any path, the Mark-as-read action, and the
 * inline Reply action — all of which funnel through
 * [MessageNotifications.cancelForConversation]. The store therefore
 * can never outlive its notification or resurrect after a read.
 *
 * In-memory only, keyed by SERVER conversation id, with the same
 * process lifetime as RelayApp's notifiedMessageIds dedupe set: a
 * process restart resets it, exactly like the dedupe set, and the
 * poll fallback's baseline keeps pre-existing history from
 * re-notifying (and so from re-accumulating) after a restart.
 */
data class MissedMessage(
    val messageId: String,
    /** Display name of THIS message's sender (groups differ per message). */
    val senderName: String,
    /** Sender's number/address; the avatar shape key ("" when unknown). */
    val senderNumber: String,
    /** Notification text, placeholders already applied (see below). */
    val body: String,
    val timestampMs: Long,
    /** Sender's contact photo URI at arrival time, for their Person icon. */
    val photoUri: String? = null,
)

/** Thread-safe per-conversation backlog of missed messages. */
class MissedMessageStore(
    private val cap: Int = MAX_MISSED_MESSAGES,
) {
    private val lock = Any()
    private val byConversation = LinkedHashMap<String, MutableList<MissedMessage>>()

    /**
     * Appends [message] to [conversationId]'s backlog, evicting the
     * OLDEST entries past the cap, and returns the full backlog
     * snapshot (oldest → newest) for the notification rebuild.
     */
    fun append(conversationId: String, message: MissedMessage): List<MissedMessage> =
        synchronized(lock) {
            val list = byConversation.getOrPut(conversationId) { mutableListOf() }
            list.add(message)
            while (list.size > cap) list.removeAt(0)
            missedForLocked(conversationId)
        }

    /** The conversation's current backlog, oldest → newest (a copy). */
    fun missedFor(conversationId: String): List<MissedMessage> =
        synchronized(lock) { missedForLocked(conversationId) }

    /** Drops the conversation's backlog (it has been read / opened). */
    fun clear(conversationId: String) {
        synchronized(lock) { byConversation.remove(conversationId) }
    }

    private fun missedForLocked(conversationId: String): List<MissedMessage> =
        byConversation[conversationId]?.toList() ?: emptyList()

    companion object {
        /**
         * Backlog bound per conversation: a busy group can never grow
         * an unbounded notification; past this, the style shows the
         * most recent [MAX_MISSED_MESSAGES] missed messages.
         */
        const val MAX_MISSED_MESSAGES = 15
    }
}

/**
 * The notification body for one incoming message: its text when it
 * has any, else the same placeholder the inbox snippet uses
 * (UnifiedInboxRepository: image attachments read "📷 Photo"), a
 * named pin for a bare location share, and a generic fallback. Pure
 * so the placeholder rules are tested alongside the store.
 */
fun notificationBodyText(
    body: String,
    hasMedia: Boolean,
    hasLocation: Boolean,
    locationName: String?,
): String = body.ifBlank {
    when {
        hasMedia -> "\uD83D\uDCF7 Photo"
        hasLocation -> "\uD83D\uDCCD " +
            (locationName?.ifBlank { null } ?: "Location")
        else -> "New message"
    }
}
