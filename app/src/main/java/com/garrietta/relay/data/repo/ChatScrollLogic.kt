package com.garrietta.relay.data.repo

/**
 * Pure scroll decisions for the chat screen (Garrett 2026-10-20): "When
 * I get a new message the chat opens up with the bottom at the location
 * of the last message in the chat, rather than scrolling freshly down
 * to the new message at the bottom" — and "when I send a message it
 * should scroll down to the bottom."
 *
 * The message list is newest-first (index 0 = newest) in a
 * reverseLayout column, so index 0 IS the visual bottom. A saved
 * position anchors on a message ID, which keeps pointing at the same
 * old message while the tail grows past it. These functions decide —
 * from message identity/time, never raw indices — when an open or a
 * live arrival must land on the bottom instead. Pure JVM logic;
 * unit-tested (ChatScrollLogicTest).
 */
object ChatScrollLogic {

    /**
     * Parses the persisted form written by [ChatScrollStore]:
     * "anchorId:offset" (legacy) or "anchorId:offset:newestId:newestDate"
     * (current). Ids may be negative (RCS message ids are server-id
     * hashCodes). Returns null for anything malformed.
     */
    fun parsePosition(raw: String): ScrollPosition? {
        val parts = raw.split(":")
        return when (parts.size) {
            2 -> {
                val id = parts[0].toLongOrNull() ?: return null
                val off = parts[1].toIntOrNull() ?: return null
                ScrollPosition(id, off)
            }
            4 -> {
                val id = parts[0].toLongOrNull() ?: return null
                val off = parts[1].toIntOrNull() ?: return null
                val newestId = parts[2].toLongOrNull() ?: return null
                val newestDate = parts[3].toLongOrNull() ?: return null
                ScrollPosition(id, off, newestId, newestDate)
            }
            else -> null
        }
    }

    /** Serializes [position] in the current 4-field form. */
    fun formatPosition(position: ScrollPosition): String =
        "${position.messageId}:${position.scrollOffset}:" +
            "${position.newestMessageId}:${position.newestMessageDate}"

    /**
     * Open-time decision: does the loaded history hold a message NEWER
     * than the tail Garrett saw when the position was saved? If so the
     * saved anchor is stale and the chat must open at the bottom.
     *
     * Current-format positions record the newest message's id + date at
     * save time, making this exact: a different newest id at an equal
     * or later date is a genuine arrival (an equal date covers a
     * same-millisecond arrival); a different id at an EARLIER date is
     * the tail shrinking (deletion/expiry), not growth, and the saved
     * position still rules.
     *
     * Legacy positions recorded only the anchor. There the best
     * available reading is: if the anchor is no longer the newest
     * loaded message, treat the tail as grown (Garrett's repro is
     * exactly this shape); if it still is the newest, restoring it and
     * landing at the bottom are the same view anyway.
     */
    fun hasNewerThanSaved(
        saved: ScrollPosition,
        loadedNewestId: Long,
        loadedNewestDate: Long,
    ): Boolean =
        // Current format records the save-time newest DATE (legacy
        // entries carry -1; real message dates are epoch ms > 0). The
        // newest ID can't be the discriminator: RCS ids are hashCodes
        // and legitimately negative.
        if (saved.newestMessageDate >= 0L) {
            loadedNewestId != saved.newestMessageId &&
                loadedNewestDate >= saved.newestMessageDate
        } else {
            loadedNewestId != saved.messageId
        }

    /**
     * Open-time recheck, armed when a chat that was left AT THE BOTTOM
     * restores onto its saved anchor from an early emission (the cache
     * paints instantly, before the network load lands). If a tail newer
     * than [savedNewestDate] then loads while the view still sits on
     * [anchorId], the open must re-land at the bottom — the cached
     * emission alone couldn't prove the tail had grown.
     */
    data class TailRecheck(val anchorId: Long, val savedNewestDate: Long)

    /** What the chat view should do when the tail (newest message) changes. */
    enum class TailAction { NONE, TO_BOTTOM }

    /**
     * Live-tail decision (Garrett 2026-10-20):
     * - HIS OWN send always scrolls to the bottom, wherever he was
     *   reading — his send wins unconditionally.
     * - An INCOMING arrival pins to the bottom only when he is already
     *   at the bottom (the newest message is the first visible item);
     *   scrolled up into history, the view is NEVER yanked.
     */
    fun onTailChanged(isOwnSend: Boolean, atBottom: Boolean): TailAction =
        if (isOwnSend || atBottom) TailAction.TO_BOTTOM else TailAction.NONE

    /**
     * Travel style for a scroll-to-bottom: animate short hops with the
     * standard list animation; teleport long ones (animating across
     * hundreds of bubbles is a slow, janky ride).
     */
    const val MAX_ANIMATED_ITEMS = 30

    fun useAnimatedScroll(firstVisibleIndex: Int): Boolean =
        firstVisibleIndex <= MAX_ANIMATED_ITEMS
}
