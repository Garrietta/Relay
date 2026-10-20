package com.garrietta.relay.ui.chat

/**
 * Pure geometry for the group header cluster's scroll-collapse
 * (Garrett 2026-10-20). The collapse used to be an
 * AnimatedVisibility horizontal size animation — and a size
 * animation CLIPS its content to the animated window for as long as
 * it runs. The window's left and bottom edges sit exactly on the
 * cluster box's left and bottom edges, and the cluster's tiles are
 * NOT confined to the box: in the 2-person overlap their centers
 * clamp to [0.36, 0.64] of the box while each tile's half-size is
 * 0.375, so a tile's extent reaches 1.015 of the box — a ~1.1dp
 * sliver past the box's edges at the header's 76dp box, invisible at
 * rest (nothing clips) but shaved flat the moment the collapse ran
 * (his video: the bottom-left corner of Mom's "M" tile cut by an
 * invisible straight boundary mid-scroll, whole again at settle).
 *
 * The collapse is therefore layer-driven instead: the slot's width
 * animates on the same house spring, the content is measured ONCE at
 * its full extent and never re-laid, and the clip that sweeps the
 * reveal is extended by [CLIP_MARGIN_FRAC] past the box on the
 * three fixed edges (left, top, bottom) — beyond every tile's full
 * extent — while the fourth edge (the sweep) does the revealing.
 * The functions here are the exact model of the modifier stack the
 * header builds (slot offset −margin, content offset +margin, clip
 * to the slot's bounds): they decide the numbers, and the tests
 * prove the containment for every member count, so the composable
 * stays a thin shell over them. Pure JVM; unit-tested
 * (ClusterCollapseGeometryTest).
 */
object ClusterCollapseGeometry {

    /**
     * How far the farthest cluster tile reaches past the cluster
     * box's edge, as a fraction of the box size (one side).
     *
     * 2 members ([com.garrietta.relay.ui.theme.GroupAvatarCluster]'s
     * diagonal overlap): centers clamp to [0.36, 0.64] and the tile
     * half-size is 0.75 / 2 = 0.375, so the extreme extent is
     * 0.64 + 0.375 = 1.015 → 0.015 past the edge. Every other count:
     * each shape is inscribed in the circle of radius size/2 about
     * its tile's center (rotations cannot reach past that circle)
     * and placements clamp centers to [half, 1 − half], so extents
     * stay inside the box → 0.
     */
    fun tileOverhangFrac(memberCount: Int): Float =
        if (memberCount == 2) 0.015f else 0f

    /**
     * The clip margin, as a fraction of the cluster box: the clip
     * window extends this far past the box on its fixed edges. Twice
     * the worst tile overhang (0.015), so the margin strictly
     * contains every tile's full extent at every member count.
     */
    const val CLIP_MARGIN_FRAC = 0.03f

    /** The clip margin in dp for a cluster box of [boxSizeDp]. */
    fun clipMarginDp(boxSizeDp: Float): Float = CLIP_MARGIN_FRAC * boxSizeDp

    /**
     * The animated slot's width at collapse [fraction] (0 = gone,
     * 1 = expanded) for a slot whose expanded width is
     * [fullWidthDp]. Spring overshoot past 1 is passed through (the
     * old IntSize spring overshot identically); undershoot clamps at
     * 0 — a width can never be negative.
     */
    fun slotWidthDp(fraction: Float, fullWidthDp: Float): Float =
        (fraction * fullWidthDp).coerceAtLeast(0f)

    /** A rectangle in dp, in cluster-content coordinates. */
    data class RectDp(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The union of every tile's full extent, in content coordinates
     * (the cluster box occupies [0, boxSizeDp]²): tiles may reach
     * [tileOverhangFrac] past the box on any side.
     */
    fun unionTileExtentDp(memberCount: Int, boxSizeDp: Float): RectDp {
        val o = tileOverhangFrac(memberCount) * boxSizeDp
        return RectDp(-o, -o, boxSizeDp + o, boxSizeDp + o)
    }

    /**
     * The clip window the collapse applies at [fraction], in content
     * coordinates: extended by the margin past the box on the left,
     * top and bottom; the right edge is the sweep — the slot's
     * animated width, shifted by the same margin the slot is offset
     * by (so at fraction 0 the window is exactly closed, and at
     * fraction 1 it ends past the trailing gap, revealing everything
     * the settled layout shows).
     */
    fun clipRectDp(fraction: Float, boxSizeDp: Float, slotFullWidthDp: Float): RectDp {
        val m = clipMarginDp(boxSizeDp)
        return RectDp(
            left = -m,
            top = -m,
            right = slotWidthDp(fraction, slotFullWidthDp) - m,
            bottom = boxSizeDp + m,
        )
    }
}
