package com.garrietta.relay.data.repo

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "current contact" color seed.
 *
 * Opening a chat sets the seed from that conversation's resolved contact
 * color: the Palette vibrant of the contact's photo when they have one,
 * else their assigned expressive palette's seed
 * ([com.garrietta.relay.ui.theme.ContactPalettes.resolveContactSeedArgb]).
 * The seed persists across process death and applies to the WHOLE app —
 * backing out to the inbox keeps the last-viewed contact's color identity
 * instead of reverting to the plain wallpaper scheme; the chat details
 * page themes from it too.
 *
 * Replacement rule: EVERY opened chat replaces the seed with its resolved
 * seed (Garrett 2026-10-20 — a photo-less chat's assigned palette color
 * tints the app just like a photo color does). First-ever state (nothing
 * opened yet): null → the wallpaper dynamic scheme, as before.
 */
class AppSeedStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _seed = MutableStateFlow(load())
    /** Null = no seed yet → wallpaper dynamic scheme. */
    val seed: StateFlow<Color?> = _seed.asStateFlow()

    fun setSeed(color: Color) {
        _seed.value = color
        prefs.edit().putInt(KEY_SEED, color.toArgb()).apply()
    }

    fun clear() {
        _seed.value = null
        prefs.edit().remove(KEY_SEED).apply()
    }

    private fun load(): Color? {
        val argb = prefs.getInt(KEY_SEED, 0)
        return if (argb != 0) Color(argb) else null
    }

    companion object {
        private const val PREFS = "relay_app_seed"
        private const val KEY_SEED = "seed_argb"
    }
}
