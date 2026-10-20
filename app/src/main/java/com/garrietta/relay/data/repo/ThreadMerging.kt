package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Participant

/**
 * Pure, JVM-testable thread merging. The repository resolves contact
 * IDs/names/photos from the providers BEFORE calling in, so this file makes
 * zero Android framework calls and is covered by unit tests.
 *
 * Rules:
 *  - 1:1 threads bucket by contact ID first (same person, different numbers),
 *    then by normalized address. Group threads and short codes never merge.
 *  - Provider noise is hidden: a thread with no messages, a blank snippet,
 *    and no resolvable contact is not a conversation (the "No messages"
 *    phantom leg).
 *  - Two DIFFERENT contact IDs are NEVER merged, no matter what.
 */

/** One provider thread row, with contact resolution already done. */
data class MergeCandidate(
    val threadId: Long,
    val address: String,
    val recipients: Set<String>,
    val date: Long,
    val snippet: String,
    val unread: Boolean,
    val messageCount: Int,
    val transportLabel: String,
    /** Raw contact ID, or null when the address isn't in contacts. */
    val contactId: Long?,
    val displayName: String,
    val photoUri: String?,
)

/**
 * Normalizes an address to a merge key: digits only; NANP numbers compare by
 * last 10 digits. Returns null when the address isn't a mergeable phone
 * number (short codes, alphanumeric senders, blanks) — those are never merged.
 */
fun mergeKey(address: String): String? {
    val digits = address.filter { it.isDigit() }
    if (digits.length < 7) return null
    return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
}

/**
 * Merges 1:1 threads that belong to the same conversation into a single
 * [Conversation], newest first. See the file header for the rules.
 */
fun mergeCandidates(candidates: List<MergeCandidate>): List<Conversation> {
    // Provider noise: no messages + blank snippet + no resolvable contact =
    // not a conversation. Hide it instead of showing a phantom row.
    val real = candidates.filterNot { c ->
        c.messageCount == 0 && c.snippet.isBlank() && c.contactId == null
    }
    val singles = real.filter { it.recipients.size <= 1 }
    val groups = real.filter { it.recipients.size > 1 }
    val buckets = mutableMapOf<String, MutableList<MergeCandidate>>()
    val unmerged = mutableListOf<MergeCandidate>()
    for (c in singles) {
        val key = c.contactId?.let { "contact:$it" }
            ?: mergeKey(c.address)?.let { "addr:$it" }
        if (key == null) {
            unmerged.add(c)
        } else {
            buckets.getOrPut(key) { mutableListOf() }.add(c)
        }
    }
    val out = mutableListOf<Conversation>()
    for (bucket in buckets.values) out.add(mergeBucket(bucket))
    for (c in unmerged) out.add(c.toConversation(listOf(c.threadId)))
    for (c in groups) out.add(c.toConversation(listOf(c.threadId)))
    return out.sortedByDescending { it.date }
}

/**
 * Merges one bucket of same-conversation 1:1 threads. Latest wins for the
 * row fields; unread is OR-ed; message counts are summed.
 */
private fun mergeBucket(bucket: List<MergeCandidate>): Conversation {
    val sorted = bucket.sortedByDescending { it.date }
    val primary = sorted.first()
    return Conversation(
        threadId = primary.threadId,
        address = primary.address,
        displayName = primary.displayName,
        snippet = primary.snippet,
        date = primary.date,
        unread = sorted.any { it.unread },
        messageCount = sorted.sumOf { it.messageCount },
        transportLabel = primary.transportLabel,
        photoUri = primary.photoUri,
        threadIds = sorted.map { it.threadId },
    )
}

private fun MergeCandidate.toConversation(ids: List<Long>): Conversation =
    Conversation(
        threadId = threadId,
        address = address,
        displayName = displayName,
        snippet = snippet,
        date = date,
        unread = unread,
        messageCount = messageCount,
        transportLabel = transportLabel,
        photoUri = photoUri,
        threadIds = ids,
    )

// ---------- Participant integrity (Garrett 2026-10-20) ----------

/**
 * Participant-integrity anomaly (Garrett 2026-10-20): a conversation's
 * own participant list claims a group ([claimedOthers] > 1) but its
 * loaded messages were sent by at most ONE distinct other participant.
 * The list and the message evidence contradict each other — his "Dad,
 * Grace Ambrose" chat whose messages are all Dad's. The server list
 * still wins for display (it is the only membership data there is);
 * the contradiction is SURFACED in Diagnostics instead of silently
 * trusted. Requires enough message evidence to judge: a group where
 * a member simply hasn't spoken in the last couple of messages is
 * not an anomaly.
 *
 * NOTE (2026-10-20 teardown): the cross-conversation participant-set
 * merge that once lived here (mergeDuplicateConversations) is REMOVED.
 * It was built on a polluted premise — participant-list pollution made
 * a 1:1's set look identical to a group's — and it combined two
 * legitimately separate server conversations (his Dad 1:1 and the
 * Dad+Grace group), corrupting sender attribution in the process.
 * The invariant now: every server conversation is its own chat, and
 * nothing crosses conversation boundaries.
 */
fun participantAnomaly(
    claimedOthers: Int,
    distinctOtherSenders: Int,
    incomingMessages: Int,
): Boolean =
    claimedOthers > 1 && incomingMessages >= 3 && distinctOtherSenders <= 1

// ---------- Snippet prefixes + typing labels (Garrett 2026-10-20) ----------

/** First name from a display name: the first whitespace-separated token. */
fun firstNameOf(displayName: String): String =
    displayName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

/**
 * Google Messages snippet prefix for a conversation's latest message
 * (Garrett 2026-10-20, GM parity from his side-by-side screenshots):
 *  - latest message is OUTGOING -> "You: " (any chat, self-chat included),
 *  - latest is incoming in a GROUP -> "{FirstName}: " from the sender's
 *    display name (the server's LatestMessage.displayName on the RCS
 *    path; a contact-resolved name on the Telephony path),
 *  - incoming in a 1:1, or no sender name to show -> "" (GM shows the
 *    bare text there).
 * Applied to the FINAL snippet text (reaction snippets keep their
 * existing format; the same rule prefixes them). Never applied to a
 * blank snippet — the row's "No messages" fallback must stay bare.
 */
fun snippetPrefix(latestFromMe: Boolean, isGroup: Boolean, latestSenderName: String): String =
    when {
        latestFromMe -> "You: "
        isGroup -> firstNameOf(latestSenderName)
            .takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
        else -> ""
    }

/** Digits-only form of a phone number for comparison. */
private fun digitsOnly(number: String): String = number.filter { it.isDigit() }

/**
 * Number equality tolerant of country-code formatting (the repository's
 * standing rule): exact digit match, or last-10 match when both sides
 * have at least 10 digits.
 */
fun samePhoneNumber(a: String, b: String): Boolean {
    val da = digitsOnly(a)
    val db = digitsOnly(b)
    if (da.isEmpty() || db.isEmpty()) return false
    if (da == db) return true
    return da.length >= 10 && db.length >= 10 && da.takeLast(10) == db.takeLast(10)
}

/**
 * The inbox typing label for one conversation (Garrett 2026-10-20):
 * "{FirstName} is typing..." with the typist resolved from the wire's
 * TypingData number against the conversation's OTHER participants:
 *  - number matches a participant -> that participant's first name;
 *  - no usable number but exactly ONE other participant (a 1:1) -> them;
 *  - a group whose typist can't be resolved -> "Someone is typing..."
 *    (a generic label, never a fabricated name);
 *  - no other participants at all (self-chat) -> null, no label.
 */
fun typingLabel(
    others: List<Participant>,
    typistNumber: String?,
): String? {
    if (others.isEmpty()) return null
    val byNumber = typistNumber?.takeIf { it.isNotBlank() }?.let { n ->
        others.firstOrNull { samePhoneNumber(it.address, n) }
    }
    val named = byNumber ?: others.singleOrNull()
    val first = named?.let { firstNameOf(it.displayName.ifBlank { it.address }) }
    return if (!first.isNullOrBlank()) "$first is typing..."
    else "Someone is typing..."
}
