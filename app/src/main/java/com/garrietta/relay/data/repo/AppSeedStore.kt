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
 * Opening a chat whose contact HAS a photo sets the seed from that photo
 * (Palette vibrant). The seed persists across process death and applies to
 * the WHOLE app — backing out to the inbox keeps the last-viewed contact's
 * color identity instead of reverting to the plain wallpaper scheme.
 *
 * Replacement rule: only a chat WITH a new contact photo replaces the seed.
 * Chats without photos keep the current seed. First-ever state (nothing
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
