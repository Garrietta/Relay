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
    /**
     * Delivery state for outgoing messages (Garrett 2026-10-19): Google
     * Messages-style ticks — sent → delivered → read. Wired from the RCS
     * MessageStatus proto codes; NONE for incoming messages.
     */
    val deliveryStatus: DeliveryStatus = DeliveryStatus.NONE,
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
 * 'UNKNOWN_SENDER!'"). Exact-match kept missing it (#118, #119). Now: strip
 * every quote flavor and invisible char from the WHOLE string, then match
 * on the substring — the constant UNKNOWN_SENDER is specific enough to
 * never collide with a real contact name. Display-layer only; the
 * underlying data is never touched.
 */
fun String.isUnknownSenderGhostName(): Boolean {
    val strip = setOf(
        '"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '\u201A', '\u201E',
        '\u2039', '\u203A', '\u00AB', '\u00BB', '`', '\u00B4', '\u02BC',
        ' ', '\t', '\n', '\r', '\u00A0', '\u200B', '\u200C', '\u200D', '\uFEFF',
    )
    val normalized = this.filterNot { it in strip }
    return normalized.contains("UNKNOWN_SENDER")
}

/**
 * Display-layer ghost predicate (Garrett 2026-10-19): a participant whose
 * display name is literally "UNKNOWN_SENDER!" — a remnant (he suspects car
 * usage) — is HIDDEN from name lists and avatar stacks, never deleted.
 * The underlying data is untouched; call sites filter with
 * `filterNot { it.isUnknownSenderGhost() }`.
 */
fun Participant.isUnknownSenderGhost(): Boolean = displayName.isUnknownSenderGhostName()

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
