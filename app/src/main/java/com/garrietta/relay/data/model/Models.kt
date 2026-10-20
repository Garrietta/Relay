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
     * ALL server conversation ids unified into this row by the
     * duplicate participant-set merge (Garrett 2026-10-20: identical
     * participant sets in more than one chat ARE the same chat),
     * canonical first. Empty when the row was not merged — [allRcsIds]
     * then falls back to [rcsConversationId]. Display unification only:
     * the underlying server conversations and their messages are never
     * deleted or rewritten, so a future server-side change un-merges
     * cleanly.
     */
    val mergedRcsIds: List<String> = emptyList(),
    /**
     * Group members (self excluded) for group conversations. Drives the
     * per-group avatar cluster in inbox rows. Empty for 1:1 rows.
     */
    val participants: List<Participant> = emptyList(),
    /**
     * The ONE group classification every surface reads (Garrett
     * 2026-10-20): set where the row is built — from the server's
     * Conversation.isGroup (field 10) or the cleaned other-participant
     * count for RCS rows, from the recipient count for Telephony rows.
     * The inbox row, the chat header and the details page all consume
     * this flag, so they can never disagree ("No Dad" rendered as a
     * group in the inbox but opened as a 1:1).
     */
    val isGroup: Boolean = false,
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
    /**
     * Delivery state for outgoing messages (Garrett 2026-10-19): Google
     * Messages-style ticks — sent → delivered → read. Wired from the RCS
     * MessageStatus proto codes; NONE for incoming messages.
     */
    val deliveryStatus: DeliveryStatus = DeliveryStatus.NONE,
    /**
     * Emoji reactions attached to this message (Garrett 2026-10-19). REAL
     * reaction events from the wire (conversations.Message field 19) land
     * here via the RCS mapping; formatted text messages that genuinely
     * arrive as text (`{emoji} to "{snippet}"`, plus iPhone tapback verb
     * forms) are consumed into here by ChatViewModel so they also render as
     * inline badges, Google Messages style, instead of stray text bubbles.
     */
    val reactions: List<MessageReaction> = emptyList(),
    /**
     * Server-assigned RCS message id (conversations.Message field 1) when
     * known — needed for protocol actions that target a message (e.g. the
     * SEND_REACTION request). Null for Telephony messages.
     */
    val serverId: String? = null,
    /**
     * Upload progress (0..1) while THIS outgoing attachment is uploading
     * (Garrett 2026-10-19: the attachment-send path). Null once the upload
     * finishes and the send is handed to the server — the bubble then shows
     * the normal SENDING delivery state until the echo lands.
     */
    val uploadProgress: Float? = null,
)

/**
 * An emoji reaction attached to a message.
 *
 * @property reactorName The reactor's display name, or "You" when Garrett reacted.
 */
data class MessageReaction(
    val emoji: String,
    val reactorName: String,
    val fromMe: Boolean,
    val timestampMs: Long,
)

/**
 * Outgoing message delivery state, rendered as Google Messages-style ticks:
 * SENT = single check, DELIVERED = double check, READ = double check filled
 * blue. SENDING = just left the phone (optimistic bubble).
 */
enum class DeliveryStatus { NONE, SENDING, SENT, DELIVERED, READ }

/**
 * Maps an RCS MessageStatus code to a [DeliveryStatus]. Server codes
 * (RpcModels): OUTGOING_* are <100 and nonzero (2=delivered, 11=displayed);
 * anything else nonzero outgoing = sent.
 */
fun rcsStatusToDelivery(status: Int, outgoing: Boolean): DeliveryStatus {
    if (!outgoing) return DeliveryStatus.NONE
    return when (status) {
        11 -> DeliveryStatus.READ
        2 -> DeliveryStatus.DELIVERED
        else -> DeliveryStatus.SENT
    }
}

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

/**
 * String-level ghost predicate (Garrett 2026-10-19): the server emits this
 * remnant's name wrapped in varying quote/invisible characters — straight,
 * curly, backtick, sometimes several layers, possibly with other junk —
 * which is why it renders quoted in the header ("Alec, Trenton,
 * 'UNKNOWN_SENDER!'"). Three rounds of matching kept missing it (#118,
 * #119, #122) because the wrapping characters vary. Bulletproof
 * normalization: strip EVERYTHING that isn't alphanumeric, uppercase, and
 * match on the bare constant. Catches "'UNKNOWN_SENDER!'",
 * "Unknown Sender", "unknown_sender", etc. Display-layer only; the
 * underlying data is never touched.
 */
fun String.isUnknownSenderGhostName(): Boolean {
    val normalized = this.filter { it.isLetterOrDigit() }.uppercase()
    return "UNKNOWNSENDER" in normalized
}


/**
 * Display-layer ghost predicate (Garrett 2026-10-19): a participant whose
 * display name is literally "UNKNOWN_SENDER!" — a remnant (he suspects car
 * usage) — is HIDDEN from name lists and avatar stacks, never deleted.
 * The underlying data is untouched; call sites filter with
 * `filterNot { it.isUnknownSenderGhost() }`.
 */
fun Participant.isUnknownSenderGhost(): Boolean = displayName.isUnknownSenderGhostName()

/** Every server conversation id this row represents, canonical first. */
fun Conversation.allRcsIds(): List<String> =
    if (mergedRcsIds.isNotEmpty()) mergedRcsIds
    else listOfNotNull(rcsConversationId)

/**
 * Built-in "Unknown" category membership (Garrett 2026-10-19): a
 * conversation is "unknown" when its display title is a bare phone number —
 * digits, +, spaces, dashes, parens, dots only, no resolved contact name.
 * His political-campaign SMS threads ("(317) 286-6929", "454545", "22395")
 * land here. No new permissions: the title the inbox already shows is the
 * whole signal. Auto-classification wins: an unknown thread is excluded
 * from All and from custom categories while the toggle is on, and shows
 * only under the Unknown tab.
 */
fun Conversation.isUnknownNumber(): Boolean {
    val t = displayName.trim()
    if (t.isEmpty() || !t.any { it.isDigit() }) return false
    return t.all { it.isDigit() || it in "+-(). " }
}
