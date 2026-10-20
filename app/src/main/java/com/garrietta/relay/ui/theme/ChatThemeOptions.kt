package com.garrietta.relay.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * The curated per-chat theme catalog (Garrett 2026-10-20, feature queue
 * 3/7). A theme is two independent picks per conversation — a BACKGROUND
 * (soft two-stop vertical gradient) and a bubble COLORWAY — persisted as
 * plain ids in ChatThemeStore. "Default" is NOT an entry here: it is the
 * absence of a pick, and renders exactly as the app did before themes.
 *
 * Every seed is a mid-tone hue; the resolvers below derive the actual
 * surfaces per light/dark mode with the same tonal discipline as the
 * contact-seeded schemes (ContactTheme.kt): backgrounds stay a whisper
 * of tint over the surface ramp so message text and system notices stay
 * legible, and bubble pairs go through the WCAG AA contrast guard
 * (see [chatBubblePair]).
 */
data class ChatBackgroundOption(
    val id: String,
    val name: String,
    val seed: Color,
)

/** One curated bubble colorway: both bubble directions from one seed. */
data class ChatColorwayOption(
    val id: String,
    val name: String,
    val seed: Color,
)

val ChatBackgroundOptions: List<ChatBackgroundOption> = listOf(
    ChatBackgroundOption("meadow", "Meadow", Color(0xFF43A047)),
    ChatBackgroundOption("ocean", "Ocean", Color(0xFF1E88E5)),
    ChatBackgroundOption("lagoon", "Lagoon", Color(0xFF00897B)),
    ChatBackgroundOption("sunset", "Sunset", Color(0xFFFB8C00)),
    ChatBackgroundOption("honey", "Honey", Color(0xFFF9A825)),
    ChatBackgroundOption("rose", "Rose", Color(0xFFD81B60)),
    ChatBackgroundOption("berry", "Berry", Color(0xFF8E24AA)),
    ChatBackgroundOption("slate", "Slate", Color(0xFF546E7A)),
)

val ChatColorwayOptions: List<ChatColorwayOption> = listOf(
    ChatColorwayOption("relay", "Relay", Color(0xFF01AD41)),
    ChatColorwayOption("ocean", "Ocean", Color(0xFF1E88E5)),
    ChatColorwayOption("lagoon", "Lagoon", Color(0xFF00897B)),
    ChatColorwayOption("sunset", "Sunset", Color(0xFFFB8C00)),
    ChatColorwayOption("honey", "Honey", Color(0xFFF9A825)),
    ChatColorwayOption("rose", "Rose", Color(0xFFD81B60)),
    ChatColorwayOption("berry", "Berry", Color(0xFF8E24AA)),
    ChatColorwayOption("slate", "Slate", Color(0xFF546E7A)),
)

/** Resolves a stored background id; unknown ids render as Default. */
fun chatBackgroundOption(id: String?): ChatBackgroundOption? =
    ChatBackgroundOptions.firstOrNull { it.id == id }

/** Resolves a stored colorway id; unknown ids render as Default. */
fun chatColorwayOption(id: String?): ChatColorwayOption? =
    ChatColorwayOptions.firstOrNull { it.id == id }

/**
 * (top, bottom) tones of a background seed in the current mode: a soft
 * vertical falloff — lighter at the top behind the header, a touch
 * deeper at the bottom behind the composer. Both stops sit close to the
 * surface ramp (a whisper of the hue), so every foreground the chat
 * draws (notices, ticks, sender names) keeps the contrast it has on
 * the default surface.
 */
fun chatBackgroundTones(seed: Color, darkTheme: Boolean): Pair<Color, Color> {
    val s = seed.toArgb()
    val white = android.graphics.Color.WHITE
    val black = android.graphics.Color.BLACK
    fun blend(target: Int, ratio: Float) =
        Color(ColorUtils.blendARGB(s, target, ratio))
    return if (!darkTheme) {
        blend(white, 0.90f) to blend(white, 0.79f)
    } else {
        blend(black, 0.87f) to blend(black, 0.76f)
    }
}

/** The background gradient for a seed in the current mode. */
fun chatBackgroundBrush(seed: Color, darkTheme: Boolean): Brush {
    val (top, bottom) = chatBackgroundTones(seed, darkTheme)
    return Brush.verticalGradient(listOf(top, bottom))
}
