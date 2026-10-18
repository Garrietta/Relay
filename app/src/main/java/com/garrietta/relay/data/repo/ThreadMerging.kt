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
