package com.garrietta.relay.ui.chat

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Open-flight surface geometry (Garrett 2026-10-20, follow-on to the
 * top-of-chat clip round): where the chat's shared-bounds surface ends,
 * and the size its content is pinned to.
 *
 * The surface is the container morph's target. While a flight runs,
 * the morph's overlay copy of the surface renders ABOVE the whole chat
 * pane — including the composer bar, which is destination chrome
 * outside the surface (Build #175). When the surface ran the pane's
 * full height (Build #176), the copy's opaque background covered the
 * bar's zone for the entire flight: the bar faded in underneath the
 * copy and only popped into view when the copy lifted at settle —
 * "the whole message bar is disappearing on first load, every time."
 * The header never suffered this: the surface starts below it (the
 * Scaffold's top padding), so the copy never reaches it.
 *
 * The fix gives the bottom the top's treatment: the surface ends at
 * the composer zone's top ([surfaceHeightPx]), so its copy can never
 * enter the bar zone in either direction, and the bar rides the pane
 * transform whole from the first frame, exactly like the header. The
 * surface's CONTENT keeps the pane's full size ([pinSize]): the list
 * overflows the surface's bottom edge in place — that overflow is the
 * draw-behind, messages passing under the floating bar — while inside
 * the traveling copy the pinned layout means every item already sits
 * at its final geometry and the copy's clip merely reveals it (a
 * growing window, never a re-layout riding the animated bounds).
 */
internal object ChatSurfaceGeometry {

    /**
     * The shared surface's height inside the pane: the pane height
     * minus the measured composer-zone height, so the surface's bottom
     * edge — and therefore the morph copy's lowest possible reach —
     * lands exactly on the bar zone's top edge. Clamped at zero:
     * a zone taller than the pane (a transient measurement) yields an
     * empty surface, never a negative height.
     */
    fun surfaceHeightPx(paneHeightPx: Int, zoneHeightPx: Int): Int =
        (paneHeightPx - zoneHeightPx).coerceAtLeast(0)

    /**
     * The size the surface's content is pinned to: the pane's own
     * measured size, so the list's viewport and parking geometry are
     * the settled pane's regardless of the surface's (shorter) bounds
     * or the morph's animated bounds. Null until the pane has been
     * measured (zero/unknown size or density) — the caller falls back
     * to filling the surface for that first layout pass.
     */
    fun pinSize(paneWidthPx: Int, paneHeightPx: Int, density: Float): DpSize? =
        if (paneWidthPx > 0 && paneHeightPx > 0 && density > 0f) {
            DpSize(
                (paneWidthPx / density).dp,
                (paneHeightPx / density).dp,
            )
        } else {
            null
        }
}
