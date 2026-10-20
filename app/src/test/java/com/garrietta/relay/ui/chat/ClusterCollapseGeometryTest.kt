package com.garrietta.relay.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the group cluster collapse geometry (Garrett
 * 2026-10-20): the collapse animation's clip window must contain
 * every tile's FULL extent at every animation frame — the old
 * AnimatedVisibility size animation clipped to the cluster box's own
 * edges and shaved the 2-person tiles' ~1.1dp overhang flat
 * mid-scroll (Mom's "M" tile losing its bottom-left corner). Pure
 * JVM. Run on CI via testDebugUnitTest.
 *
 * The header's numbers: cluster box 76dp, slot = box + 10dp
 * trailing gap = 86dp.
 */
class ClusterCollapseGeometryTest {

    private val box = 76f
    private val slotFull = 86f

    @Test
    fun `only the 2-person overlap overhangs the box`() {
        // Diagonal overlap: centers clamp to [0.36, 0.64], tile
        // half-size 0.375 -> extreme extent 1.015 of the box.
        assertEquals(0.015f, ClusterCollapseGeometry.tileOverhangFrac(2), 0.0001f)
        // Every other count: shapes are inscribed in the circle of
        // radius size/2 about clamped centers — extents stay inside.
        for (n in listOf(0, 1, 3, 4, 5, 6, 7, 8, 12)) {
            assertEquals(0f, ClusterCollapseGeometry.tileOverhangFrac(n), 0.0001f)
        }
    }

    @Test
    fun `clip margin covers the worst overhang at every count`() {
        val margin = ClusterCollapseGeometry.clipMarginDp(box)
        for (n in 1..12) {
            val overhang = ClusterCollapseGeometry.tileOverhangFrac(n) * box
            assertTrue(
                "count $n: margin $margin must cover overhang $overhang",
                margin >= overhang,
            )
        }
        // And with headroom, not equality: the margin is twice the
        // worst overhang fraction.
        assertEquals(
            ClusterCollapseGeometry.tileOverhangFrac(2) * box * 2,
            margin,
            0.001f,
        )
    }

    @Test
    fun `slot width follows the fraction and never goes negative`() {
        assertEquals(0f, ClusterCollapseGeometry.slotWidthDp(0f, slotFull), 0.0001f)
        assertEquals(slotFull, ClusterCollapseGeometry.slotWidthDp(1f, slotFull), 0.0001f)
        assertEquals(slotFull / 2, ClusterCollapseGeometry.slotWidthDp(0.5f, slotFull), 0.0001f)
        // Spring undershoot below 0 clamps (a width can't be negative);
        // overshoot past 1 passes through, as the old IntSize spring did.
        assertEquals(0f, ClusterCollapseGeometry.slotWidthDp(-0.25f, slotFull), 0.0001f)
        assertEquals(slotFull * 1.1f, ClusterCollapseGeometry.slotWidthDp(1.1f, slotFull), 0.001f)
    }

    @Test
    fun `clip window is exactly closed at fraction zero`() {
        val clip = ClusterCollapseGeometry.clipRectDp(0f, box, slotFull)
        assertEquals(clip.left, clip.right, 0.0001f)
    }

    @Test
    fun `fixed clip edges contain every tile extent at every count and fraction`() {
        // The three fixed edges (left, top, bottom) must contain the
        // union of all tile extents for the WHOLE animation — a tile
        // point the sweep has not yet reached is never cut by them.
        val fractions = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)
        for (n in 1..12) {
            val extent = ClusterCollapseGeometry.unionTileExtentDp(n, box)
            for (f in fractions) {
                val clip = ClusterCollapseGeometry.clipRectDp(f, box, slotFull)
                assertTrue("count $n f $f: left", extent.left >= clip.left)
                assertTrue("count $n f $f: top", extent.top >= clip.top)
                assertTrue("count $n f $f: bottom", extent.bottom <= clip.bottom)
            }
        }
    }

    @Test
    fun `sweep edge reveals the full extent exactly at settle and sweeps monotonically`() {
        for (n in 1..12) {
            val extent = ClusterCollapseGeometry.unionTileExtentDp(n, box)
            val atSettle = ClusterCollapseGeometry.clipRectDp(1f, box, slotFull)
            assertTrue(
                "count $n: settled sweep must pass the full extent",
                atSettle.right >= extent.right,
            )
        }
        var previous = Float.NEGATIVE_INFINITY
        for (i in 0..20) {
            val f = i / 20f
            val right = ClusterCollapseGeometry.clipRectDp(f, box, slotFull).right
            assertTrue("sweep must not move backwards at f=$f", right >= previous)
            previous = right
        }
    }

    @Test
    fun `mid-flight the sweep is the only edge that can cross a tile`() {
        // At f = 0.5 the window's right edge sits inside the slot —
        // that crossing is the intended reveal. The fixed edges are
        // already proven containing above; here: the sweep at 0.5 is
        // strictly inside the slot's full width (the reveal is
        // genuinely mid-flight, not degenerate).
        val clip = ClusterCollapseGeometry.clipRectDp(0.5f, box, slotFull)
        assertTrue(clip.right > 0f)
        assertTrue(clip.right < slotFull)
    }
}
