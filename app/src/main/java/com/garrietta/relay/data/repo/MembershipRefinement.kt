package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.rcs.proto.RcsMessage
import com.garrietta.relay.rcs.proto.RcsParticipant

/**
 * Sender-evidence membership refinement (Garrett 2026-10-20, the Dad
 * fix): for a conversation whose payload ships NO field 21 and whose
 * field 10 says NOT a group, the derivation collapses the stale
 * field-20 list to a single peer — by the server's conversation name
 * when it can, else provisionally to the first cleaned other. The
 * conversation's OWN message history is the stronger signal: when it
 * shows at least 3 incoming messages from EXACTLY ONE distinct other
 * sender, that sender IS the peer, whatever the name/first-entry
 * heuristics picked. This class is the decision + the in-memory record
 * that lets the refinement ride every refresh (the ReadLatch #159
 * pattern): nothing is persisted to disk, and server state wins again
 * on cold start.
 *
 * The record lives only while the payload stays field-21-absent and
 * not-a-group: the repository retires it the moment a refresh shows
 * the conversation shipping field 21 or flagged a group, at which
 * point the derivation is authoritative on its own.
 *
 * THE SENDER UNION (Garrett 2026-10-20, Game Night): the peer record
 * above is a REPLACEMENT — one person in, everyone else out. The
 * union record is the opposite shape: field 21 proved to be a SUBSET
 * of membership, not the roster (Game Night: field 21 named 4 of 9
 * field-20 others, and the 5 excluded are active senders), so for
 * every conversation the collapse does NOT govern, the stored row's
 * membership is the derived others UNION every distinct other-sender
 * the conversation's OWN loaded messages evidence. Anyone who sends
 * in a conversation is a member of it. The union adds ONLY evidenced
 * senders resolved in the conversation's own field-20 table — a
 * field-20 entry that never sends (Grace in "Dad, Haley") stays out,
 * and a sender with no field-20 entry at all is counted in the census
 * as unmatched, never fabricated into a member. The union retires
 * when the payload catches up (a refresh's derivation covers every
 * evidenced sender) or the conversation falls into the collapse
 * shape. Same in-memory discipline as the peer record: nothing on
 * disk, server state wins on cold start, and rows for never-opened
 * chats keep the derived membership until their first load.
 */
class MembershipRefinements {

    /** One refinement entry: the wire entry + the row-ready participant. */
    class RefinedPeer(
        val entry: RcsParticipant,
        val participant: Participant,
    )

    /** Server conversation id -> the evidence-backed single peer. */
    private val refined = mutableMapOf<String, RefinedPeer>()

    /**
     * Server conversation id -> sender-union additions: evidenced
     * senders the derivation left out, in field-20 order.
     */
    private val unions = mutableMapOf<String, List<RefinedPeer>>()

    /** Records (or refreshes) the refined peer for [conversationId]. */
    @Synchronized
    fun record(conversationId: String, entry: RcsParticipant, peer: Participant) {
        refined[conversationId] = RefinedPeer(entry, peer)
    }

    /** Retires the refinement for [conversationId], if one is held. */
    @Synchronized
    fun clear(conversationId: String) {
        refined.remove(conversationId)
    }

    @Synchronized
    fun isRecorded(conversationId: String): Boolean =
        conversationId in refined

    /** The wire participant entry the refinement rests on, if any. */
    @Synchronized
    fun peerEntry(conversationId: String): RcsParticipant? =
        refined[conversationId]?.entry

    /** Records (or replaces) the sender-union additions for [conversationId]. */
    @Synchronized
    fun recordUnion(conversationId: String, additions: List<RefinedPeer>) {
        if (additions.isEmpty()) {
            unions.remove(conversationId)
        } else {
            unions[conversationId] = additions
        }
    }

    /** Retires the sender-union record for [conversationId], if held. */
    @Synchronized
    fun clearUnion(conversationId: String) {
        unions.remove(conversationId)
    }

    @Synchronized
    fun isUnionRecorded(conversationId: String): Boolean =
        conversationId in unions

    /** The wire entries the union adds for [conversationId], if any. */
    @Synchronized
    fun unionEntries(conversationId: String): List<RcsParticipant> =
        unions[conversationId].orEmpty().map { it.entry }

    /**
     * Applies every held refinement to a conversation list (called on
     * each refresh emission, and immediately when a refinement is
     * recorded mid-session). A peer-refined row shows exactly the
     * evidence peer as its only participant, never as a group; its
     * address and photo follow the peer, mirroring how the mapping
     * pass derives them from the others list. A union row keeps its
     * derived participants and GAINS the recorded additions it doesn't
     * already carry (matched by number, so a payload that later ships
     * field 21 covering a sender simply absorbs the addition); its
     * group-ness is the grown count. The two records never govern the
     * same conversation — the shapes are mutually exclusive — but if
     * both were somehow held, the peer record wins. Returns the input
     * list unchanged when no row needed rewriting.
     */
    @Synchronized
    fun applyTo(rows: List<Conversation>): List<Conversation> {
        if (refined.isEmpty() && unions.isEmpty()) return rows
        var changed = false
        val out = rows.map { row ->
            val id = row.rcsConversationId
            val peer = id?.let { refined[it]?.participant }
            when {
                peer != null -> when {
                    !row.isGroup && row.participants == listOf(peer) &&
                        row.address == peer.address -> row
                    else -> {
                        changed = true
                        row.copy(
                            participants = listOf(peer),
                            isGroup = false,
                            address = peer.address,
                            photoUri = peer.photoUri,
                        )
                    }
                }
                id != null && unions.containsKey(id) -> {
                    val have = row.participants
                        .map { numberKey(it.address) }.toSet()
                    val extra = unions.getValue(id)
                        .map { it.participant }
                        .filter { numberKey(it.address) !in have }
                    if (extra.isEmpty()) {
                        row
                    } else {
                        changed = true
                        val grown = row.participants + extra
                        row.copy(
                            participants = grown,
                            isGroup = grown.size > 1,
                        )
                    }
                }
                else -> row
            }
        }
        return if (changed) out else rows
    }

    /** Number identity for row-level dedupe: digits, last 10. */
    private fun numberKey(address: String): String =
        address.filter(Char::isDigit).takeLast(10)
}

/**
 * The sender-evidence decision, pure: the single distinct other
 * sender's normalized number when a field-21-absent, not-a-group
 * conversation's loaded history carries at least 3 incoming messages
 * from exactly one distinct other sender — else null (no refinement:
 * the evidence is absent, thin, or points at more than one person).
 */
fun senderEvidencePeerNumber(
    field21Present: Boolean,
    serverIsGroup: Boolean,
    incomingCount: Int,
    distinctSenderNumbers: Collection<String>,
): String? =
    if (!field21Present && !serverIsGroup && incomingCount >= 3 &&
        distinctSenderNumbers.size == 1
    ) {
        distinctSenderNumbers.first()
    } else {
        null
    }

/**
 * The sender-union gate, pure: sender evidence may ADD members for
 * every payload shape EXCEPT the field-21-absent, not-a-group one —
 * there the collapse governs membership and sender evidence SELECTS
 * the peer instead (see [senderEvidencePeerNumber]); it must never
 * union that conversation into a group.
 */
fun senderUnionGoverns(field21Present: Boolean, serverIsGroup: Boolean): Boolean =
    field21Present || serverIsGroup

/**
 * The union additions, pure: entries of [pool] — the derivation's own
 * cleaned field-20 universe (non-self, non-ghost) — whose
 * participantId is an evidenced sender but which the derivation left
 * out ([derivedPids]). A silent field-20 entry is never added, and a
 * sender whose participantId has no field-20 entry cannot be added
 * (no fabricated members — the census counts those as unmatched).
 */
fun unionAdditionEntries(
    pool: List<RcsParticipant>,
    senderPids: Set<String>,
    derivedPids: Set<String>,
): List<RcsParticipant> =
    pool.filter {
        it.participantId in senderPids && it.participantId !in derivedPids
    }

/**
 * Union retirement coverage, pure: every evidenced sender is already
 * in the derivation's others — the payload caught up, so the
 * derivation is authoritative on its own again.
 */
fun unionCoveredByDerivation(
    senderPids: Set<String>,
    derivedPids: Set<String>,
): Boolean =
    senderPids.isNotEmpty() && senderPids.all { it in derivedPids }

/**
 * Sender census for ONE conversation (Garrett 2026-10-20): who
 * actually sends, from the history the repository has loaded. Feeds
 * the Diagnostics membership dump (per-entry `sent=N` markers) and
 * the sender union's retirement checks. Counts are a LOWER BOUND:
 * they describe loaded/cached history, not the server's full record.
 */
data class SenderCensus(
    /** participantId -> incoming message count, for senders whose
     *  participantId resolves to a cleaned (non-self, non-ghost)
     *  field-20 entry of this conversation. */
    val sentByPid: Map<String, Int> = emptyMap(),
    /** Distinct participantIds that sent but resolve to NO field-20
     *  entry at all — counted, never fabricated into members. */
    val unmatchedPids: Set<String> = emptySet(),
    /** Incoming messages from those unresolvable participantIds. */
    val unmatchedMessages: Int = 0,
) {
    /**
     * Session merge: successive loads (chat open, then pagination)
     * widen the history window, so per-sender counts keep the MAX any
     * single load observed — summing would double-count the overlap
     * every load re-reads.
     */
    fun mergedWith(other: SenderCensus): SenderCensus = SenderCensus(
        sentByPid = (sentByPid.keys + other.sentByPid.keys).associateWith { pid ->
            maxOf(sentByPid[pid] ?: 0, other.sentByPid[pid] ?: 0)
        },
        unmatchedPids = unmatchedPids + other.unmatchedPids,
        unmatchedMessages = maxOf(unmatchedMessages, other.unmatchedMessages),
    )
}

/**
 * Computes the census for one loaded history, pure: incoming messages
 * only, each sender resolved in THIS conversation's own participant
 * table ([byPid]). A sender counts toward [SenderCensus.sentByPid]
 * only when its entry survives the derivation's cleaning ([poolPids]
 * — the cleaned field-20 universe); a resolved self/ghost entry is
 * neither an "other sender" nor unmatched; an unresolvable
 * participantId is unmatched.
 */
fun computeSenderCensus(
    incoming: List<RcsMessage>,
    byPid: Map<String, RcsParticipant>,
    poolPids: Set<String>,
): SenderCensus {
    val sent = mutableMapOf<String, Int>()
    val unmatchedPids = mutableSetOf<String>()
    var unmatchedMessages = 0
    for (m in incoming) {
        val entry = byPid[m.participantId]
        when {
            entry == null -> {
                unmatchedPids.add(m.participantId)
                unmatchedMessages++
            }
            entry.participantId in poolPids -> {
                sent[entry.participantId] =
                    (sent[entry.participantId] ?: 0) + 1
            }
            // Resolved but self/ghost: not an other sender.
            else -> Unit
        }
    }
    return SenderCensus(sent, unmatchedPids, unmatchedMessages)
}
