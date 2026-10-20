package com.garrietta.relay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo-less palette assignment (Garrett 2026-10-20): deterministic
 * per contact identity, spread across the set, WCAG AA pairs, and
 * cluster collision nudging. Fixture numbers are Garrett's real
 * contacts (from his membership dump) so the spread is proven on the
 * inbox he actually has, not on synthetic keys.
 */
class ContactPalettesTest {

    private val dad = "+17653182838"
    private val grace = "+18122017121"
    private val haley = "+18125937679"
    private val mom = "+17653182837"
    private val kevin = "+19789873356"
    private val seth = "+18126143113"
    private val self = "+17653419588"
    private val ghost = "UNKNOWN_SENDER!"

    private val fixtureKeys = listOf(
        dad, grace, haley, mom,
        "+12197768195", // Alec
        "+13038297909", // Trenton
        "+15084686985", // Kelly
        kevin,
        "+18123450716", // Adam
        "+18125680033", // Papaw
        self,
        seth,
        "+17659937929", // Bram
        "+19733608287", // Monroe Health
        "Grace", "Dad", "Mom", "Papaw Ambrose", "Monroe Health",
        "568348",
    )

    @Test
    fun `palette set is a curated expressive range`() {
        assertTrue(ContactPalettes.all.size in 12..16)
        assertEquals(
            ContactPalettes.all.size,
            ContactPalettes.all.map { it.name }.toSet().size,
        )
        assertEquals(
            ContactPalettes.all.size,
            ContactPalettes.all.map { it.seedArgb }.toSet().size,
        )
        // The Relay brand green anchors the set.
        assertEquals("Relay Green", ContactPalettes.all.first().name)
    }

    @Test
    fun `same contact always resolves to the same palette`() {
        val first = ContactPalettes.paletteFor(dad)
        repeat(10) { assertSame(first, ContactPalettes.paletteFor(dad)) }
        assertEquals(
            ContactPalettes.paletteIndexFor(dad),
            ContactPalettes.paletteIndexFor(dad),
        )
    }

    @Test
    fun `phone formatting variants share one palette`() {
        assertSame(
            ContactPalettes.paletteFor(dad),
            ContactPalettes.paletteFor("7653182838"),
        )
        assertSame(
            ContactPalettes.paletteFor(dad),
            ContactPalettes.paletteFor("+1 (765) 318-2838"),
        )
    }

    @Test
    fun `assignment is stateless - self and ghost keys never consume palettes`() {
        // The Dad fixture: his palette must be identical no matter
        // which other keys (his own self entries, the UNKNOWN_SENDER!
        // ghost, anyone) are resolved before, between, or after.
        val before = ContactPalettes.paletteFor(dad)
        repeat(25) {
            ContactPalettes.paletteFor(self)
            ContactPalettes.paletteFor(ghost)
            ContactPalettes.paletteFor("other-$it")
        }
        assertSame(before, ContactPalettes.paletteFor(dad))
        // And every key lands inside the set.
        for (key in fixtureKeys + listOf(self, ghost)) {
            val index = ContactPalettes.paletteIndexFor(key)
            assertTrue(index in ContactPalettes.all.indices)
            assertSame(ContactPalettes.all[index], ContactPalettes.paletteFor(key))
        }
    }

    @Test
    fun `fixture contacts spread across at least half the set`() {
        val used = fixtureKeys.map { ContactPalettes.paletteIndexFor(it) }.toSet()
        assertTrue(
            "expected >= ${ContactPalettes.all.size / 2} distinct palettes, got ${used.size}: $used",
            used.size >= ContactPalettes.all.size / 2,
        )
    }

    @Test
    fun `every palette pair passes WCAG AA in both schemes`() {
        for (palette in ContactPalettes.all) {
            val light = wcagContrastRatio(
                palette.lightBackgroundArgb, palette.lightOnBackgroundArgb,
            )
            val dark = wcagContrastRatio(
                palette.darkBackgroundArgb, palette.darkOnBackgroundArgb,
            )
            assertTrue("${palette.name} light pair contrast $light < 4.5", light >= 4.5f)
            assertTrue("${palette.name} dark pair contrast $dark < 4.5", dark >= 4.5f)
        }
    }

    @Test
    fun `cluster nudges an exact collision to the next palette`() {
        // Kevin and Seth hash to the same palette (verified fixture).
        assertEquals(
            ContactPalettes.paletteIndexFor(kevin),
            ContactPalettes.paletteIndexFor(seth),
        )
        val indexes = ContactPalettes.paletteIndexesForCluster(listOf(kevin, seth))
        // First member keeps the canonical assignment; the later one
        // moves exactly one step.
        assertEquals(ContactPalettes.paletteIndexFor(kevin), indexes[0])
        assertEquals((ContactPalettes.paletteIndexFor(kevin) + 1) % ContactPalettes.all.size, indexes[1])
    }

    @Test
    fun `cluster without collisions keeps every solo assignment`() {
        val keys = listOf(dad, grace, haley, mom)
        assertEquals(
            keys.map { ContactPalettes.paletteIndexFor(it) },
            ContactPalettes.paletteIndexesForCluster(keys),
        )
    }

    @Test
    fun `chat seed resolution - photo seed beats the assigned palette`() {
        // A contact WITH a photo: theming must use the photo-derived
        // seed, byte-for-byte — the palette never overrides a photo.
        val photoSeed = 0xFF123456.toInt()
        for (key in listOf(dad, grace, mom, haley)) {
            assertEquals(
                photoSeed,
                ContactPalettes.resolveContactSeedArgb(key, photoSeed),
            )
        }
    }

    @Test
    fun `chat seed resolution - photo-less contact resolves to the palette seed`() {
        // No photo (photoSeedArgb = null) → the assigned palette's seed.
        // A failed photo extraction reports null through the exact same
        // call shape, so this covers the extraction-failure case too.
        for (key in fixtureKeys) {
            assertEquals(
                ContactPalettes.paletteFor(key).seedArgb,
                ContactPalettes.resolveContactSeedArgb(key, null),
            )
        }
    }

    @Test
    fun `chat seed for Mom matches the seed her avatar palette uses`() {
        // Icon/chat agreement fixture (Garrett 2026-10-20: Mom's chat
        // rendered unthemed while her avatar wore the peach palette).
        // The chat scheme's seed for Mom's number must be the seed of
        // the very palette her avatar resolves — one identity, one color.
        val chatSeed = ContactPalettes.resolveContactSeedArgb(mom, null)
        assertEquals(ContactPalettes.paletteFor(mom).seedArgb, chatSeed)
        // Key formatting cannot split them either: the avatar and the
        // chat both key off the raw address, normalized identically.
        assertEquals(
            chatSeed,
            ContactPalettes.resolveContactSeedArgb("7653182837", null),
        )
        assertEquals(
            chatSeed,
            ContactPalettes.resolveContactSeedArgb("+1 (765) 318-2837", null),
        )
    }

    @Test
    fun `chat seed for Grace matches her avatar palette`() {
        // The other half of his video evidence: Grace's 1:1 rendered in
        // the wallpaper scheme while her avatar wore her teal palette.
        assertEquals(
            ContactPalettes.paletteFor(grace).seedArgb,
            ContactPalettes.resolveContactSeedArgb(grace, null),
        )
    }

    @Test
    fun `cluster nudge cascades past a taken next slot`() {
        // Kevin + Seth collide; Adam's own assignment is the slot
        // Seth would nudge into, so Adam must move one further.
        val keys = listOf(kevin, seth, "+18123450716")
        val indexes = ContactPalettes.paletteIndexesForCluster(keys)
        assertEquals(keys.size, indexes.toSet().size)
        assertEquals(ContactPalettes.paletteIndexFor(kevin), indexes[0])
    }
}
