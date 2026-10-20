package com.garrietta.relay.data.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-conversation TRUE mute (Garrett 2026-10-20, from the Google
 * Messages most-requested list: users complain "muted" chats still pop
 * up on screen). Relay posts its OWN notifications, so mute here is
 * total: the notification pipeline's single decision point
 * (RelayApp.handleIncomingRcsMessage) checks this store and posts
 * NOTHING for a muted conversation — no heads-up, no sound, no shade
 * entry. The inbox itself is untouched: messages still arrive, unread
 * state stays truthful in-app; only system notifications are silenced.
 *
 * Persistence mirrors [CategoryStore]: app-private SharedPreferences +
 * a StateFlow the UI collects. Keys are conversation keys in the
 * [CategoryStore.conversationKey] scheme
 * (rcsConversationId ?: "tel:$threadId") — for RCS conversations the
 * key IS the server conversation id, which is exactly the id the
 * notification pipeline holds for an incoming message.
 */
class MuteStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _mutedKeys = MutableStateFlow(loadMuted(prefs))
    /** Conversation keys with notifications fully muted. */
    val mutedKeys: StateFlow<Set<String>> = _mutedKeys.asStateFlow()

    fun isMuted(conversationKey: String): Boolean =
        conversationKey in _mutedKeys.value

    /** Mutes or unmutes a conversation; persists immediately. */
    fun setMuted(conversationKey: String, muted: Boolean) {
        val updated = if (muted) {
            _mutedKeys.value + conversationKey
        } else {
            _mutedKeys.value - conversationKey
        }
        if (updated == _mutedKeys.value) return
        _mutedKeys.value = updated
        prefs.edit().putStringSet(KEY_MUTED, updated).apply()
    }

    companion object {
        private const val PREFS = "relay_mutes"
        private const val KEY_MUTED = "muted_keys"

        private fun loadMuted(prefs: SharedPreferences): Set<String> =
            // Defensive copy: the set SharedPreferences hands out must
            // never be mutated in place.
            prefs.getStringSet(KEY_MUTED, emptySet())?.toSet() ?: emptySet()
    }
}
