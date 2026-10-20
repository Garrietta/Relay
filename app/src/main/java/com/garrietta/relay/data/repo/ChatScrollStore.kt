package com.garrietta.relay.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-conversation chat scroll position.
 *
 * Garrett 2026-10-19: exiting a chat and reopening it jumped back to the
 * top; it must restore the exact position where he left off. The position
 * is keyed by [CategoryStore.conversationKey] (stable across restarts) and
 * stored as (messageId, scrollOffset): the id of the first visible message
 * plus the pixel offset inside it. Restoring by MESSAGE ID (not raw index)
 * survives the list growing underneath (cache → 50 → deep-fill 300):
 * older messages append at higher indices, so the saved message keeps its
 * identity even as the list gets longer.
 *
 * Garrett 2026-10-20: the position ALSO records the newest message's
 * id + date at save time. A chat left at the bottom whose tail has since
 * grown must reopen at the NEW bottom, not restored onto the old tail —
 * [ChatScrollLogic.hasNewerThanSaved] makes that call exactly. Positions
 * saved before this recorded only the anchor; they still parse (the
 * newest fields read -1) and [ChatScrollLogic] treats them as legacy.
 *
 * SharedPreferences-backed (same pattern as CategoryStore); in-memory
 * StateFlow mirrors for synchronous reads on the UI thread.
 */
class ChatScrollStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _positions = MutableStateFlow(loadAll(prefs))
    val positions: StateFlow<Map<String, ScrollPosition>> = _positions.asStateFlow()

    /** Remembers where the user left off in [conversationKey]. */
    fun save(
        conversationKey: String,
        messageId: Long,
        scrollOffset: Int,
        newestMessageId: Long,
        newestMessageDate: Long,
    ) {
        val position = ScrollPosition(
            messageId, scrollOffset, newestMessageId, newestMessageDate,
        )
        val next = _positions.value + (conversationKey to position)
        _positions.value = next
        prefs.edit()
            .putString(
                KEY_PREFIX + conversationKey,
                ChatScrollLogic.formatPosition(position),
            )
            .apply()
    }

    fun positionFor(conversationKey: String): ScrollPosition? =
        _positions.value[conversationKey]

    fun clear(conversationKey: String) {
        _positions.value = _positions.value - conversationKey
        prefs.edit().remove(KEY_PREFIX + conversationKey).apply()
    }

    companion object {
        private const val PREFS = "relay_chat_scroll"
        private const val KEY_PREFIX = "scroll_"

        private fun loadAll(prefs: android.content.SharedPreferences): Map<String, ScrollPosition> {
            val out = mutableMapOf<String, ScrollPosition>()
            for ((k, v) in prefs.all) {
                if (!k.startsWith(KEY_PREFIX)) continue
                val raw = v as? String ?: continue
                val parsed = ChatScrollLogic.parsePosition(raw) ?: continue
                out[k.removePrefix(KEY_PREFIX)] = parsed
            }
            return out
        }
    }
}

/**
 * First-visible message id + pixel offset inside it, plus the newest
 * message's id + date in the list when the position was saved
 * (-1 when unknown — positions written before the newest fields
 * existed). The newest snapshot is what lets the open path tell
 * "left mid-history" (restore) from "left at the bottom, then the
 * tail grew" (open at the new bottom).
 */
data class ScrollPosition(
    val messageId: Long,
    val scrollOffset: Int,
    val newestMessageId: Long = -1L,
    val newestMessageDate: Long = -1L,
)
