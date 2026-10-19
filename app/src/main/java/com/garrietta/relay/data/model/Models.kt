package com.garrietta.relay.data.model

/**
 * Google Messages' inline transport notices. The server emits these as
 * timeline entries ("RCS chat with {name}", "Texting with {number}
 * (SMS/MMS)") and Google Messages renders them as CENTERED inline text,
 * never as bubbles and never attributed to a sender. The patterns match the
 * system format exactly (full-string match) so real user text is never
 * misclassified.
 */
private val RcsChatNotice = Regex("^RCS chat with .+$")
private val SmsMmsNotice = Regex("^Texting with .+ \\(SMS/MMS\\)$")

fun isTransportSystemNotice(body: String): Boolean {
    val t = body.trim()
    return RcsChatNotice.matches(t) || SmsMmsNotice.matches(t)
}

/** A single conversation thread in the unified inbox. */
data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
    val messageCount: Int,
    /** Dominant transport of the latest message: SMS or MMS. RCS arrives later. */
    val transportLabel: String,
    /** Contacts profile photo URI (PHOTO_URI), or null when there is none. */
    val photoUri: String?,
    /**
     * All provider thread_ids merged into this conversation. The Telephony
     * provider splits SMS and MMS into separate threads when address
     * formatting differs; 1:1 threads resolving to the same normalized
     * address are merged. threadId is the primary (latest) one.
     */
    val threadIds: List<Long> = listOf(threadId),
    /**
     * Server conversation id when this row comes from the RCS sync (paired
     * mode). Null for Telephony rows. The threadId is a stable hash of this
     * id for list keys; all RCS loads branch on this field, never the
     * Telephony provider.
     */
    val rcsConversationId: String? = null,
    /**
     * Group members (self excluded) for group conversations. Drives the
     * per-group avatar cluster in inbox rows. Empty for 1:1 rows.
     */
    val participants: List<Participant> = emptyList(),
)

/** A single message inside a conversation. */
data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isOutgoing: Boolean,
    val isMms: Boolean,
    val read: Boolean,
    /**
     * RCS echo-correlation id (the tmpID sent with the message). The server
     * echo carries it back; the optimistic bubble is replaced on match.
     * Null for Telephony messages.
     */
    val tmpId: String? = null,
    /** RCS media attachment (image/file); null for text messages. */
    val media: com.garrietta.relay.rcs.proto.RcsMediaAttachment? = null,
)

/** Theme preference persisted in SharedPreferences. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** One participant of a conversation: 1:1 threads have exactly one. */
data class Participant(
    val address: String,
    val displayName: String,
    val photoUri: String?,
    /** True for the device owner (RCS server says so; Telephony infers it). */
    val isSelf: Boolean = false,
)
