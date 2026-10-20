package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recently used stickers (Garrett 2026-10-20, feature queue 5/7): the
 * sticker panel surfaces last-used stickers first, like every other
 * client's sticker picker. Persistence mirrors [MuteStore]:
 * app-private SharedPreferences + a StateFlow the UI collects.
 *
 * Stores sticker IDS only (comma-joined; ids are [a-z0-9-] by
 * construction in the pack) — the pack itself defines the artwork, so
 * a stored id that a future pack retires simply resolves to nothing
 * and drops out of the row.
 */
class StickerRecentsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _recentIds = MutableStateFlow(loadRecents(prefs))
    /** Sticker ids, most recently used first. */
    val recentIds: StateFlow<List<String>> = _recentIds.asStateFlow()

    /** Moves [id] to the front of the recents list; persists immediately. */
    fun record(id: String) {
        val updated = (listOf(id) + _recentIds.value.filterNot { it == id })
            .take(MAX_RECENTS)
        if (updated == _recentIds.value) return
        _recentIds.value = updated
        prefs.edit().putString(KEY_RECENTS, updated.joinToString(",")).apply()
    }

    companion object {
        private const val PREFS = "relay_sticker_recents"
        private const val KEY_RECENTS = "recent_ids"
        private const val MAX_RECENTS = 14

        private fun loadRecents(prefs: SharedPreferences): List<String> =
            prefs.getString(KEY_RECENTS, null)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.take(MAX_RECENTS)
                ?: emptyList()
    }
}
