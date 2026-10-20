package com.garrietta.relay.ui.theme

/**
 * The seed-derived tonal roles of Relay's two seeded schemes,
 * extracted as pure ARGB math (Garrett 2026-10-20, contrast round).
 *
 * Until now [seededChatColorScheme] / [seededAppColorScheme] in
 * ContactTheme.kt built their roles inline with ColorUtils blends, so
 * the derivation could not be exercised off-device. The blend ratios
 * below are transcribed 1:1 from those builders — the schemes render
 * the same tones they always did — with ONE change at the end of the
 * derivation: the surface tones run the adjacent-separation pass
 * ([SurfaceTones.withAdjacentSeparation]), so every container that
 * sits on another surface clears its [AdjacentFloors] minimum for
 * every seed in both themes. That pass is where "colors contrast
 * more" lives; the roles a test asserts on here are the exact ints
 * the app paints.
 *
 * [onSecondary] is null in dark theme for both variants: the builders
 * deliberately keep the fallback scheme's onSecondary there.
 */
data class SeedSchemeTones(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int?,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val surfaceDim: Int,
    val surfaceBright: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
    val outline: Int,
    val outlineVariant: Int,
)

/** Which seeded scheme to derive: the chat-scoped one or the richer
 *  app-wide one (Theme.kt's "more color in the app in general"). */
enum class SeedSchemeVariant { CHAT, APP }

private const val WHITE = -0x1 // 0xFFFFFFFF
private const val BLACK = -0x1000000 // 0xFF000000

fun seedSchemeTones(
    seedArgb: Int,
    darkTheme: Boolean,
    variant: SeedSchemeVariant,
): SeedSchemeTones {
    val s = seedArgb
    val seedIsDark = relativeLuminanceArgb(s) < 0.5
    fun toWhite(ratio: Float) = blendArgb(s, WHITE, ratio)
    fun toBlack(ratio: Float) = blendArgb(s, BLACK, ratio)

    val chat = variant == SeedSchemeVariant.CHAT
    val primary: Int
    val onPrimary: Int
    val primaryContainer: Int
    val onPrimaryContainer: Int
    val secondary: Int
    val onSecondary: Int?
    val secondaryContainer: Int
    val onSecondaryContainer: Int
    val tertiary: Int
    val onTertiary: Int
    val tertiaryContainer: Int
    val onTertiaryContainer: Int
    val background: Int
    val surface: Int
    val surfaceVariant: Int
    val surfaceDim: Int
    val surfaceBright: Int
    val lowest: Int
    val low: Int
    val container: Int
    val high: Int
    val highest: Int
    val inverseSurface: Int
    val inverseOnSurface: Int
    val inversePrimary: Int
    val outline: Int
    val outlineVariant: Int

    if (!darkTheme) {
        primary = s
        onPrimary = if (seedIsDark) WHITE else BLACK
        primaryContainer = toWhite(if (chat) 0.80f else 0.62f)
        onPrimaryContainer = toBlack(if (chat) 0.62f else 0.55f)
        secondary = toWhite(if (chat) 0.30f else 0.22f)
        onSecondary = if (seedIsDark) WHITE else BLACK
        secondaryContainer = toWhite(if (chat) 0.84f else 0.68f)
        onSecondaryContainer = toBlack(if (chat) 0.62f else 0.55f)
        tertiary = toBlack(if (chat) 0.12f else 0.10f)
        onTertiary = if (seedIsDark) WHITE else BLACK
        tertiaryContainer = toWhite(if (chat) 0.76f else 0.58f)
        onTertiaryContainer = toBlack(if (chat) 0.62f else 0.55f)
        background = toWhite(if (chat) 0.96f else 0.88f)
        surface = toWhite(if (chat) 0.95f else 0.86f)
        surfaceVariant = toWhite(if (chat) 0.88f else 0.78f)
        surfaceDim = toWhite(if (chat) 0.80f else 0.70f)
        surfaceBright = toWhite(if (chat) 0.98f else 0.94f)
        lowest = toWhite(if (chat) 0.99f else 0.96f)
        low = toWhite(if (chat) 0.94f else 0.88f)
        container = toWhite(if (chat) 0.91f else 0.82f)
        high = toWhite(if (chat) 0.87f else 0.74f)
        highest = toWhite(if (chat) 0.83f else 0.66f)
        inverseSurface = toBlack(0.82f)
        inverseOnSurface = toWhite(0.90f)
        inversePrimary = toWhite(if (chat) 0.55f else 0.45f)
        outline = toBlack(if (chat) 0.38f else 0.32f)
        outlineVariant = toBlack(if (chat) 0.14f else 0.12f)
    } else {
        primary = toWhite(
            if (chat) {
                if (seedIsDark) 0.28f else 0.12f
            } else {
                if (seedIsDark) 0.22f else 0.10f
            },
        )
        onPrimary = if (seedIsDark) BLACK else toBlack(if (chat) 0.75f else 0.72f)
        primaryContainer = toBlack(if (chat) 0.70f else 0.52f)
        onPrimaryContainer = toWhite(if (chat) 0.82f else 0.78f)
        secondary = toWhite(if (chat) 0.18f else 0.14f)
        onSecondary = null // both builders keep the fallback's onSecondary in dark
        secondaryContainer = toBlack(if (chat) 0.66f else 0.50f)
        onSecondaryContainer = toWhite(if (chat) 0.82f else 0.78f)
        tertiary = toWhite(if (chat) 0.24f else 0.18f)
        onTertiary = toBlack(if (chat) 0.80f else 0.78f)
        tertiaryContainer = toBlack(if (chat) 0.62f else 0.46f)
        onTertiaryContainer = toWhite(if (chat) 0.82f else 0.78f)
        background = toBlack(if (chat) 0.95f else 0.86f)
        surface = toBlack(if (chat) 0.94f else 0.84f)
        surfaceVariant = toBlack(if (chat) 0.86f else 0.74f)
        surfaceDim = toBlack(if (chat) 0.95f else 0.90f)
        surfaceBright = toBlack(if (chat) 0.65f else 0.55f)
        lowest = toBlack(if (chat) 0.97f else 0.94f)
        low = toBlack(if (chat) 0.90f else 0.84f)
        container = toBlack(if (chat) 0.86f else 0.76f)
        high = toBlack(if (chat) 0.80f else 0.66f)
        highest = toBlack(if (chat) 0.72f else 0.56f)
        inverseSurface = toWhite(0.88f)
        inverseOnSurface = toBlack(0.80f)
        inversePrimary = toBlack(if (chat) 0.35f else 0.30f)
        outline = toWhite(if (chat) 0.38f else 0.32f)
        outlineVariant = toWhite(if (chat) 0.14f else 0.12f)
    }

    // The contrast discipline: containers that touch other surfaces
    // clear their adjacent-separation floors before anything paints.
    // Background is the fixed reference; text pairs are verified
    // afterwards against these final fills (withVerifiedContrast).
    val separated = SurfaceTones(
        background = background,
        surfaceContainerLow = low,
        surfaceContainer = container,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        primaryContainer = primaryContainer,
        secondaryContainer = secondaryContainer,
    ).withAdjacentSeparation(darkTheme)

    return SeedSchemeTones(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = separated.primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = separated.secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = lowest,
        surfaceContainerLow = separated.surfaceContainerLow,
        surfaceContainer = separated.surfaceContainer,
        surfaceContainerHigh = separated.surfaceContainerHigh,
        surfaceContainerHighest = separated.surfaceContainerHighest,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary,
        outline = outline,
        outlineVariant = outlineVariant,
    )
}
