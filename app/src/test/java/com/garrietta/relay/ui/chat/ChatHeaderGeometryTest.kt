package com.garrietta.relay.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the 1:1 header's single-composition travel
 * (Garrett 2026-10-20): ONE avatar, name and label animate between
 * the standard (collapsed) and open (expanded) header layouts on a
 * single progress value, instead of the old AnimatedContent swap
 * that showed both titles at once mid-transition and teleported
 * the avatar. The endpoints must reproduce the two replaced layouts
 * EXACTLY — their own constants and arithmetic, with measured sizes
 * fed in as the layouts produced them — and the interpolated frames
 * must be the straight line between them. Pure JVM.
 * Run on CI via testDebugUnitTest.
 */
class ChatHeaderGeometryTest {

    // A 400dp-wide header; the back button's measured layout width
    // and height, and representative measured text heights (the real
    // values arrive from measurement at runtime — every expectation
    // below is derived from these inputs by the endpoint layouts'
    // own arithmetic, so the exact numbers don't matter, the
    // relationships do).
    private val width = 400f
    private val backW = 48f
    private val backH = 48f
    private val nameH = 24f
    private val labelH = 16f

    @Test
    fun `progress zero is the standard layout exactly`() {
        val g = ChatHeaderGeometry
        // The old collapsed Row: 64dp tall.
        assertEquals(64f, g.headerHeightDp(0f, nameH, labelH, 0f), EPS)
        // 40dp avatar immediately right of the back button, centered
        // vertically in the row: y = (64 - 40) / 2 = 12.
        assertEquals(40f, g.avatarSizeDp(0f), EPS)
        assertEquals(backW, g.avatarXDp(0f, width, backW), EPS)
        assertEquals(12f, g.avatarYDp(0f), EPS)
        // Text column at back + avatar + 12dp gap, the pair centered
        // vertically: name y = (64 - 24 - 16) / 2 = 12, label under it.
        val textX = backW + 40f + 12f
        assertEquals(textX, g.textXDp(0f, width, backW, 200f), EPS)
        assertEquals(12f, g.nameYDp(0f, nameH, labelH), EPS)
        assertEquals(12f + nameH, g.labelYDp(0f, nameH, labelH), EPS)
        // Back button centered vertically in the 64dp row.
        assertEquals(8f, g.backYDp(0f, backH), EPS)
        // The row's remainder, minus its 16dp end pad, caps the text.
        assertEquals(
            width - textX - 16f,
            g.textMaxWidthDp(0f, width, backW),
            EPS,
        )
    }

    @Test
    fun `progress one is the open layout exactly`() {
        val g = ChatHeaderGeometry
        // The old expanded Column: 2 top pad + 48 avatar + 4 gap +
        // name + label + 10 bottom pad.
        assertEquals(
            2f + 48f + 4f + nameH + labelH + 10f,
            g.headerHeightDp(1f, nameH, labelH, 0f),
            EPS,
        )
        // With "Add contact?" present, its measured height joins the
        // stack — the old column's conditional content, nothing more.
        assertEquals(
            2f + 48f + 4f + nameH + labelH + 40f + 10f,
            g.headerHeightDp(1f, nameH, labelH, 40f),
            EPS,
        )
        // 48dp avatar centered horizontally, at the 2dp top pad.
        assertEquals(48f, g.avatarSizeDp(1f), EPS)
        assertEquals((width - 48f) / 2f, g.avatarXDp(1f, width, backW), EPS)
        assertEquals(2f, g.avatarYDp(1f), EPS)
        // Name centered under the avatar; label directly under the
        // name; both capped by the 56dp side pads.
        assertEquals(
            (width - 200f) / 2f,
            g.textXDp(1f, width, backW, 200f),
            EPS,
        )
        assertEquals(2f + 48f + 4f, g.nameYDp(1f, nameH, labelH), EPS)
        assertEquals(
            2f + 48f + 4f + nameH,
            g.labelYDp(1f, nameH, labelH),
            EPS,
        )
        assertEquals(
            width - 2f * 56f,
            g.textMaxWidthDp(1f, width, backW),
            EPS,
        )
        // Back button overlays the top-start corner.
        assertEquals(0f, g.backYDp(1f, backH), EPS)
        // "Add contact?" centered, directly under the label.
        assertEquals(
            (width - 150f) / 2f,
            g.addContactXDp(width, 150f),
            EPS,
        )
        assertEquals(
            2f + 48f + 4f + nameH + labelH,
            g.addContactYDp(1f, nameH, labelH),
            EPS,
        )
    }

    @Test
    fun `mid progress is the straight line between the endpoints`() {
        val g = ChatHeaderGeometry
        // Avatar size and position at the halfway frame: the size is
        // the midpoint, and the CENTER sits halfway between the
        // collapsed slot's center and the header's center — the
        // avatar travels, it never jumps or crossfades.
        assertEquals(44f, g.avatarSizeDp(0.5f), EPS)
        val collapsedCenter = backW + 20f
        val midCenter = (collapsedCenter + width / 2f) / 2f
        assertEquals(
            midCenter,
            g.avatarXDp(0.5f, width, backW) + g.avatarSizeDp(0.5f) / 2f,
            EPS,
        )
        assertEquals(7f, g.avatarYDp(0.5f), EPS)
        // Header height halfway between the two resting heights.
        val collapsedH = g.headerHeightDp(0f, nameH, labelH, 0f)
        val openH = g.headerHeightDp(1f, nameH, labelH, 0f)
        assertEquals(
            (collapsedH + openH) / 2f,
            g.headerHeightDp(0.5f, nameH, labelH, 0f),
            EPS,
        )
        // The label stays glued under the name at every progress.
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(
                g.nameYDp(t, nameH, labelH) + nameH,
                g.labelYDp(t, nameH, labelH),
                EPS,
            )
        }
    }

    @Test
    fun `the avatar slot stays square - the shape can only scale uniformly`() {
        // One size value drives the slot at every progress, and the
        // travel is monotonic: no frame is larger than the open
        // avatar or smaller than the standard one (inside the
        // spring's own 0..1 range).
        var previous = Float.NEGATIVE_INFINITY
        for (i in 0..10) {
            val size = ChatHeaderGeometry.avatarSizeDp(i / 10f)
            assertTrue(size in 40f..48f)
            assertTrue(size >= previous)
            previous = size
        }
    }

    @Test
    fun `add contact fades with the expansion and answers taps only when open`() {
        val g = ChatHeaderGeometry
        assertEquals(0f, g.addContactAlpha(0f), EPS)
        assertEquals(0.5f, g.addContactAlpha(0.5f), EPS)
        assertEquals(1f, g.addContactAlpha(1f), EPS)
        // Spring overshoot can push progress past either end; the
        // alpha stays a legal opacity regardless.
        assertEquals(1f, g.addContactAlpha(1.15f), EPS)
        assertEquals(0f, g.addContactAlpha(-0.1f), EPS)
        assertFalse(g.addContactInteractive(0f))
        assertFalse(g.addContactInteractive(0.5f))
        assertTrue(g.addContactInteractive(1f))
    }

    @Test
    fun `spring overshoot never pushes the back button off the top edge`() {
        // Past-open overshoot would put the back button at a negative
        // y (its open position is the header's top corner) and clip
        // it against the header's bounds; it clamps at the corner.
        assertEquals(0f, ChatHeaderGeometry.backYDp(1.2f, backH), EPS)
        assertEquals(0f, ChatHeaderGeometry.backYDp(2f, backH), EPS)
    }

    @Test
    fun `text cap never goes negative on a narrow header`() {
        // A very narrow header (or a wide back button) must not hand
        // measurement a negative width.
        assertEquals(
            0f,
            ChatHeaderGeometry.textMaxWidthDp(0f, 100f, 60f),
            EPS,
        )
    }

    private companion object {
        const val EPS = 0.001f
    }
}
