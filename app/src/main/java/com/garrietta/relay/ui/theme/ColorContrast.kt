package com.garrietta.relay.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure color-contrast machinery — Garrett 2026-10-20: "Make colors
 * contrast more in the app", refined the same hour with his named case:
 * "colors from nearly similar color screens budded against each other
 * (the message bar at the bottom here)" — the composer bar was a pale
 * pill on a pale ground with a pale field inside it, three adjacent
 * surfaces within ~1.05:1 of each other, one undifferentiated wash.
 *
 * Everything in this file is ARGB-int math with no Android or Compose
 * dependency, so the whole discipline runs identically in the app and
 * in JVM unit tests: the functions the tests assert on ARE the
 * functions the app derives its colors with.
 *
 * Two disciplines live here:
 *
 * 1. TEXT (WCAG AA). A foreground that carries text is walked along
 *    its OWN HSL lightness — hue and saturation untouched — until it
 *    clears its floor against its ground ([ensureTextContrastArgb]).
 *    This replaces the old blend-toward-black/white guard for the
 *    scheme and sender-name paths: that guard reached 4.5:1 too, but
 *    by desaturating the color toward gray, so a sender's name tone
 *    stopped being the sender's color. Here the tone deepens (or
 *    lightens) WITHIN its hue family.
 *
 * 2. ADJACENT SURFACES ([AdjacentFloors] + [separateFromArgb]). Two
 *    fills that touch must read as different objects: each named pair
 *    has a minimum tonal separation, enforced at derivation time by
 *    pushing the FOREGROUND element's lightness away from its ground
 *    (deeper in light theme, lighter in dark — the direction the
 *    tonal ramp already steps), hue preserved. Grounds never move;
 *    the object on them gains the separation.
 */

/** sRGB channel blend, bit-compatible with androidx ColorUtils.blendARGB. */
fun blendArgb(color1: Int, color2: Int, ratio: Float): Int {
    val inverse = 1f - ratio
    fun channel(shift: Int): Int {
        val c1 = (color1 shr shift) and 0xFF
        val c2 = (color2 shr shift) and 0xFF
        return (c1 * inverse + c2 * ratio).toInt()
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or
        (channel(8) shl 8) or channel(0)
}

/** WCAG relative luminance of an sRGB ARGB color (alpha ignored). */
fun relativeLuminanceArgb(argb: Int): Double {
    fun linearize(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    val r = linearize((argb shr 16) and 0xFF)
    val g = linearize((argb shr 8) and 0xFF)
    val b = linearize(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** (hue 0..360, saturation 0..1, lightness 0..1) of an ARGB color. */
fun hslOfArgb(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    if (mx == mn) return floatArrayOf(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return floatArrayOf((h * 60f + 360f) % 360f, s, l)
}

/** ARGB from HSL components (alpha preserved from [alpha]). */
fun argbFromHsl(hue: Float, saturation: Float, lightness: Float, alpha: Int = 0xFF): Int {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs(h % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun q(v: Float): Int = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
}

/** Hue of an ARGB color in degrees (0..360); 0 for achromatic colors. */
fun hueOfArgb(argb: Int): Float = hslOfArgb(argb)[0]

/** Smallest angular distance between two hues, in degrees. */
fun hueDistanceDegrees(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return min(d, 360f - d)
}

private const val LIGHTNESS_MIN = 0.02f
private const val LIGHTNESS_MAX = 0.98f
private const val LIGHTNESS_STEP = 0.01f

/**
 * Walks [argb]'s HSL lightness one step at a time in [stepDirection]
 * (-1 = darker, +1 = lighter) and returns the first tone for which
 * [passes] holds; if the walk reaches the lightness bound first, the
 * bound tone is returned (best effort — for the floors used here a
 * passing tone always exists well inside the bounds, because against
 * any ground at least one lightness extreme clears 4.5:1, and the
 * adjacent-surface floors are far looser than that).
 *
 * The walk starts from the range-clamped lightness so a color whose
 * lightness already sits past a bound still moves on the first step.
 */
private fun walkLightnessArgb(
    argb: Int,
    stepDirection: Int,
    passes: (Int) -> Boolean,
): Int {
    val hsl = hslOfArgb(argb)
    val alpha = (argb shr 24) and 0xFF
    var current = hsl[2].coerceIn(LIGHTNESS_MIN, LIGHTNESS_MAX)
    var candidate = argbFromHsl(hsl[0], hsl[1], current, alpha)
    if (passes(candidate)) return candidate
    repeat(140) {
        val next = current + stepDirection * LIGHTNESS_STEP
        if (next < LIGHTNESS_MIN || next > LIGHTNESS_MAX) {
            val bound = argbFromHsl(
                hsl[0], hsl[1],
                next.coerceIn(LIGHTNESS_MIN, LIGHTNESS_MAX), alpha,
            )
            return if (passes(bound)) bound else candidate
        }
        current = next
        candidate = argbFromHsl(hsl[0], hsl[1], current, alpha)
        if (passes(candidate)) return candidate
    }
    return candidate
}

/**
 * WCAG AA text enforcement, hue-preserving: returns [foreground]
 * unchanged when it already clears [minRatio] against [background];
 * otherwise walks its lightness toward whichever extreme (black or
 * white) offers the higher ceiling against that ground until it
 * passes. The result is the same color family, deepened or lightened
 * — never a gray.
 */
fun ensureTextContrastArgb(
    foreground: Int,
    background: Int,
    minRatio: Float = 4.5f,
): Int {
    if (wcagContrastRatio(foreground, background) >= minRatio) return foreground
    val darken = wcagContrastRatio(0xFF000000.toInt(), background) >=
        wcagContrastRatio(0xFFFFFFFF.toInt(), background)
    return walkLightnessArgb(foreground, if (darken) -1 else 1) { candidate ->
        wcagContrastRatio(candidate, background) >= minRatio
    }
}

/**
 * Adjacent-surface separation: returns [foreground] unchanged when it
 * already sits at least [minRatio] away from [background]; otherwise
 * pushes its lightness in the theme's ramp direction — [darken] in
 * light theme (objects step DOWN from pale grounds), lighten in dark
 * theme — until the pair clears the floor. Hue and saturation are
 * preserved: the object stays in its color family, it just becomes
 * unmistakably its own object.
 */
fun separateFromArgb(
    foreground: Int,
    background: Int,
    minRatio: Float,
    darken: Boolean,
): Int {
    if (wcagContrastRatio(foreground, background) >= minRatio) return foreground
    return walkLightnessArgb(foreground, if (darken) -1 else 1) { candidate ->
        wcagContrastRatio(candidate, background) >= minRatio
    }
}

/**
 * The adjacent-surface floors (Garrett 2026-10-20). Measured BEFORE
 * this discipline existed, the worst pairs sat at 1.00–1.10:1 — the
 * app-scheme list sheet was literally the window background's color,
 * and the composer stack (bar / field / ground) spanned 1.01–1.07:1
 * end to end. The floors are tonal-distance minimums (contrast-ratio
 * form), enforced per derived scheme, every seed, both themes.
 */
object AdjacentFloors {
    /** List sheet (surfaceContainerLow) on the window background. */
    const val SHEET_ON_WINDOW = 1.12f

    /** Composer bar (surfaceContainerHigh) on the chat ground
     *  (surfaceContainerLow) — Garrett's named pair. The same pair is
     *  the 1:1 incoming bubble on the chat ground. */
    const val COMPOSER_BAR_ON_GROUND = 1.6f

    /** Unselected category chip (surfaceContainerHigh) on the inbox
     *  window background. */
    const val CHIP_ON_WINDOW = 1.5f

    /** Composer text field (surfaceContainerHighest) inside the bar
     *  (surfaceContainerHigh) — the innermost of Garrett's three
     *  budded-together surfaces. */
    const val COMPOSER_FIELD_IN_BAR = 1.4f

    /** Outgoing bubble (primaryContainer) on the chat ground. */
    const val OUTGOING_BUBBLE_ON_GROUND = 1.6f

    /** New-conversation / minimized cluster pill (primaryContainer)
     *  on the inbox window background. */
    const val CLUSTER_PILL_ON_WINDOW = 1.6f

    /** Search button + transport chip + selected-row wash
     *  (secondaryContainer) on the window background... */
    const val SECONDARY_ON_WINDOW = 1.45f

    /** ...and on the list sheet (surfaceContainerLow). */
    const val SECONDARY_ON_SHEET = 1.4f

    /** Settings cards (surfaceContainer) on the window background. */
    const val CARD_ON_WINDOW = 1.3f

    /** Per-sender group bubble fill on the thread ground. */
    const val SENDER_BUBBLE_ON_GROUND = 1.5f
}

/**
 * The surface tones that take part in adjacent-surface pairs. One
 * record so the seeded schemes and the dynamic fallback scheme run
 * the exact same separation pass ([withAdjacentSeparation]).
 * [background] is the fixed reference and never moves — grounds stay
 * grounds; the objects on them gain the separation.
 */
data class SurfaceTones(
    val background: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val primaryContainer: Int,
    val secondaryContainer: Int,
) {
    /**
     * The ordered separation pass. Order matters: the sheet settles
     * against the window first, then the objects that sit on the
     * sheet, then the one object nested inside another object (the
     * composer field inside the bar). Each step moves only the
     * foreground role, in the theme direction, so the tonal ramp's
     * ordering (ground < sheet < object < nested object) is preserved
     * — separation widens the steps, it never inverts them.
     */
    fun withAdjacentSeparation(darkTheme: Boolean): SurfaceTones {
        val darken = !darkTheme
        val low = separateFromArgb(
            surfaceContainerLow, background, AdjacentFloors.SHEET_ON_WINDOW, darken,
        )
        var high = separateFromArgb(
            surfaceContainerHigh, low, AdjacentFloors.COMPOSER_BAR_ON_GROUND, darken,
        )
        high = separateFromArgb(
            high, background, AdjacentFloors.CHIP_ON_WINDOW, darken,
        )
        val highest = separateFromArgb(
            surfaceContainerHighest, high, AdjacentFloors.COMPOSER_FIELD_IN_BAR, darken,
        )
        var primary = separateFromArgb(
            primaryContainer, low, AdjacentFloors.OUTGOING_BUBBLE_ON_GROUND, darken,
        )
        primary = separateFromArgb(
            primary, background, AdjacentFloors.CLUSTER_PILL_ON_WINDOW, darken,
        )
        var secondary = separateFromArgb(
            secondaryContainer, background, AdjacentFloors.SECONDARY_ON_WINDOW, darken,
        )
        secondary = separateFromArgb(
            secondary, low, AdjacentFloors.SECONDARY_ON_SHEET, darken,
        )
        val container = separateFromArgb(
            surfaceContainer, background, AdjacentFloors.CARD_ON_WINDOW, darken,
        )
        return SurfaceTones(
            background = background,
            surfaceContainerLow = low,
            surfaceContainer = container,
            surfaceContainerHigh = high,
            surfaceContainerHighest = highest,
            primaryContainer = primary,
            secondaryContainer = secondary,
        )
    }
}
