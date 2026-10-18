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
 * Per-contact chat theming: seeds a FULL Material 3 color scheme from the
 * vibrant color pulled out of the contact's profile photo (Palette API, same
 * pattern as Koda's artwork theming).
 *
 * Every tonal role is derived from the seed — not just primary — and every
 * text-bearing pair is verified against WCAG AA (4.5:1), nudging the
 * foreground toward black/white until it passes. The wallpaper dynamic
 * scheme remains the fallback (and the inbox scheme) when [seed] is null.
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
    fun blendTo(target: Int, ratio: Float): Int = ColorUtils.blendARGB(s, target, ratio)
    fun c(argb: Int) = Color(argb)

    // Tonal roles from the seed. Blend ratios are ordered so the surface
    // ramp reads correctly: dim < surface < lowest < low < container <
    // high < highest < bright (light), mirrored for dark.
    val scheme = if (!darkTheme) {
        fallback.copy(
            primary = c(s),
            onPrimary = c(if (seedIsDark) white else black),
            primaryContainer = c(blendTo(white, 0.80f)),
            onPrimaryContainer = c(blendTo(black, 0.62f)),
            secondary = c(blendTo(white, 0.30f)),
            onSecondary = c(if (seedIsDark) white else black),
            secondaryContainer = c(blendTo(white, 0.84f)),
            onSecondaryContainer = c(blendTo(black, 0.62f)),
            tertiary = c(blendTo(black, 0.12f)),
            onTertiary = c(if (seedIsDark) white else black),
            tertiaryContainer = c(blendTo(white, 0.76f)),
            onTertiaryContainer = c(blendTo(black, 0.62f)),
            background = c(blendTo(white, 0.96f)),
            onBackground = fallback.onBackground,
            surface = c(blendTo(white, 0.95f)),
            onSurface = fallback.onSurface,
            surfaceVariant = c(blendTo(white, 0.88f)),
            onSurfaceVariant = fallback.onSurfaceVariant,
            surfaceTint = c(s),
            surfaceDim = c(blendTo(white, 0.80f)),
            surfaceBright = c(blendTo(white, 0.98f)),
            surfaceContainerLowest = c(blendTo(white, 0.99f)),
            surfaceContainerLow = c(blendTo(white, 0.94f)),
            surfaceContainer = c(blendTo(white, 0.91f)),
            surfaceContainerHigh = c(blendTo(white, 0.87f)),
            surfaceContainerHighest = c(blendTo(white, 0.83f)),
            inverseSurface = c(blendTo(black, 0.82f)),
            inverseOnSurface = c(blendTo(white, 0.90f)),
            inversePrimary = c(blendTo(white, 0.55f)),
            outline = c(blendTo(black, 0.38f)),
            outlineVariant = c(blendTo(black, 0.14f)),
            // scrim and error roles intentionally stay on the fallback:
            // errors must never be tinted by a contact color.
        )
    } else {
        val primary = c(blendTo(white, if (seedIsDark) 0.28f else 0.12f))
        fallback.copy(
            primary = primary,
            onPrimary = c(if (seedIsDark) black else blendTo(black, 0.75f)),
            primaryContainer = c(blendTo(black, 0.70f)),
            onPrimaryContainer = c(blendTo(white, 0.82f)),
            secondary = c(blendTo(white, 0.18f)),
            onSecondary = fallback.onSecondary,
            secondaryContainer = c(blendTo(black, 0.66f)),
            onSecondaryContainer = c(blendTo(white, 0.82f)),
            tertiary = c(blendTo(white, 0.24f)),
            onTertiary = c(blendTo(black, 0.80f)),
            tertiaryContainer = c(blendTo(black, 0.62f)),
            onTertiaryContainer = c(blendTo(white, 0.82f)),
            background = c(blendTo(black, 0.95f)),
            onBackground = fallback.onBackground,
            surface = c(blendTo(black, 0.94f)),
            onSurface = fallback.onSurface,
            surfaceVariant = c(blendTo(black, 0.86f)),
            onSurfaceVariant = fallback.onSurfaceVariant,
            surfaceTint = primary,
            surfaceDim = c(blendTo(black, 0.95f)),
            surfaceBright = c(blendTo(black, 0.65f)),
            surfaceContainerLowest = c(blendTo(black, 0.97f)),
            surfaceContainerLow = c(blendTo(black, 0.90f)),
            surfaceContainer = c(blendTo(black, 0.86f)),
            surfaceContainerHigh = c(blendTo(black, 0.80f)),
            surfaceContainerHighest = c(blendTo(black, 0.72f)),
            inverseSurface = c(blendTo(white, 0.88f)),
            inverseOnSurface = c(blendTo(black, 0.80f)),
            inversePrimary = c(blendTo(black, 0.35f)),
            outline = c(blendTo(white, 0.38f)),
            outlineVariant = c(blendTo(white, 0.14f)),
        )
    }
    return scheme.withVerifiedContrast()
}

/**
 * Contrast guard: every text-bearing pair must meet WCAG AA (4.5:1). Pairs
 * that don't are nudged toward black/white until they pass. This is the
 * design work the dynamic-color system won't do for a custom seed.
 */
private fun ColorScheme.withVerifiedContrast(): ColorScheme {
    fun fix(fg: Color, bg: Color): Color = Color(ensureContrast(fg.toArgb(), bg.toArgb()))
    return copy(
        onPrimary = fix(onPrimary, primary),
        onPrimaryContainer = fix(onPrimaryContainer, primaryContainer),
        onSecondary = fix(onSecondary, secondary),
        onSecondaryContainer = fix(onSecondaryContainer, secondaryContainer),
        onTertiary = fix(onTertiary, tertiary),
        onTertiaryContainer = fix(onTertiaryContainer, tertiaryContainer),
        onSurface = fix(onSurface, surface),
        onSurfaceVariant = fix(onSurfaceVariant, surfaceContainer),
        onBackground = fix(onBackground, background),
        inverseOnSurface = fix(inverseOnSurface, inverseSurface),
    )
}

private fun ensureContrast(fg: Int, bg: Int, minRatio: Float = 4.5f): Int {
    var c = fg
    repeat(24) {
        if (ColorUtils.calculateContrast(c, bg) >= minRatio) return c
        val toBlack = ColorUtils.calculateContrast(android.graphics.Color.BLACK, bg)
        val toWhite = ColorUtils.calculateContrast(android.graphics.Color.WHITE, bg)
        val target = if (toBlack > toWhite) android.graphics.Color.BLACK
        else android.graphics.Color.WHITE
        c = ColorUtils.blendARGB(c, target, 0.18f)
    }
    return c
}
