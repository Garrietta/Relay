package com.garrietta.relay.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The open-flight surface geometry: the morph copy must never reach
 * the composer zone, and the list's pinned layout must be the settled
 * pane's. Pure arithmetic, pinned exactly (Garrett 2026-10-20: "the
 * whole message bar is disappearing on first load of the chat").
 */
class ChatSurfaceGeometryTest {

    @Test
    fun `surface ends exactly at the composer zone's top`() {
        // The surface's bottom edge (top 0 + height) plus the zone is
        // the pane: the copy's lowest reach is the bar zone's top.
        val pane = 2400
        val zone = 290
        assertEquals(pane - zone, ChatSurfaceGeometry.surfaceHeightPx(pane, zone))
        assertEquals(
            pane,
            ChatSurfaceGeometry.surfaceHeightPx(pane, zone) + zone,
        )
    }

    @Test
    fun `zero zone leaves the surface full height`() {
        assertEquals(2400, ChatSurfaceGeometry.surfaceHeightPx(2400, 0))
    }

    @Test
    fun `zone taller than the pane clamps to zero, never negative`() {
        // A negative height would crash Compose layout; a transient
        // oversized zone measurement must degrade to an empty surface.
        assertEquals(0, ChatSurfaceGeometry.surfaceHeightPx(800, 1200))
        assertEquals(0, ChatSurfaceGeometry.surfaceHeightPx(800, 800))
    }

    @Test
    fun `pin size is the pane's own size in dp`() {
        // 1050px / 2.625 = 400dp; 2100px / 2.625 = 800dp exactly.
        val pin = ChatSurfaceGeometry.pinSize(1050, 2100, 2.625f)
        assertEquals(400.dp, pin?.width)
        assertEquals(800.dp, pin?.height)
    }

    @Test
    fun `pin size is null until the pane has been measured`() {
        assertNull(ChatSurfaceGeometry.pinSize(0, 2100, 2.625f))
        assertNull(ChatSurfaceGeometry.pinSize(1050, 0, 2.625f))
        assertNull(ChatSurfaceGeometry.pinSize(0, 0, 2.625f))
        assertNull(ChatSurfaceGeometry.pinSize(1050, 2100, 0f))
    }
}
