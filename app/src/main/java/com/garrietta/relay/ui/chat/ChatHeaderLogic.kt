package com.garrietta.relay.ui.chat

import com.garrietta.relay.data.repo.ChatScrollLogic
import com.garrietta.relay.data.repo.ScrollPosition

/**
 * Pure header-layout decisions for the chat screen (Garrett 2026-10-20).
 *
 * The 1:1 header collapses on scroll (large photo → top-bar row). The
 * GROUP header must NOT: on his device video the "Dad, Grace" group
 * header rested at two different heights — the expanded form (76dp
 * avatar cluster inline) at the top of the thread and a compact
 * top-bar form once scrolled, because the group header consumed the
 * SAME scroll-collapse state as the 1:1 header and dropped its cluster
 * when it flipped. Every flip resized the Scaffold top bar, so the
 * message list's top inset jumped mid-scroll. His directive: "Group
 * chat header height should stay the larger of the two types you see
 * in this video."
 *
 * So: a group header's height is a CONSTANT — [GROUP_HEADER_HEIGHT_DP],
 * the expanded cluster row — in every state (entry, any scroll offset,
 * return from details). The collapse state machine keeps serving the
 * 1:1 header exactly as before; it simply never reaches a group.
 *
 * The cluster's scroll-EXIT is a separate axis (Garrett 2026-10-20,
 * clarifying the height directive): on vertical scroll the group
 * cluster still slides away to the left, as it did before #169 —
 * "just maintaining the header height as is." [groupClusterCollapsed]
 * decides ONLY the cluster's horizontal presence; it is never an
 * input to the height decision ([headerCollapsed]), and the header
 * composable pins the row height outright, so the exit animation is
 * height-neutral by construction. The two axes are tested
 * independently (ChatHeaderLogicTest).
 * Pure JVM logic; unit-tested (ChatHeaderLogicTest).
 */
object ChatHeaderLogic {

    /**
     * The group header row's constant content height: the expanded
     * presentation — back arrow, the 76dp avatar cluster box, and the
     * name/transport column centered against it. (The Scaffold adds
     * the status-bar inset above this row, as it does today.)
     */
    const val GROUP_HEADER_HEIGHT_DP = 76

    /**
     * Whether the header renders collapsed for a chat whose scroll
     * state says [scrollCollapsed]: groups NEVER collapse (their
     * height is the constant above); 1:1 chats follow the scroll
     * state exactly.
     */
    fun headerCollapsed(isGroup: Boolean, scrollCollapsed: Boolean): Boolean =
        !isGroup && scrollCollapsed

    /**
     * Whether a GROUP header's avatar cluster is scrolled away: the
     * raw scroll-collapse state drives it, for groups only, and it
     * affects ONLY the cluster's horizontal presence inside the
     * pinned-height row — the names column takes the freed width.
     * 1:1 chats have no cluster to exit (their own [headerCollapsed]
     * path is untouched).
     */
    fun groupClusterCollapsed(isGroup: Boolean, scrollCollapsed: Boolean): Boolean =
        isGroup && scrollCollapsed

    /**
     * Whether the saved position ITSELF says the chat was left at the
     * newest message: the snapshot's anchor IS the save-time newest
     * message, at offset 0. [ScrollPosition] records the newest
     * message's id + date when the position is saved (current
     * format: newestMessageDate >= 0), so this is decidable from the
     * snapshot alone — no live message list needed. Legacy snapshots
     * (no newest fields) cannot prove it and read false.
     */
    fun savedAtBottom(saved: ScrollPosition): Boolean =
        saved.newestMessageDate >= 0L &&
            saved.messageId == saved.newestMessageId &&
            saved.scrollOffset == 0

    /**
     * The header's collapse state at a chat's first composition —
     * the state the open's landing implies, decided BEFORE the
     * scroll-restore lands (the collapse driver stays frozen until
     * then, so this value is what renders at rest in the meantime).
     *
     * When a live emission is already in hand ([loadedNewestId] /
     * [loadedNewestDate] non-null — a retained ViewModel), the exact
     * reading applies: expanded when the saved anchor is the newest
     * message, or when the tail grew past the snapshot (that open
     * lands at the new bottom).
     *
     * When NO emission has landed yet — the common cold open, where
     * the ViewModel's first list arrives a beat after the first
     * frame — the decision falls back to the snapshot's own
     * save-time fields ([savedAtBottom]). The previous initializer
     * read this case as "opens scrolled" for EVERY chat with a saved
     * position: a chat left exactly at the bottom reopened with its
     * header already collapsed (Garrett 2026-10-20 — the group
     * cluster missing at rest after an at-bottom open), and the
     * wrong state could only correct once the restore landed, in
     * practice surfacing on his first scroll as a spurious
     * expand-then-collapse of the cluster.
     */
    fun initialHeaderCollapsed(
        saved: ScrollPosition?,
        loadedNewestId: Long?,
        loadedNewestDate: Long?,
    ): Boolean {
        if (saved == null) return false
        return if (loadedNewestId != null && loadedNewestDate != null) {
            !(ChatScrollLogic.hasNewerThanSaved(saved, loadedNewestId, loadedNewestDate) ||
                (saved.messageId == loadedNewestId && saved.scrollOffset == 0))
        } else {
            !savedAtBottom(saved)
        }
    }

    /**
     * What tapping "Add contact?" prefills in the system Contacts
     * app's new-contact page (Garrett 2026-10-20): the peer's phone
     * number, or their email for an email-identity peer (RCS iCloud
     * participants carry an email instead of a number).
     */
    sealed interface AddContactTarget {
        data class Phone(val number: String) : AddContactTarget
        data class Email(val email: String) : AddContactTarget
    }

    /**
     * Whether the 1:1 header shows "Add contact?" under the transport
     * tag, and what it prefills. The peer is UNKNOWN exactly when no
     * resolved name stands in front of their identity — the same
     * signal the inbox's Unknown category keys on for numbers
     * (Conversation.isUnknownNumber): the header title IS the bare
     * number. Groups and the self-chat never qualify, whatever their
     * titles say. The prefilled value is the peer's own address —
     * the full form Relay resolved for this conversation, the same
     * address the details page's Call/Video actions use — never the
     * possibly reformatted title.
     */
    fun addContactTarget(
        isGroup: Boolean,
        isSelf: Boolean,
        peerName: String,
        peerAddress: String,
    ): AddContactTarget? {
        if (isGroup || isSelf) return null
        val address = peerAddress.trim()
        if (address.isEmpty()) return null
        val title = peerName.trim()
        return if ('@' in address) {
            // Email identity: unknown while the title is the address
            // itself (or any email form) — a resolved contact name in
            // the title (e.g. the iCloud participant who IS in his
            // contacts) means they're already known.
            if (title == address || '@' in title) {
                AddContactTarget.Email(address)
            } else {
                null
            }
        } else {
            if (isBareNumberTitle(title)) {
                AddContactTarget.Phone(address)
            } else {
                null
            }
        }
    }

    /**
     * A title that is nothing but a phone number — the
     * Conversation.isUnknownNumber test applied to a title string: at
     * least one digit, and every character is a digit or phone
     * punctuation. A resolved contact name always fails this; the
     * UNKNOWN_SENDER! ghost's title fails it too (letters).
     */
    private fun isBareNumberTitle(title: String): Boolean {
        if (title.isEmpty() || !title.any { it.isDigit() }) return false
        return title.all { it.isDigit() || it in "+-(). " }
    }
}
