package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Conversation

/**
 * Optimistic-read latch (Garrett 2026-10-20): "It takes a second for a
 * read message to mark as read in the homepage… you should be able to
 * anticipate that the chat is read by the idea that the message was
 * tapped open. So that tap on the unread chat marks it as read."
 *
 * Opening a chat tells the server to mark it read (the ChatViewModel
 * mark-read path, unchanged), but the inbox row's unread flag only
 * flipped when a later refresh landed with the server's updated
 * metadata — a visible lag on return to the homepage. The repository
 * now flips the row read locally at the tap (see
 * [UnifiedInboxRepository.markConversationOpened]) and records a latch
 * here so a stale refresh can't resurrect the unread flag in between
 * (read → unread → read flicker).
 *
 * A latch maps a conversation key ([CategoryStore.conversationKey] —
 * the server conversation id for RCS rows, "tel:<threadId>" for
 * Telephony rows) to the newest-message timestamp observed at open
 * time. While it holds, refresh emissions keep the row read UNLESS:
 *  - the emission itself reports the row read (the server caught up —
 *    latch satisfied, dropped), or
 *  - the emission's newest message is NEWER than the latched one (a
 *    genuinely new message arrived after the open — latch dropped,
 *    unread returns legitimately).
 *
 * In-memory only: process death drops every latch, and by then the
 * server state — which the mark-read RPC already updated — wins on
 * cold start, which is correct. Pure JVM logic; unit-tested.
 */
class ReadLatch {

    /** Conversation key -> newest-message timestamp (ms) seen at open. */
    private val latched = mutableMapOf<String, Long>()

    /** Records (or refreshes) the latch for [key] at open time. */
    @Synchronized
    fun latch(key: String, latestMessageTs: Long) {
        latched[key] = latestMessageTs
    }

    /**
     * Drops the latch for [key]. Used by manual state changes (Mark as
     * unread) so the user's explicit state is never overridden by a
     * latch their earlier tap left behind.
     */
    @Synchronized
    fun clear(key: String) {
        latched.remove(key)
    }

    @Synchronized
    fun isLatched(key: String): Boolean = key in latched

    /**
     * Applies every held latch to a freshly loaded conversation list
     * (called on each refresh emission before the list is published).
     * Returns the input list unchanged when no row needed holding, so
     * steady-state refreshes keep their identity.
     */
    @Synchronized
    fun applyTo(rows: List<Conversation>): List<Conversation> {
        if (latched.isEmpty()) return rows
        var held = false
        val out = rows.map { row ->
            val key = CategoryStore.conversationKey(row.rcsConversationId, row.threadId)
            val ts = latched[key]
            when {
                ts == null -> row
                // The server now agrees the conversation is read: the
                // latch has done its job and is satisfied.
                !row.unread -> {
                    latched.remove(key)
                    row
                }
                // A message NEWER than the one observed at open: the
                // conversation is genuinely unread again — release and
                // let the row show it.
                row.date > ts -> {
                    latched.remove(key)
                    row
                }
                // Stale echo: the server hasn't propagated the
                // mark-read yet. Hold the row read.
                else -> {
                    held = true
                    row.copy(unread = false)
                }
            }
        }
        return if (held) out else rows
    }
}
