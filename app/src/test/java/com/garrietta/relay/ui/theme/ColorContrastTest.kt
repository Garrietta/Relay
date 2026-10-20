package com.garrietta.relay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast discipline (Garrett 2026-10-20): "Make colors contrast
 * more in the app" + his named case — near-identical tones budded
 * against each other at the composer. Every assertion runs the SAME
 * pure functions the app derives its colors with (ColorContrast.kt,
 * SeedSchemeTones.kt), over all 14 contact palettes plus a spread of
 * representative photo seeds (warm cream/tan like his family chats,
 * sky, rose, gray, near-white, near-black), in both schemes.
 *
 * Before this discipline, the worst measured pairs were: composer bar
 * on the chat ground 1.02:1, composer field in the bar 1.01:1,
 * outgoing bubble on the ground 1.03:1, the app list sheet on the
 * window background 1.00:1 (the identical color).
 */
class ColorContrastTest {

    private val photoSeeds: List<Int> = listOf(
        0xFFC9A227.toInt(), // mustard (photo)
        0xFFB08968.toInt(), // warm tan (photo)
        0xFFD9B98A.toInt(), // pale cream (photo — his group-chat family)
        0xFF87A8C8.toInt(), // sky (photo)
        0xFFE8A0B4.toInt(), // rose (photo)
        0xFF8A8D91.toInt(), // neutral gray (photo)
        0xFFF0E6D2.toInt(), // near-white cream (photo)
        0xFF2A2F38.toInt(), // near-black slate (photo)
    )

    private val allSeeds: List<Int> =
        ContactPalettes.all.map { it.seedArgb } + photoSeeds

    private fun assertRatioAtLeast(label: String, fg: Int, bg: Int, floor: Float) {
        val r = wcagContrastRatio(fg, bg)
        assertTrue("$label: $r < $floor (fg=${Integer.toHexString(fg)}, bg=${Integer.toHexString(bg)})", r >= floor - 0.001f)
    }

    // ---- Adjacent surfaces: the scheme-level floors ----

    @Test
    fun `seeded schemes clear every adjacent-surface floor for every seed`() {
        for (seed in allSeeds) {
            for (variant in SeedSchemeVariant.values()) {
                for (dark in listOf(false, true)) {
                    val t = seedSchemeTones(seed, dark, variant)
                    val where = "seed=${Integer.toHexString(seed)} $variant dark=$dark"
                    assertRatioAtLeast("sheet on window ($where)", t.surfaceContainerLow, t.background, AdjacentFloors.SHEET_ON_WINDOW)
                    assertRatioAtLeast("bar on ground ($where)", t.surfaceContainerHigh, t.surfaceContainerLow, AdjacentFloors.COMPOSER_BAR_ON_GROUND)
                    assertRatioAtLeast("chip on window ($where)", t.surfaceContainerHigh, t.background, AdjacentFloors.CHIP_ON_WINDOW)
                    assertRatioAtLeast("field in bar ($where)", t.surfaceContainerHighest, t.surfaceContainerHigh, AdjacentFloors.COMPOSER_FIELD_IN_BAR)
                    assertRatioAtLeast("outgoing on ground ($where)", t.primaryContainer, t.surfaceContainerLow, AdjacentFloors.OUTGOING_BUBBLE_ON_GROUND)
                    assertRatioAtLeast("pill on window ($where)", t.primaryContainer, t.background, AdjacentFloors.CLUSTER_PILL_ON_WINDOW)
                    assertRatioAtLeast("secondary on window ($where)", t.secondaryContainer, t.background, AdjacentFloors.SECONDARY_ON_WINDOW)
                    assertRatioAtLeast("secondary on sheet ($where)", t.secondaryContainer, t.surfaceContainerLow, AdjacentFloors.SECONDARY_ON_SHEET)
                    assertRatioAtLeast("card on window ($where)", t.surfaceContainer, t.background, AdjacentFloors.CARD_ON_WINDOW)
                }
            }
        }
    }

    @Test
    fun `composer bar on chat ground clears its floor — Garrett's named pair`() {
        // The composer bar is surfaceContainerHigh floating over the
        // chat's surfaceContainerLow ground; its inner field is
        // surfaceContainerHighest. Before: 1.02:1 and 1.01:1 worst-case.
        for (seed in allSeeds) {
            for (dark in listOf(false, true)) {
                val t = seedSchemeTones(seed, dark, SeedSchemeVariant.CHAT)
                assertRatioAtLeast(
                    "composer bar (seed=${Integer.toHexString(seed)}, dark=$dark)",
                    t.surfaceContainerHigh, t.surfaceContainerLow,
                    AdjacentFloors.COMPOSER_BAR_ON_GROUND,
                )
                assertRatioAtLeast(
                    "composer field (seed=${Integer.toHexString(seed)}, dark=$dark)",
                    t.surfaceContainerHighest, t.surfaceContainerHigh,
                    AdjacentFloors.COMPOSER_FIELD_IN_BAR,
                )
            }
        }
    }

    @Test
    fun `separation pass also separates a dynamic-style scheme`() {
        // The wallpaper fallback path (RelayTheme, no seed): Material
        // baseline-ish container tones run through the same pass.
        val baseline = SurfaceTones(
            background = 0xFFFFFBFE.toInt(),
            surfaceContainerLow = 0xFFF7F2FA.toInt(),
            surfaceContainer = 0xFFF3EDF7.toInt(),
            surfaceContainerHigh = 0xFFECE6F0.toInt(),
            surfaceContainerHighest = 0xFFE6E0E9.toInt(),
            primaryContainer = 0xFFEADDFF.toInt(),
            secondaryContainer = 0xFFE8DEF8.toInt(),
        )
        val out = baseline.withAdjacentSeparation(darkTheme = false)
        assertRatioAtLeast("sheet", out.surfaceContainerLow, out.background, AdjacentFloors.SHEET_ON_WINDOW)
        assertRatioAtLeast("bar", out.surfaceContainerHigh, out.surfaceContainerLow, AdjacentFloors.COMPOSER_BAR_ON_GROUND)
        assertRatioAtLeast("field", out.surfaceContainerHighest, out.surfaceContainerHigh, AdjacentFloors.COMPOSER_FIELD_IN_BAR)
        assertRatioAtLeast("outgoing", out.primaryContainer, out.surfaceContainerLow, AdjacentFloors.OUTGOING_BUBBLE_ON_GROUND)
        // The ground never moves.
        assertEquals(baseline.background, out.background)
    }

    // ---- Text pairs: AA, hue-preserved ----

    @Test
    fun `derived on-container tones clear AA against their final containers`() {
        // Mirrors withVerifiedContrast: the derived on* roles are run
        // through ensureTextContrastArgb against the separated fills.
        for (seed in allSeeds) {
            for (variant in SeedSchemeVariant.values()) {
                for (dark in listOf(false, true)) {
                    val t = seedSchemeTones(seed, dark, variant)
                    val where = "seed=${Integer.toHexString(seed)} $variant dark=$dark"
                    assertRatioAtLeast("onPrimary ($where)", ensureTextContrastArgb(t.onPrimary, t.primary), t.primary, 4.5f)
                    assertRatioAtLeast("onPrimaryContainer ($where)", ensureTextContrastArgb(t.onPrimaryContainer, t.primaryContainer), t.primaryContainer, 4.5f)
                    assertRatioAtLeast("onSecondaryContainer ($where)", ensureTextContrastArgb(t.onSecondaryContainer, t.secondaryContainer), t.secondaryContainer, 4.5f)
                    assertRatioAtLeast("onTertiaryContainer ($where)", ensureTextContrastArgb(t.onTertiaryContainer, t.tertiaryContainer), t.tertiaryContainer, 4.5f)
                    assertRatioAtLeast("inverse ($where)", ensureTextContrastArgb(t.inverseOnSurface, t.inverseSurface), t.inverseSurface, 4.5f)
                }
            }
        }
    }

    @Test
    fun `ensureTextContrastArgb reaches AA against any ground`() {
        val grounds = allSeeds + listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF8A8D91.toInt())
        for (fg in allSeeds) {
            for (bg in grounds) {
                val fixed = ensureTextContrastArgb(fg, bg)
                assertRatioAtLeast("fg=${Integer.toHexString(fg)} bg=${Integer.toHexString(bg)}", fixed, bg, 4.5f)
            }
        }
    }

    @Test
    fun `ensureTextContrastArgb leaves passing colors untouched`() {
        assertEquals(0xFF000000.toInt(), ensureTextContrastArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ensureTextContrastArgb(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        // A deep palette seed already clears AA on white — it stays itself.
        val indigo = ContactPalettes.all.first { it.name == "Indigo" }.seedArgb
        assertEquals(indigo, ensureTextContrastArgb(indigo, 0xFFFFFFFF.toInt()))
    }

    // ---- Sender bubbles: separation + AA + identity ----

    @Test
    fun `sender bubbles separate from the thread ground and carry AA text`() {
        for (senderSeed in allSeeds) {
            for (chatSeed in allSeeds) {
                for (dark in listOf(false, true)) {
                    val ground = seedSchemeTones(chatSeed, dark, SeedSchemeVariant.CHAT).surfaceContainerLow
                    val (container, onContainer, nameTone) = senderBubbleArgb(senderSeed, ground, dark)
                    val where = "sender=${Integer.toHexString(senderSeed)} chat=${Integer.toHexString(chatSeed)} dark=$dark"
                    assertRatioAtLeast("bubble on ground ($where)", container, ground, AdjacentFloors.SENDER_BUBBLE_ON_GROUND)
                    assertRatioAtLeast("bubble text ($where)", onContainer, container, 4.5f)
                    assertRatioAtLeast("sender name ($where)", nameTone, ground, 4.5f)
                }
            }
        }
    }

    @Test
    fun `sender colors keep their hue through separation and AA`() {
        val groundLight = seedSchemeTones(0xFF01AD41.toInt(), false, SeedSchemeVariant.CHAT).surfaceContainerLow
        val groundDark = seedSchemeTones(0xFF01AD41.toInt(), true, SeedSchemeVariant.CHAT).surfaceContainerLow
        for (senderSeed in allSeeds) {
            // Hue assertions only where hue is meaningful (saturated seed).
            if (hslOfArgb(senderSeed)[1] < 0.25f) continue
            for ((ground, dark) in listOf(groundLight to false, groundDark to true)) {
                val (container, _, nameTone) = senderBubbleArgb(senderSeed, ground, dark)
                val rawContainer = blendArgb(
                    senderSeed,
                    if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
                    if (dark) 0.60f else 0.74f,
                )
                assertTrue(
                    "container hue drift (seed=${Integer.toHexString(senderSeed)}, dark=$dark)",
                    hueDistanceDegrees(hueOfArgb(container), hueOfArgb(rawContainer)) <= 12f,
                )
                assertTrue(
                    "name hue drift (seed=${Integer.toHexString(senderSeed)}, dark=$dark)",
                    hueDistanceDegrees(hueOfArgb(nameTone), hueOfArgb(senderSeed)) <= 12f,
                )
            }
        }
    }

    @Test
    fun `sender containers stay distinguishable across the palette set`() {
        val ground = seedSchemeTones(0xFF01AD41.toInt(), false, SeedSchemeVariant.CHAT).surfaceContainerLow
        val containers = ContactPalettes.all.map { palette ->
            senderBubbleArgb(palette.seedArgb, ground, false).first
        }
        assertEquals(
            "every palette's bubble fill must be a distinct color on one ground",
            containers.size, containers.toSet().size,
        )
    }

    // ---- Stability ----

    @Test
    fun `derivation is deterministic — same seed, same scheme`() {
        for (seed in allSeeds) {
            for (variant in SeedSchemeVariant.values()) {
                for (dark in listOf(false, true)) {
                    assertEquals(
                        seedSchemeTones(seed, dark, variant),
                        seedSchemeTones(seed, dark, variant),
                    )
                }
            }
        }
    }

    @Test
    fun `separation is a no-op when the pair already clears its floor`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertEquals(white, separateFromArgb(white, black, 1.6f, darken = true))
        assertEquals(black, separateFromArgb(black, white, 1.6f, darken = false))
    }
}
