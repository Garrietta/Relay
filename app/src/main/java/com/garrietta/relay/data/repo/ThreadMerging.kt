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

// ---------- Duplicate participant-set merging (Garrett 2026-10-20) ----------

/**
 * The duplicate-merge key for a unified-inbox row: the EXACT set of
 * normalized participant numbers, self excluded. [Conversation.participants]
 * already excludes self on both the RCS path (cleaned server others) and
 * the Telephony path (recipients minus self); 1:1 rows with no
 * participant list key on their single address. Returns null when the
 * row must never merge: no participants at all, or any address that is
 * not a mergeable phone number (short codes / alphanumeric senders —
 * the same rule as [mergeKey]).
 */
fun participantSetKey(row: Conversation): Set<String>? {
    // Participant-integrity guard (Garrett 2026-10-20): a row classified
    // as a GROUP whose own participant list is empty must never key on
    // its single fallback address — that would merge a group into a real
    // 1:1 with the first member and drag the group's identity with it.
    // A group that cannot name its members from its OWN payload does
    // not merge at all.
    if (row.isGroup && row.participants.isEmpty()) return null
    val addresses = if (row.participants.isNotEmpty()) {
        row.participants.map { it.address }
    } else {
        listOf(row.address)
    }
    val keys = addresses.map { mergeKey(it) ?: return null }
    return keys.toSet().ifEmpty { null }
}

/**
 * Participant-integrity anomaly (Garrett 2026-10-20): a conversation's
 * own participant list claims a group ([claimedOthers] > 1) but its
 * loaded messages were sent by at most ONE distinct other participant.
 * The list and the message evidence contradict each other — his "Dad,
 * Grace Ambrose" chat whose messages are all Dad's. The server list
 * still wins for display and merging (it is the only membership data
 * there is); the contradiction is SURFACED in Diagnostics instead of
 * silently trusted. Requires enough message evidence to judge: a group
 * where a member simply hasn't spoken in the last couple of messages
 * is not an anomaly.
 */
fun participantAnomaly(
    claimedOthers: Int,
    distinctOtherSenders: Int,
    incomingMessages: Int,
): Boolean =
    claimedOthers > 1 && incomingMessages >= 3 && distinctOtherSenders <= 1

/**
 * Garrett's hard verification rule (2026-10-20): "if a two-person chat
 * exists, or if any set number of contacts exists in more than one
 * chat, then those chats are likely the same chat and should simply be
 * combined and organized by timestamp." His evidence: two inbox rows —
 * "Dad" (whose own avatar cluster showed Dad + Grace) and "Dad, Grace
 * Ambrose" — one real group conversation split into two entries.
 *
 * Rows whose participant sets are EXACTLY equal (set equality only,
 * never subset matches) collapse into one entry:
 *  - title / avatar / participants from the FULLEST participant list,
 *  - snippet / date / transport chip from the newest message overall,
 *  - unread OR-ed, message counts summed,
 *  - routing identity (threadId / rcsConversationId) from the CANONICAL
 *    member: a live server (RCS) conversation beats a local row, an
 *    RCS-transport row beats an SMS one, then the most recently active
 *    member wins.
 *
 * Nothing is deleted: this is display unification in the repository's
 * row list. The merged row remembers every server id in
 * [Conversation.mergedRcsIds] so message loads fan out across members,
 * sends canonicalize, and read-marking covers every member.
 */
fun mergeDuplicateConversations(rows: List<Conversation>): List<Conversation> {
    val buckets = LinkedHashMap<Set<String>, MutableList<Conversation>>()
    val passthrough = mutableListOf<Conversation>()
    for (row in rows) {
        val key = participantSetKey(row)
        if (key == null) {
            passthrough.add(row)
        } else {
            buckets.getOrPut(key) { mutableListOf() }.add(row)
        }
    }
    val out = passthrough.toMutableList()
    for (bucket in buckets.values) {
        out.add(if (bucket.size == 1) bucket.single() else mergeDuplicateBucket(bucket))
    }
    return out.sortedByDescending { it.date }
}

/** Combines one bucket of participant-set-identical rows. See above. */
private fun mergeDuplicateBucket(bucket: List<Conversation>): Conversation {
    // Canonical (routing identity): live server conversation first,
    // RCS transport next, then most recent activity.
    val canonical = bucket.sortedWith(
        compareByDescending<Conversation> { it.rcsConversationId != null }
            .thenByDescending { it.transportLabel == "RCS" }
            .thenByDescending { it.date }
            .thenByDescending { it.messageCount },
    ).first()
    // Display representative: the fullest participant list — most
    // members, then most contact-resolved names — then most recent.
    val representative = bucket.sortedWith(
        compareByDescending<Conversation> { it.participants.size }
            .thenByDescending { rep ->
                rep.participants.count { it.displayName != it.address }
            }
            .thenByDescending { it.date },
    ).first()
    // Snippet / date: the newest message overall.
    val newest = bucket.maxByOrNull { it.date } ?: canonical
    // Every server id in the group, canonical first, rest by recency.
    val mergedIds = (listOfNotNull(canonical.rcsConversationId) +
        bucket.filter { it !== canonical }
            .sortedByDescending { it.date }
            .mapNotNull { it.rcsConversationId }).distinct()
    return representative.copy(
        threadId = canonical.threadId,
        threadIds = bucket.flatMap { it.threadIds }.distinct()
            .ifEmpty { listOf(canonical.threadId) },
        rcsConversationId = canonical.rcsConversationId,
        mergedRcsIds = mergedIds,
        snippet = newest.snippet,
        date = newest.date,
        transportLabel = newest.transportLabel,
        unread = bucket.any { it.unread },
        messageCount = bucket.sumOf { it.messageCount },
        isGroup = bucket.any { it.isGroup } || representative.participants.size > 1,
    )
}
