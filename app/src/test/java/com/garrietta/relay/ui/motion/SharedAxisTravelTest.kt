package com.garrietta.relay.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the shared-axis pane travel math (Garrett
 * 2026-10-20): BACKWARD must be the EXACT geometric mirror of
 * FORWARD — every offset negated — so a pop retraces the push path
 * precisely, with no sign or bounds drift for any container width
 * (odd widths included: integer division truncates toward zero
 * symmetrically for both signs). Pure JVM.
 * Run on CI via testDebugUnitTest.
 */
class SharedAxisTravelTest {

    private val widths = listOf(0, 1, 3, 100, 333, 1080, 1441, 2560)

    @Test
    fun `backward enter and exit are the negated forward offsets`() {
        for (full in widths) {
            assertEquals(
                "enter mirror at width $full",
                -SharedAxisTravel.enterOffset(MotionKind.FORWARD, full),
                SharedAxisTravel.enterOffset(MotionKind.BACKWARD, full),
            )
            assertEquals(
                "exit mirror at width $full",
                -SharedAxisTravel.exitOffset(MotionKind.FORWARD, full),
                SharedAxisTravel.exitOffset(MotionKind.BACKWARD, full),
            )
        }
    }

    @Test
    fun `enter starts exactly where the same direction's exit ends, mirrored`() {
        // One consistent axis: in FORWARD the entering screen starts
        // at +travel while the exiting one ends at -travel (equal and
        // opposite), and BACKWARD swaps the signs as a pair.
        for (full in widths) {
            assertEquals(
                -SharedAxisTravel.exitOffset(MotionKind.FORWARD, full),
                SharedAxisTravel.enterOffset(MotionKind.FORWARD, full),
            )
            assertEquals(
                -SharedAxisTravel.exitOffset(MotionKind.BACKWARD, full),
                SharedAxisTravel.enterOffset(MotionKind.BACKWARD, full),
            )
        }
    }

    @Test
    fun `travel is a quarter of the container width`() {
        assertEquals(270, SharedAxisTravel.enterOffset(MotionKind.FORWARD, 1080))
        assertEquals(-270, SharedAxisTravel.exitOffset(MotionKind.FORWARD, 1080))
        assertEquals(-270, SharedAxisTravel.enterOffset(MotionKind.BACKWARD, 1080))
        assertEquals(270, SharedAxisTravel.exitOffset(MotionKind.BACKWARD, 1080))
        // Odd widths truncate toward zero on both signs (333 / 4 = 83).
        assertEquals(83, SharedAxisTravel.enterOffset(MotionKind.FORWARD, 333))
        assertEquals(-83, SharedAxisTravel.enterOffset(MotionKind.BACKWARD, 333))
    }

    @Test
    fun `non-travel kinds have no offset`() {
        for (full in widths) {
            assertEquals(0, SharedAxisTravel.enterOffset(MotionKind.TOP_LEVEL, full))
            assertEquals(0, SharedAxisTravel.exitOffset(MotionKind.TOP_LEVEL, full))
            assertEquals(0, SharedAxisTravel.enterOffset(MotionKind.CONTAINER, full))
            assertEquals(0, SharedAxisTravel.exitOffset(MotionKind.CONTAINER, full))
        }
    }
}
