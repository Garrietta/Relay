package com.garrietta.relay.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Per-contact Material 3 Expressive color palettes for contacts WITHOUT a
 * profile photo (Garrett 2026-10-20): "If a contact doesn't have a photo
 * then they are assigned a random material 3 expressive color palette."
 *
 * THE single assignment point. Every surface that renders a photo-less
 * contact — inbox rows, group avatar clusters, group sender avatars, chat
 * headers, the details page, search results, the forward picker (all via
 * [ContactAvatar]), notification avatars, and the group bubble-tint seed —
 * resolves colors from here, so a contact's color identity is the same
 * everywhere it appears.
 *
 * "Random" is the variety ACROSS contacts: the assignment is a pure,
 * stateless function of the contact's identity key (the same normalized
 * key [AvatarShapeAssigner] keys shapes by — digits-only, last 10), mixed
 * with a murmur3-style finalizer so the fixture contacts spread across
 * nearly the whole set instead of clumping the way raw `hashCode() % n`
 * does. It is NOT reshuffled per cold start (unlike shapes, which have
 * their own reshuffle directive): colors stay put across launches so
 * contacts stay recognizable. Nothing is consumed or handed out in
 * order — self entries and the UNKNOWN_SENDER! ghost can be queried
 * freely without shifting anyone else's palette.
 *
 * Each palette is one hue in M3 container discipline: a vivid [seedArgb]
 * (also the group bubble-tint seed for photo-less senders), a pastel
 * container background + deep on-container for light scheme, and a deep
 * container + pale on-container for dark scheme. Every pair is verified
 * WCAG AA (≥ 4.5:1) by unit test ([wcagContrastRatio]); [ContactAvatar]
 * additionally runs the pair through the codebase's `ensureContrast`
 * guard at compose time.
 */
data class ContactPalette(
    val name: String,
    val seedArgb: Int,
    val lightBackgroundArgb: Int,
    val lightOnBackgroundArgb: Int,
    val darkBackgroundArgb: Int,
    val darkOnBackgroundArgb: Int,
) {
    val seed: Color get() = Color(seedArgb)
    val lightBackground: Color get() = Color(lightBackgroundArgb)
    val lightOnBackground: Color get() = Color(lightOnBackgroundArgb)
    val darkBackground: Color get() = Color(darkBackgroundArgb)
    val darkOnBackground: Color get() = Color(darkOnBackgroundArgb)

    fun background(darkTheme: Boolean): Color =
        if (darkTheme) darkBackground else lightBackground

    fun onBackground(darkTheme: Boolean): Color =
        if (darkTheme) darkOnBackground else lightOnBackground

    fun backgroundArgb(darkTheme: Boolean): Int =
        if (darkTheme) darkBackgroundArgb else lightBackgroundArgb

    fun onBackgroundArgb(darkTheme: Boolean): Int =
        if (darkTheme) darkOnBackgroundArgb else lightOnBackgroundArgb
}

object ContactPalettes {

    private fun a(hex: Long): Int = hex.toInt()

    private fun palette(
        name: String,
        seed: Long,
        lightBg: Long,
        lightFg: Long,
        darkBg: Long,
        darkFg: Long,
    ) = ContactPalette(name, a(seed), a(lightBg), a(lightFg), a(darkBg), a(darkFg))

    /**
     * 14 hues spanning the expressive range — the Relay green family
     * first, then a warm-to-cool spread. Tones were derived from each
     * seed by fixed container blends (light bg = seed→white 0.82, light
     * fg = seed→black 0.58, dark bg = seed→black 0.66, dark fg =
     * seed→white 0.80) and are pinned here as literals; every pair
     * measures ≥ 7.9:1 contrast (unit-tested at the AA floor of 4.5).
     */
    val all: List<ContactPalette> = listOf(
        palette("Relay Green", 0xFF01AD41, 0xFFD1F0DD, 0xFF00491B, 0xFF003B16, 0xFFCCEFD9),
        palette("Emerald", 0xFF00897B, 0xFFD1EAE7, 0xFF003A34, 0xFF002F2A, 0xFFCCE7E5),
        palette("Cyan", 0xFF0288D1, 0xFFD1EAF7, 0xFF013958, 0xFF012E47, 0xFFCCE7F6),
        palette("Blue", 0xFF1E5EFF, 0xFFD6E2FF, 0xFF0D276B, 0xFF0A2057, 0xFFD2DFFF),
        palette("Indigo", 0xFF5E35B1, 0xFFE2DBF1, 0xFF27164A, 0xFF20123C, 0xFFDFD7EF),
        palette("Purple", 0xFF8E24AA, 0xFFEBD8F0, 0xFF3C0F47, 0xFF300C3A, 0xFFE8D3EE),
        palette("Magenta", 0xFFC2189E, 0xFFF4D5EE, 0xFF510A42, 0xFF420836, 0xFFF3D1EC),
        palette("Pink", 0xFFD81B60, 0xFFF8D6E2, 0xFF5B0B28, 0xFF490921, 0xFFF7D1DF),
        palette("Red", 0xFFE53935, 0xFFFADBDB, 0xFF601816, 0xFF4E1312, 0xFFFAD7D7),
        palette("Coral", 0xFFF4511E, 0xFFFDE0D6, 0xFF66220D, 0xFF531C0A, 0xFFFDDCD2),
        palette("Orange", 0xFFEF6C00, 0xFFFCE5D1, 0xFF642D00, 0xFF512500, 0xFFFCE2CC),
        palette("Amber", 0xFFFF8F00, 0xFFFFEBD1, 0xFF6B3C00, 0xFF573100, 0xFFFFE9CC),
        palette("Lime", 0xFF7CB342, 0xFFE7F1DD, 0xFF344B1C, 0xFF2A3D16, 0xFFE5F0D9),
        palette("Slate", 0xFF546E7A, 0xFFE0E5E7, 0xFF232E33, 0xFF1D2529, 0xFFDDE2E4),
    )

    /** The palette at [index], wrapping — cluster nudges rely on this. */
    fun paletteAt(index: Int): ContactPalette =
        all[Math.floorMod(index, all.size)]

    /**
     * Stable palette index for an identity [key]. The key is normalized
     * exactly like shape keys (digits-only, last 10), so formatting
     * variants of one number agree; the hash then goes through a
     * murmur3 fmix32 finalizer before the modulo — raw `hashCode() % n`
     * clumped his real contacts (five of them, Dad and Grace included,
     * shared one slot of 14); the mixed hash spreads them.
     */
    fun paletteIndexFor(key: String): Int {
        val norm = AvatarShapeAssigner.normalizeKey(key)
        var h = norm.hashCode()
        h = h xor (h ushr 16)
        h *= 0x7FEB352D
        h = h xor (h ushr 15)
        return (h and Int.MAX_VALUE) % all.size
    }

    /** The contact's assigned palette — the same on every surface. */
    fun paletteFor(key: String): ContactPalette = all[paletteIndexFor(key)]

    /**
     * THE contact-theming seed rule (Garrett 2026-10-20): a contact's
     * color identity is ONE seed everywhere — the photo-derived seed
     * when they have a photo (and extraction succeeds), else the seed
     * of their assigned palette, resolved from the SAME identity key
     * their avatar uses. So the icon's assigned color and the chat's
     * theme can never diverge: a photo-less contact's chat themes from
     * their palette instead of falling back to the wallpaper scheme.
     *
     * [photoSeedArgb] is null both when there is no photo and when
     * extraction failed — the palette covers both. The rule works in
     * ARGB ints so it is unit-testable without a Compose runtime.
     */
    fun resolveContactSeedArgb(identityKey: String, photoSeedArgb: Int?): Int =
        photoSeedArgb ?: paletteFor(identityKey).seedArgb

    /**
     * Palette indexes for one avatar cluster's members, in member
     * order. If two members' assigned palettes collide exactly, the
     * LATER member nudges to the next palette in the set (wrapping)
     * so cluster members stay distinguishable; the first member always
     * keeps the canonical assignment. Solo surfaces never nudge — this
     * is cluster-only, and only when the whole cluster fits in the set.
     */
    fun paletteIndexesForCluster(keys: List<String>): List<Int> {
        val nudgeable = keys.size in 2..all.size
        val used = HashSet<Int>()
        return keys.map { key ->
            var index = paletteIndexFor(key)
            if (nudgeable) {
                var guard = 0
                while (index in used && guard < all.size) {
                    index = (index + 1) % all.size
                    guard++
                }
            }
            used += index
            index
        }
    }
}

/**
 * WCAG contrast ratio between two sRGB ARGB colors — the same math as
 * `androidx.core.graphics.ColorUtils.calculateContrast` (which
 * `ensureContrast` in ContactTheme.kt is built on), re-implemented in
 * pure Kotlin so palette pairs are verifiable in JVM unit tests with
 * no Android runtime.
 */
fun wcagContrastRatio(argb1: Int, argb2: Int): Float {
    fun luminance(argb: Int): Double {
        fun linearize(channel: Int): Double {
            val v = channel / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = linearize((argb shr 16) and 0xFF)
        val g = linearize((argb shr 8) and 0xFF)
        val b = linearize(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    val l1 = luminance(argb1)
    val l2 = luminance(argb2)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return ((lighter + 0.05) / (darker + 0.05)).toFloat()
}
