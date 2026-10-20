package com.garrietta.relay.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the inbox bottom cluster's form decisions (Garrett
 * 2026-10-20): FULL (new-conversation pill + search button) at the
 * top of the list; scrolled away from the top, the cluster condenses
 * Koda-mini-player-style into ONE circular new-conversation button
 * at the bottom-right, and the full form restores only back at the
 * top. Position-based, with a hysteresis band so the boundary can't
 * flicker; pure JVM ints (LazyListState's raw pixel offsets).
 * Run on CI via testDebugUnitTest.
 */
class ClusterStateLogicTest {

    private val full = InboxClusterForm.FULL
    private val mini = InboxClusterForm.MINIMIZED

    @Test
    fun `at the very top the cluster is full from either form`() {
        assertEquals(full, ClusterStateLogic.formFor(0, 0, full))
        assertEquals(full, ClusterStateLogic.formFor(0, 0, mini))
        assertEquals(full, ClusterStateLogic.formFor(0, 60, mini))
        assertEquals(
            full,
            ClusterStateLogic.formFor(
                0, ClusterStateLogic.RESTORE_TOP_OFFSET_PX - 1, mini,
            ),
        )
    }

    @Test
    fun `scrolled past the threshold the cluster minimizes`() {
        assertEquals(
            mini,
            ClusterStateLogic.formFor(
                0, ClusterStateLogic.MINIMIZE_OFFSET_PX, full,
            ),
        )
        assertEquals(mini, ClusterStateLogic.formFor(0, 900, full))
        // Onto any later item — even at that item's own offset 0 —
        // the list is away from the top.
        assertEquals(mini, ClusterStateLogic.formFor(1, 0, full))
        assertEquals(mini, ClusterStateLogic.formFor(12, 140, full))
    }

    @Test
    fun `hysteresis band holds the current form in both directions`() {
        // Offsets 120..239 on item 0 decide nothing by themselves:
        // scrolling down through the band keeps FULL until the
        // minimize bound, scrolling back up keeps MINIMIZED until
        // the restore bound — no flicker at a single line.
        val band = listOf(120, 121, 180, 239)
        for (offset in band) {
            assertEquals("offset $offset from FULL", full, ClusterStateLogic.formFor(0, offset, full))
            assertEquals("offset $offset from MINIMIZED", mini, ClusterStateLogic.formFor(0, offset, mini))
        }
    }

    @Test
    fun `scrolling up away from the top does not restore early`() {
        // The Koda model: the full bar exists only at the top. A
        // scroll-up gesture deep in the list keeps the circle.
        assertEquals(mini, ClusterStateLogic.formFor(4, 20, mini))
        assertEquals(mini, ClusterStateLogic.formFor(2, 500, mini))
        assertEquals(mini, ClusterStateLogic.formFor(0, 200, mini))
    }

    @Test
    fun `returning to the top zone restores the full cluster`() {
        assertEquals(mini, ClusterStateLogic.formFor(0, 180, mini))
        assertEquals(full, ClusterStateLogic.formFor(0, 119, mini))
        assertEquals(full, ClusterStateLogic.formFor(0, 0, mini))
    }

    @Test
    fun `initial form is decisive - deep restore points start minimized`() {
        // First-frame composition (cold start has no restore point;
        // a chat-exit return can sit anywhere): not-in-the-top-zone
        // is minimized, with no hysteresis ambiguity to resolve.
        assertEquals(full, ClusterStateLogic.initialFormFor(0, 0))
        assertEquals(full, ClusterStateLogic.initialFormFor(0, 119))
        assertEquals(mini, ClusterStateLogic.initialFormFor(0, 120))
        assertEquals(mini, ClusterStateLogic.initialFormFor(0, 180))
        assertEquals(mini, ClusterStateLogic.initialFormFor(0, 1080))
        assertEquals(mini, ClusterStateLogic.initialFormFor(7, 240))
    }

    @Test
    fun `slot height is the same constant for both forms`() {
        // The invariant the chrome band's bottom edge relies on: the
        // band is read from the slot's top, so the slot must not
        // resize when the form flips — 64dp content + 24dp bottom
        // padding, for FULL and MINIMIZED alike.
        assertEquals(88, ClusterStateLogic.SLOT_HEIGHT_DP)
        assertEquals(
            ClusterStateLogic.CONTENT_HEIGHT_DP +
                ClusterStateLogic.BOTTOM_PADDING_DP,
            ClusterStateLogic.SLOT_HEIGHT_DP,
        )
        assertEquals(
            ClusterStateLogic.SLOT_HEIGHT_DP,
            ClusterStateLogic.slotHeightDp(full),
        )
        assertEquals(
            ClusterStateLogic.SLOT_HEIGHT_DP,
            ClusterStateLogic.slotHeightDp(mini),
        )
        assertEquals(
            ClusterStateLogic.slotHeightDp(full),
            ClusterStateLogic.slotHeightDp(mini),
        )
    }
}
