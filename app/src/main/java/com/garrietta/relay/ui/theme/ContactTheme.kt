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
 * Every tonal role is derived from the seed — not just primary — by the
 * pure derivation in SeedSchemeTones.kt, which also runs the
 * adjacent-surface separation pass (Garrett 2026-10-20: near-identical
 * tones budded against each other — the composer bar on the chat ground
 * with the field inside it — now clear their [AdjacentFloors] minimums).
 * Every text-bearing pair is then verified against WCAG AA (4.5:1) with
 * the hue-preserving guard ([ensureTextContrastArgb]). The wallpaper
 * dynamic scheme remains the fallback (and the inbox scheme) when
 * [seed] is null.
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

fun seededChatColorScheme(seed: Color, darkTheme: Boolean, fallback: ColorScheme): ColorScheme =
    seedScheme(seed, darkTheme, fallback, SeedSchemeVariant.CHAT)

/**
 * App-wide persistent contact color — RICHER than the chat-scoped scheme.
 * Garrett: "more color in the app in general." Blend ratios are pushed so
 * the seed reads clearly on backgrounds, surfaces, and containers (Koda
 * tints its entire UI from artwork; this is the same idea). Every
 * text-bearing pair still goes through the WCAG AA verifier — contrast is
 * non-negotiable, color density is not.
 */
fun seededAppColorScheme(seed: Color, darkTheme: Boolean, fallback: ColorScheme): ColorScheme =
    seedScheme(seed, darkTheme, fallback, SeedSchemeVariant.APP)

private fun seedScheme(
    seed: Color,
    darkTheme: Boolean,
    fallback: ColorScheme,
    variant: SeedSchemeVariant,
): ColorScheme {
    val t = seedSchemeTones(seed.toArgb(), darkTheme, variant)
    fun c(argb: Int) = Color(argb)
    return fallback.copy(
        primary = c(t.primary),
        onPrimary = c(t.onPrimary),
        primaryContainer = c(t.primaryContainer),
        onPrimaryContainer = c(t.onPrimaryContainer),
        secondary = c(t.secondary),
        onSecondary = t.onSecondary?.let { c(it) } ?: fallback.onSecondary,
        secondaryContainer = c(t.secondaryContainer),
        onSecondaryContainer = c(t.onSecondaryContainer),
        tertiary = c(t.tertiary),
        onTertiary = c(t.onTertiary),
        tertiaryContainer = c(t.tertiaryContainer),
        onTertiaryContainer = c(t.onTertiaryContainer),
        background = c(t.background),
        onBackground = fallback.onBackground,
        surface = c(t.surface),
        onSurface = fallback.onSurface,
        surfaceVariant = c(t.surfaceVariant),
        onSurfaceVariant = fallback.onSurfaceVariant,
        surfaceTint = c(t.primary),
        surfaceDim = c(t.surfaceDim),
        surfaceBright = c(t.surfaceBright),
        surfaceContainerLowest = c(t.surfaceContainerLowest),
        surfaceContainerLow = c(t.surfaceContainerLow),
        surfaceContainer = c(t.surfaceContainer),
        surfaceContainerHigh = c(t.surfaceContainerHigh),
        surfaceContainerHighest = c(t.surfaceContainerHighest),
        inverseSurface = c(t.inverseSurface),
        inverseOnSurface = c(t.inverseOnSurface),
        inversePrimary = c(t.inversePrimary),
        outline = c(t.outline),
        outlineVariant = c(t.outlineVariant),
        // scrim and error roles intentionally stay on the fallback:
        // errors must never be tinted by a contact color.
    ).withVerifiedContrast()
}

/**
 * The adjacent-separation pass for a scheme that did NOT come from a
 * seed — the wallpaper dynamic fallback. Runs the same SurfaceTones
 * discipline (plus the text verifier) so the unseeded app separates
 * its surfaces too. [background] itself never moves (MainActivity
 * paints the window from the dynamic scheme's background directly;
 * keeping the role identical keeps the two in lockstep).
 */
fun ColorScheme.withAdjacentSeparation(darkTheme: Boolean): ColorScheme {
    val separated = SurfaceTones(
        background = background.toArgb(),
        surfaceContainerLow = surfaceContainerLow.toArgb(),
        surfaceContainer = surfaceContainer.toArgb(),
        surfaceContainerHigh = surfaceContainerHigh.toArgb(),
        surfaceContainerHighest = surfaceContainerHighest.toArgb(),
        primaryContainer = primaryContainer.toArgb(),
        secondaryContainer = secondaryContainer.toArgb(),
    ).withAdjacentSeparation(darkTheme)
    return copy(
        surfaceContainerLow = Color(separated.surfaceContainerLow),
        surfaceContainer = Color(separated.surfaceContainer),
        surfaceContainerHigh = Color(separated.surfaceContainerHigh),
        surfaceContainerHighest = Color(separated.surfaceContainerHighest),
        primaryContainer = Color(separated.primaryContainer),
        secondaryContainer = Color(separated.secondaryContainer),
    ).withVerifiedContrast()
}

/**
 * Contrast guard: every text-bearing pair must meet WCAG AA (4.5:1),
 * verified against the FINAL fills (i.e. after adjacent separation has
 * moved the containers). The fix is hue-preserving
 * ([ensureTextContrastArgb]): a failing foreground deepens or lightens
 * within its own color family instead of being blended toward gray.
 * Pairs are chained so a role that sits on several grounds (onSurface
 * on surface AND on the list sheet; onSurfaceVariant on the sheet, the
 * settings card, and the composer bar) clears every one of them — each
 * fix moves the tone monotonically toward the same extreme, so a later
 * fix can never un-pass an earlier pair.
 */
private fun ColorScheme.withVerifiedContrast(): ColorScheme {
    fun fix(fg: Color, bg: Color): Color =
        Color(ensureTextContrastArgb(fg.toArgb(), bg.toArgb()))
    val onSurfaceFixed = fix(fix(onSurface, surface), surfaceContainerLow)
    val onSurfaceVariantFixed =
        fix(fix(fix(onSurfaceVariant, surfaceContainer), surfaceContainerLow), surfaceContainerHigh)
    return copy(
        onPrimary = fix(onPrimary, primary),
        onPrimaryContainer = fix(onPrimaryContainer, primaryContainer),
        onSecondary = fix(onSecondary, secondary),
        onSecondaryContainer = fix(onSecondaryContainer, secondaryContainer),
        onTertiary = fix(onTertiary, tertiary),
        onTertiaryContainer = fix(onTertiaryContainer, tertiaryContainer),
        onSurface = onSurfaceFixed,
        onSurfaceVariant = onSurfaceVariantFixed,
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
 * text-bearing pair is verified against WCAG AA (4.5:1) hue-preserved,
 * and the fill itself clears the adjacent-separation floor against the
 * thread ground — a pale sender's bubble can no longer wash into a pale
 * thread. Null seed (no photo / extraction failed) falls back to the
 * neutral 1:1 incoming treatment so nothing ever looks broken.
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
    val (container, onContainer, nameTone) =
        senderBubbleArgb(seed.toArgb(), threadBackground.toArgb(), darkTheme)
    return Triple(Color(container), Color(onContainer), Color(nameTone))
}

/**
 * The pure derivation behind [senderBubbleColors] (ARGB in, ARGB out —
 * unit-testable on the JVM):
 * - container: the sender's pastel (light) / deep tint (dark), pushed
 *   away from the thread ground until it clears
 *   [AdjacentFloors.SENDER_BUBBLE_ON_GROUND] — hue preserved, so
 *   adjacent senders stay distinguishable by color family even when
 *   several of them had to deepen.
 * - onContainer: black (light) / white (dark) verified to AA against
 *   the final container.
 * - nameTone: the SEED ITSELF walked along its own lightness until it
 *   clears AA against the thread ground — the name is the sender's
 *   color, deepened, never a gray.
 */
fun senderBubbleArgb(
    seedArgb: Int,
    threadBackgroundArgb: Int,
    darkTheme: Boolean,
): Triple<Int, Int, Int> {
    val container = separateFromArgb(
        foreground = blendArgb(seedArgb, if (darkTheme) BLACK_ARGB else WHITE_ARGB, if (darkTheme) 0.60f else 0.74f),
        background = threadBackgroundArgb,
        minRatio = AdjacentFloors.SENDER_BUBBLE_ON_GROUND,
        darken = !darkTheme,
    )
    val onContainer = ensureTextContrastArgb(
        if (darkTheme) WHITE_ARGB else BLACK_ARGB, container,
    )
    val nameTone = ensureTextContrastArgb(seedArgb, threadBackgroundArgb)
    return Triple(container, onContainer, nameTone)
}

private const val WHITE_ARGB = -0x1 // 0xFFFFFFFF
private const val BLACK_ARGB = -0x1000000 // 0xFF000000

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
