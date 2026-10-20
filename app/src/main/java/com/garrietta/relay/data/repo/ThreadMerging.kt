package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation

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
