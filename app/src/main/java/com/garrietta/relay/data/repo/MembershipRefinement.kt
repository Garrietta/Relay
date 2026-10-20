package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Participant
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
 */
class MembershipRefinements {

    /** One refinement: the wire entry it rests on + the row-ready peer. */
    class RefinedPeer(
        val entry: RcsParticipant,
        val participant: Participant,
    )

    /** Server conversation id -> the evidence-backed single peer. */
    private val refined = mutableMapOf<String, RefinedPeer>()

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

    /**
     * Applies every held refinement to a conversation list (called on
     * each refresh emission, and immediately when a refinement is
     * recorded mid-session). A refined row shows exactly the evidence
     * peer as its only participant, never as a group; its address and
     * photo follow the peer, mirroring how the mapping pass derives
     * them from the others list. Returns the input list unchanged when
     * no row needed rewriting.
     */
    @Synchronized
    fun applyTo(rows: List<Conversation>): List<Conversation> {
        if (refined.isEmpty()) return rows
        var changed = false
        val out = rows.map { row ->
            val peer = row.rcsConversationId?.let { refined[it]?.participant }
            when {
                peer == null -> row
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
        }
        return if (changed) out else rows
    }
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
