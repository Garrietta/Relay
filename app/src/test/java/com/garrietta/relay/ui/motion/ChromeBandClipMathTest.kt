package com.garrietta.relay.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the chrome-band clip span math (Garrett
 * 2026-10-20): the conversation container morph's overlay copy is
 * intersected with the vertical band the inbox list surface occupies,
 * so traveling thread content passes BEHIND the main page's header
 * (collapsing title + pill bar) and its floating bottom cluster
 * instead of painting over them — the top-row hover in his device
 * video. Pure JVM floats; the geometry the clip builds a Rect from.
 * Run on CI via testDebugUnitTest.
 */
class ChromeBandClipMathTest {

    // A representative window: 2400px tall, list surface occupying
    // y in [320, 2080] between the expanded header and the bottom
    // cluster.
    private val bandTop = 320f
    private val bandBottom = 2080f

    @Test
    fun `unmeasured band leaves every span unchanged`() {
        // Before the inbox first lays out (or in a host with no
        // chrome measurement), topPx = 0 and bottomPx = +infinity:
        // the clip degenerates to the pre-fix shape clip alone.
        val spans = listOf(0f to 2400f, 100f to 900f, 2000f to 2400f, 0f to 0f)
        for ((top, bottom) in spans) {
            assertEquals(
                "span $top..$bottom",
                top to bottom,
                ChromeBandClipMath.visibleSpan(
                    top, bottom, 0f, Float.POSITIVE_INFINITY,
                ),
            )
        }
    }

    @Test
    fun `span fully inside the band is unchanged`() {
        // An ordinary (non-top, non-bottom) row's morph bounds never
        // touch either chrome line: no clipping, today's behavior.
        assertEquals(
            bandTop to bandBottom,
            ChromeBandClipMath.visibleSpan(bandTop, bandBottom, bandTop, bandBottom),
        )
        assertEquals(
            640f to 1500f,
            ChromeBandClipMath.visibleSpan(640f, 1500f, bandTop, bandBottom),
        )
    }

    @Test
    fun `full-screen thread surface is sliced to the band in both directions`() {
        // The morph's bounds at the chat end of the flight: the whole
        // window. Whatever direction it travels, only the band slice
        // may ever be drawn — the surface emerges from behind the
        // header and lands behind it on the way back.
        val visible = ChromeBandClipMath.visibleSpan(0f, 2400f, bandTop, bandBottom)
        assertEquals(bandTop to bandBottom, visible)
        assertTrue(visible.first >= bandTop)
        assertTrue(visible.second <= bandBottom)
    }

    @Test
    fun `top line clips only the top`() {
        // Bounds straddling the header line but well above the bottom
        // cluster: bottom untouched, top raised to the band edge.
        assertEquals(
            bandTop to 1500f,
            ChromeBandClipMath.visibleSpan(80f, 1500f, bandTop, bandBottom),
        )
    }

    @Test
    fun `bottom line clips only the bottom`() {
        // Bounds straddling the bottom cluster but clear of the
        // header: top untouched, bottom lowered to the cluster's top.
        assertEquals(
            900f to bandBottom,
            ChromeBandClipMath.visibleSpan(900f, 2350f, bandTop, bandBottom),
        )
    }

    @Test
    fun `span entirely above the band is empty, never inverted`() {
        // Early in an entry (or late in an exit) the animated bounds
        // can sit wholly inside the header band: nothing may draw,
        // expressed as a zero-height slice ON the band edge.
        val visible = ChromeBandClipMath.visibleSpan(40f, 260f, bandTop, bandBottom)
        assertEquals(bandTop to bandTop, visible)
    }

    @Test
    fun `span entirely below the band is empty, never inverted`() {
        val visible = ChromeBandClipMath.visibleSpan(2200f, 2380f, bandTop, bandBottom)
        assertEquals(bandBottom to bandBottom, visible)
    }

    @Test
    fun `collapsed header moves the top line up and the math follows`() {
        // The protected region is whatever the chrome occupies at
        // transition time: with the header fully collapsed the band
        // starts higher, and a span crossing the EXPANDED line but
        // not the collapsed one is now fully visible.
        val collapsedTop = 190f
        assertEquals(
            240f to 900f,
            ChromeBandClipMath.visibleSpan(240f, 900f, collapsedTop, bandBottom),
        )
        assertEquals(
            collapsedTop to 900f,
            ChromeBandClipMath.visibleSpan(120f, 900f, collapsedTop, bandBottom),
        )
    }

    @Test
    fun `degenerate crossed band yields an empty slice`() {
        // A transient layout frame could report a bottom edge above
        // the top edge; the result must clamp to empty, never to a
        // negative height that would corrupt the clip Rect.
        val visible = ChromeBandClipMath.visibleSpan(0f, 2400f, 1500f, 900f)
        assertTrue(visible.second >= visible.first)
        assertEquals(visible.first, visible.second)
    }

    @Test
    fun `band bottom is the cluster slot top in the full form`() {
        // The FULL cluster (pill + search button) composed: its slot
        // occupies the bottom 264px (88dp at 3x) of the window, and
        // the band's bottom edge is the slot's top.
        val slotTop = 2400f - 264f
        assertEquals(
            slotTop,
            ChromeBandClipMath.bandBottomForCluster(slotTop, 264),
        )
    }

    @Test
    fun `band bottom is the same slot top in the minimized form`() {
        // The MINIMIZED circular button (Garrett 2026-10-20): the
        // cluster condenses INSIDE the same constant-height slot —
        // the slot does not resize between forms or during the
        // morph — so the derivation yields the identical line, and a
        // morph to/from the last visible row passes behind the
        // circle exactly as it did behind the pill.
        val slotTop = 2400f - 264f
        assertEquals(
            ChromeBandClipMath.bandBottomForCluster(slotTop, 264),
            ChromeBandClipMath.bandBottomForCluster(slotTop, 264),
        )
        assertEquals(
            slotTop,
            ChromeBandClipMath.bandBottomForCluster(slotTop, 264),
        )
        // Any composed (positive) height keeps the band closed at
        // the slot's top; the form's internals never enter the math.
        assertEquals(
            slotTop,
            ChromeBandClipMath.bandBottomForCluster(slotTop, 1),
        )
    }

    @Test
    fun `band bottom opens when the cluster is not composed`() {
        // Height 0 = the cluster node has left composition (the
        // inbox is not on screen): there is no bottom chrome to
        // protect, so the band opens to the window bottom — the
        // pre-band degenerate behavior.
        assertEquals(
            Float.POSITIVE_INFINITY,
            ChromeBandClipMath.bandBottomForCluster(2136f, 0),
        )
    }

    @Test
    fun `visible slice never escapes the band for any span`() {
        // The invariant behind Garrett's report: for ANY animated
        // bounds, the drawn slice lies entirely within the band.
        val edges = listOf(0f, 100f, 319f, 320f, 321f, 1200f, 2079f, 2080f, 2081f, 2400f)
        for (top in edges) {
            for (bottom in edges) {
                if (bottom < top) continue
                val (visibleTop, visibleBottom) =
                    ChromeBandClipMath.visibleSpan(top, bottom, bandTop, bandBottom)
                assertTrue(
                    "span $top..$bottom -> $visibleTop..$visibleBottom escapes the top",
                    visibleTop >= bandTop,
                )
                assertTrue(
                    "span $top..$bottom -> $visibleTop..$visibleBottom escapes the bottom",
                    visibleBottom <= bandBottom,
                )
                assertTrue(
                    "span $top..$bottom -> $visibleTop..$visibleBottom inverted",
                    visibleBottom >= visibleTop,
                )
            }
        }
    }
}
