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
 * SharedPreferences-backed (same pattern as CategoryStore); in-memory
 * StateFlow mirrors for synchronous reads on the UI thread.
 */
class ChatScrollStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _positions = MutableStateFlow(loadAll(prefs))
    val positions: StateFlow<Map<String, ScrollPosition>> = _positions.asStateFlow()

    /** Remembers where the user left off in [conversationKey]. */
    fun save(conversationKey: String, messageId: Long, scrollOffset: Int) {
        val next = _positions.value + (conversationKey to ScrollPosition(messageId, scrollOffset))
        _positions.value = next
        prefs.edit()
            .putString(KEY_PREFIX + conversationKey, "$messageId:$scrollOffset")
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
                val parts = raw.split(":")
                if (parts.size != 2) continue
                val id = parts[0].toLongOrNull() ?: continue
                val off = parts[1].toIntOrNull() ?: continue
                out[k.removePrefix(KEY_PREFIX)] = ScrollPosition(id, off)
            }
            return out
        }
    }
}

/** First-visible message id + pixel offset inside it. */
data class ScrollPosition(val messageId: Long, val scrollOffset: Int)
