package com.garrietta.relay.data.repo

/**
 * Membership ground-truth snapshot for ONE server conversation
 * (Garrett 2026-10-20, Dad-chat deep dive): the RAW decoded payload
 * fields next to the derived result the UI renders, so a Diagnostics
 * screenshot adjudicates the payload instead of another round of
 * theory. Pure data + pure formatting — unit-tested.
 *
 * The Dad case in one line: his 1:1 renders as a group ("Dad, Grace")
 * even after the single-source participant derivation landed, and the
 * anomaly detector STILL trips on it — which proves the DERIVED others
 * still hold two people while only Dad ever speaks. Either field 21
 * never arrives for this conversation (the cleaned field-20 fallback
 * then stands, Grace included) or field 21 itself names Grace. This
 * dump prints exactly the fields that distinguish those two worlds.
 *
 * Since the Game Night evidence (2026-10-20), the dump also prints
 * each entry's SmallInfo type and the sender census: field 21 proved
 * to be a SUBSET of membership (it excluded Game Night's bare-number
 * entries, who are exactly the entries carrying sent counts), and the
 * stored row's membership now unions sender evidence on top of the
 * derivation — derivedOthers here reflects that, union included.
 */
data class MembershipEntry(
    val participantId: String,
    val number: String,
    val isMe: Boolean,
    val name: String,
    /** SmallInfo.type (Participant field 1 → SmallInfo field 1), raw. */
    val infoType: Int = 0,
    /**
     * Incoming messages from this entry in the history the repository
     * has loaded (the sender census), or null when no census covers
     * this conversation yet. The Game Night evidence (Garrett
     * 2026-10-20): the field-21-EXCLUDED entries are exactly the ones
     * carrying sent counts — field 21 is a subset, not the roster.
     */
    val sentCount: Int? = null,
)

data class MembershipSnapshot(
    val conversationId: String,
    val title: String,
    val timestampMs: Long,
    /** conversations.Conversation.type (field 22): 1=SMS, 2=RCS. */
    val type: Int,
    /** conversations.Conversation.isGroup (field 10), as decoded. */
    val serverIsGroup: Boolean,
    /** conversations.Conversation.otherParticipants (field 21), raw. */
    val field21: List<String>,
    /** conversations.Conversation.participants (field 20), raw. */
    val field20: List<MembershipEntry>,
    /** The derived "others" the UI renders (deriveRcsMembership output). */
    val derivedOthers: List<MembershipEntry>,
    /** The derived group classification the UI renders. */
    val derivedIsGroup: Boolean,
    /** True when the participantAnomaly detector currently flags it. */
    val anomaly: Boolean,
    /** The anomaly detector's own last inputs, when a message load ran. */
    val detectorClaimedOthers: Int? = null,
    val detectorDistinctSenders: Int? = null,
    val detectorIncoming: Int? = null,
    /**
     * Who actually sends in this conversation, from loaded history
     * (see SenderCensus) — null when no message load has run this
     * session. Field 21's excluded-but-sending entries are how the
     * subset nature of field 21 was proven.
     */
    val senderCensus: SenderCensus? = null,
) {
    /** Field 21 shipped nothing: the field-20 fallback is in play. */
    val field21Absent: Boolean get() = field21.isEmpty()

    /**
     * Field 21 present but its id set differs from field 20's non-self
     * participant ids — the two server fields disagree with each other.
     */
    val fieldSetsMismatch: Boolean
        get() = field21.isNotEmpty() &&
            field20.filter { !it.isMe }.map { it.participantId }.toSet() !=
            field21.toSet()

    /** Marked snapshots always make the dump, ahead of unmarked ones. */
    val marked: Boolean get() = anomaly || fieldSetsMismatch
}

/**
 * Selection for the Diagnostics dump: marked conversations first
 * (anomaly or field-set mismatch — the ones that adjudicate a
 * membership bug), then the rest newest-first, up to [cap] total.
 * Marked conversations are NEVER cut by the cap.
 */
fun selectMembershipSnapshots(
    all: List<MembershipSnapshot>,
    cap: Int = 30,
): List<MembershipSnapshot> {
    val marked = all.filter { it.marked }.sortedByDescending { it.timestampMs }
    val rest = all.filter { !it.marked }.sortedByDescending { it.timestampMs }
    return marked + rest.take((cap - marked.size).coerceAtLeast(0))
}

/** Plain-text rendering of the selected snapshots (no message bodies). */
fun formatMembershipDump(
    selected: List<MembershipSnapshot>,
    totalConversations: Int,
): String = buildString {
    appendLine(
        "Membership dump: ${selected.size} of $totalConversations " +
            "conversations (marked first). Flags: ANOMALY = participant " +
            "detector tripped; F20!=F21 = fields 20/21 disagree; " +
            "NO-F21 = field 21 absent (field-20 fallback in play). " +
            "type = SmallInfo type; sent=N = incoming messages from " +
            "that entry in loaded history.",
    )
    for (s in selected) {
        appendLine()
        val flags = buildList {
            if (s.anomaly) add("ANOMALY")
            if (s.fieldSetsMismatch) add("F20!=F21")
            if (s.field21Absent) add("NO-F21")
        }
        appendLine(
            "== \"${s.title}\"" +
                (if (flags.isEmpty()) "" else flags.joinToString(" ", " [", "]")),
        )
        appendLine("  id=${s.conversationId}")
        appendLine(
            "  type(22)=${s.type} isGroup(10)=${s.serverIsGroup} " +
                "derivedGroup=${s.derivedIsGroup}",
        )
        appendLine(
            "  f21(${s.field21.size}): " +
                (if (s.field21.isEmpty()) "(absent)" else s.field21.joinToString(", ")),
        )
        appendLine("  f20(${s.field20.size}):")
        for (p in s.field20) {
            appendLine(
                "    - id=${p.participantId.ifBlank { "(blank)" }} " +
                    "num=${p.number.ifBlank { "(blank)" }} " +
                    "name=${p.name.ifBlank { "(blank)" }}" +
                    (if (p.isMe) " isMe" else "") +
                    " type=${p.infoType}" +
                    (p.sentCount?.let { " sent=$it" } ?: ""),
            )
        }
        appendLine(
            "  derivedOthers(${s.derivedOthers.size}): " +
                s.derivedOthers.joinToString(", ") {
                    "${it.participantId.ifBlank { "(blank)" }}:${it.number}"
                }.ifBlank { "(none)" },
        )
        s.senderCensus?.let { census ->
            appendLine(
                "  census: otherSenders=${census.sentByPid.size} " +
                    "unmatchedPids=${census.unmatchedPids.size} " +
                    "unmatchedMsgs=${census.unmatchedMessages} " +
                    "(from loaded history)",
            )
        }
        if (s.detectorClaimedOthers != null) {
            appendLine(
                "  detector: claimedOthers=${s.detectorClaimedOthers} " +
                    "distinctSenders=${s.detectorDistinctSenders} " +
                    "incoming=${s.detectorIncoming}" +
                    (if (s.anomaly) " -> ANOMALY" else ""),
            )
        }
    }
}
