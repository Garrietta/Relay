package com.garrietta.relay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Per-contact chat theming: seeds a Material 3 color scheme from the vibrant
 * color pulled out of the contact's profile photo (Palette API, same pattern
 * as Koda's artwork theming).
 *
 * The seed drives the primary roles and tints the surfaces/background, so
 * each conversation gets its own color identity over a themed background.
 * Every other tonal role falls through to the [fallback] scheme, keeping the
 * result readable. When [seed] is null the fallback (wallpaper dynamic) is
 * used unchanged.
 */
@Composable
fun rememberContactChatScheme(seed: Color?): ColorScheme {
    val fallback = MaterialTheme.colorScheme
    // Infer dark/light from the actual background luminance so this stays
    // correct under System/Light/Dark theme modes alike.
    val darkTheme = fallback.background.luminance() < 0.5f
    return remember(seed, fallback) {
        seed?.let { seededChatColorScheme(it, darkTheme, fallback) } ?: fallback
    }
}

fun seededChatColorScheme(seed: Color, darkTheme: Boolean, fallback: ColorScheme): ColorScheme {
    val s = seed.toArgb()
    val white = android.graphics.Color.WHITE
    val black = android.graphics.Color.BLACK
    val seedIsDark = seed.luminance() < 0.5f
    fun blendTo(target: Int, ratio: Float): Color =
        Color(ColorUtils.blendARGB(s, target, ratio))

    return if (!darkTheme) {
        fallback.copy(
            primary = Color(s),
            onPrimary = Color(if (seedIsDark) white else black),
            primaryContainer = blendTo(white, 0.80f),
            onPrimaryContainer = blendTo(black, 0.62f),
            secondary = blendTo(white, 0.30f),
            onSecondary = Color(if (seedIsDark) white else black),
            secondaryContainer = blendTo(white, 0.84f),
            onSecondaryContainer = blendTo(black, 0.62f),
            tertiary = blendTo(black, 0.12f),
            tertiaryContainer = blendTo(white, 0.76f),
            background = blendTo(white, 0.96f),
            onBackground = fallback.onBackground,
            surface = blendTo(white, 0.95f),
            onSurface = fallback.onSurface,
            surfaceContainerLowest = blendTo(white, 0.97f),
            surfaceContainerLow = blendTo(white, 0.94f),
            surfaceContainer = blendTo(white, 0.91f),
            surfaceContainerHigh = blendTo(white, 0.87f),
            surfaceContainerHighest = blendTo(white, 0.83f),
        )
    } else {
        val primary = blendTo(white, if (seedIsDark) 0.28f else 0.12f)
        fallback.copy(
            primary = primary,
            onPrimary = Color(if (seedIsDark) black else blendTo(black, 0.75f).toArgb()),
            primaryContainer = blendTo(black, 0.70f),
            onPrimaryContainer = blendTo(white, 0.82f),
            secondary = blendTo(white, 0.18f),
            secondaryContainer = blendTo(black, 0.66f),
            onSecondaryContainer = blendTo(white, 0.82f),
            tertiary = blendTo(white, 0.24f),
            tertiaryContainer = blendTo(black, 0.62f),
            background = blendTo(black, 0.95f),
            surface = blendTo(black, 0.94f),
            surfaceContainerLowest = blendTo(black, 0.97f),
            surfaceContainerLow = blendTo(black, 0.94f),
            surfaceContainer = blendTo(black, 0.90f),
            surfaceContainerHigh = blendTo(black, 0.85f),
            surfaceContainerHighest = blendTo(black, 0.80f),
        )
    }
}
