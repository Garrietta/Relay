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
 * App-wide persistent contact color — RICHER than the chat-scoped scheme.
 * Garrett: "more color in the app in general." Blend ratios are pushed so
 * the seed reads clearly on backgrounds, surfaces, and containers (Koda
 * tints its entire UI from artwork; this is the same idea). Every
 * text-bearing pair still goes through the WCAG AA verifier — contrast is
 * non-negotiable, color density is not.
 */
fun seededAppColorScheme(seed: Color, darkTheme: Boolean, fallback: ColorScheme): ColorScheme {
    val s = seed.toArgb()
    val white = android.graphics.Color.WHITE
    val black = android.graphics.Color.BLACK
    val seedIsDark = seed.luminance() < 0.5f
    fun blendTo(target: Int, ratio: Float): Int = ColorUtils.blendARGB(s, target, ratio)
    fun c(argb: Int) = Color(argb)

    val scheme = if (!darkTheme) {
        fallback.copy(
            primary = c(s),
            onPrimary = c(if (seedIsDark) white else black),
            primaryContainer = c(blendTo(white, 0.62f)),
            onPrimaryContainer = c(blendTo(black, 0.55f)),
            secondary = c(blendTo(white, 0.22f)),
            onSecondary = c(if (seedIsDark) white else black),
            secondaryContainer = c(blendTo(white, 0.68f)),
            onSecondaryContainer = c(blendTo(black, 0.55f)),
            tertiary = c(blendTo(black, 0.10f)),
            onTertiary = c(if (seedIsDark) white else black),
            tertiaryContainer = c(blendTo(white, 0.58f)),
            onTertiaryContainer = c(blendTo(black, 0.55f)),
            background = c(blendTo(white, 0.88f)),
            onBackground = fallback.onBackground,
            surface = c(blendTo(white, 0.86f)),
            onSurface = fallback.onSurface,
            surfaceVariant = c(blendTo(white, 0.78f)),
            onSurfaceVariant = fallback.onSurfaceVariant,
            surfaceTint = c(s),
            surfaceDim = c(blendTo(white, 0.70f)),
            surfaceBright = c(blendTo(white, 0.94f)),
            surfaceContainerLowest = c(blendTo(white, 0.96f)),
            surfaceContainerLow = c(blendTo(white, 0.88f)),
            surfaceContainer = c(blendTo(white, 0.82f)),
            surfaceContainerHigh = c(blendTo(white, 0.74f)),
            surfaceContainerHighest = c(blendTo(white, 0.66f)),
            inverseSurface = c(blendTo(black, 0.82f)),
            inverseOnSurface = c(blendTo(white, 0.90f)),
            inversePrimary = c(blendTo(white, 0.45f)),
            outline = c(blendTo(black, 0.32f)),
            outlineVariant = c(blendTo(black, 0.12f)),
        )
    } else {
        val primary = c(blendTo(white, if (seedIsDark) 0.22f else 0.10f))
        fallback.copy(
            primary = primary,
            onPrimary = c(if (seedIsDark) black else blendTo(black, 0.72f)),
            primaryContainer = c(blendTo(black, 0.52f)),
            onPrimaryContainer = c(blendTo(white, 0.78f)),
            secondary = c(blendTo(white, 0.14f)),
            onSecondary = fallback.onSecondary,
            secondaryContainer = c(blendTo(black, 0.50f)),
            onSecondaryContainer = c(blendTo(white, 0.78f)),
            tertiary = c(blendTo(white, 0.18f)),
            onTertiary = c(blendTo(black, 0.78f)),
            tertiaryContainer = c(blendTo(black, 0.46f)),
            onTertiaryContainer = c(blendTo(white, 0.78f)),
            background = c(blendTo(black, 0.86f)),
            onBackground = fallback.onBackground,
            surface = c(blendTo(black, 0.84f)),
            onSurface = fallback.onSurface,
            surfaceVariant = c(blendTo(black, 0.74f)),
            onSurfaceVariant = fallback.onSurfaceVariant,
            surfaceTint = primary,
            surfaceDim = c(blendTo(black, 0.90f)),
            surfaceBright = c(blendTo(black, 0.55f)),
            surfaceContainerLowest = c(blendTo(black, 0.94f)),
            surfaceContainerLow = c(blendTo(black, 0.84f)),
            surfaceContainer = c(blendTo(black, 0.76f)),
            surfaceContainerHigh = c(blendTo(black, 0.66f)),
            surfaceContainerHighest = c(blendTo(black, 0.56f)),
            inverseSurface = c(blendTo(white, 0.88f)),
            inverseOnSurface = c(blendTo(black, 0.80f)),
            inversePrimary = c(blendTo(black, 0.30f)),
            outline = c(blendTo(white, 0.32f)),
            outlineVariant = c(blendTo(white, 0.12f)),
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

internal fun ensureContrast(fg: Int, bg: Int, minRatio: Float = 4.5f): Int {
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

/**
 * Per-sender bubble colors for GROUP chats: each incoming bubble is filled
 * with the color derived from THAT SENDER's avatar image (Palette seed), so
 * a group thread reads as a high-variety color conversation — Garrett's
 * "more colors" directive, scoped to where variety belongs.
 *
 * Returns (bubbleContainer, onBubbleContainer, senderNameTone). Every
 * text-bearing pair is verified against WCAG AA (4.5:1) via the same
 * [ensureContrast] guard as the chat schemes — contrast is non-negotiable,
 * color density is not. Null seed (no photo / extraction failed) falls back
 * to the neutral 1:1 incoming treatment so nothing ever looks broken.
 */
fun senderBubbleColors(
    seed: Color?,
    threadBackground: Color,
    darkTheme: Boolean,
    fallback: ColorScheme,
): Triple<Color, Color, Color> {
    if (seed == null) {
        return Triple(
            fallback.surfaceContainerHigh,
            fallback.onSurface,
            fallback.onSurfaceVariant,
        )
    }
    val s = seed.toArgb()
    val white = android.graphics.Color.WHITE
    val black = android.graphics.Color.BLACK
    fun blendTo(target: Int, ratio: Float) = Color(ColorUtils.blendARGB(s, target, ratio))
    // Pastel-but-present fill: the sender's color reads clearly without
    // shouting over the message text.
    val container = if (!darkTheme) blendTo(white, 0.74f) else blendTo(black, 0.60f)
    val onContainer = Color(
        ensureContrast(if (!darkTheme) black else white, container.toArgb())
    )
    // Sender name sits on the thread background, not the bubble.
    val nameTone = Color(ensureContrast(s, threadBackground.toArgb()))
    return Triple(container, onContainer, nameTone)
}

/**
 * Resolved bubble colors for a user-picked per-chat COLORWAY (Garrett
 * 2026-10-20, feature queue 3/7): both directions derived from the one
 * seed the user chose in Chat Details, with the same tonal discipline
 * as [senderBubbleColors] and every text pair verified against WCAG AA
 * (4.5:1) via [ensureContrast].
 *
 * GROUP RULE: with no colorway picked, group chats keep their
 * per-sender photo-derived bubble colors untouched. Picking a colorway
 * is an explicit override — incoming bubbles for EVERY sender take the
 * colorway's incoming pair (uniform, readable), while each sender's
 * NAME keeps its per-sender tone, so identity survives the override.
 * Outgoing bubbles take the colorway in 1:1 and group chats alike.
 */
data class ChatBubblePair(
    val outgoing: Color,
    val onOutgoing: Color,
    val incoming: Color,
    val onIncoming: Color,
)

fun chatBubblePair(seed: Color, darkTheme: Boolean): ChatBubblePair {
    val s = seed.toArgb()
    val white = android.graphics.Color.WHITE
    val black = android.graphics.Color.BLACK
    fun blendTo(target: Int, ratio: Float) = Color(ColorUtils.blendARGB(s, target, ratio))
    // Outgoing carries the hue with presence; incoming is the same hue
    // as a quieter pastel (light) / deeper tint (dark) so the two
    // directions stay distinguishable at a glance.
    val outgoing = if (!darkTheme) blendTo(white, 0.42f) else blendTo(black, 0.30f)
    val incoming = if (!darkTheme) blendTo(white, 0.78f) else blendTo(black, 0.58f)
    val onBase = if (!darkTheme) black else white
    return ChatBubblePair(
        outgoing = outgoing,
        onOutgoing = Color(ensureContrast(onBase, outgoing.toArgb())),
        incoming = incoming,
        onIncoming = Color(ensureContrast(onBase, incoming.toArgb())),
    )
}
