package com.garrietta.relay.ui.chat

/**
 * The 1:1 header's collapse/expand as ONE traveling composition
 * (Garrett 2026-10-20): "a individual chat needs an animation moving
 * the shape from it's standard to it's open position."
 *
 * The old header SWAPPED two separate layouts with an AnimatedContent
 * crossfade — the expanded column (large centered avatar, centered
 * name under it) against the collapsed row (small avatar top-left,
 * name beside it). Mid-transition frames showed BOTH titles at once —
 * the centered name still fading out while the left-aligned name was
 * already in — and the avatar never traveled: the large one faded in
 * place while the small one popped in at its corner. His video's
 * arrow points at exactly that teleport.
 *
 * Now there is ONE composition — one avatar, one name, one transport
 * label, one back button — and a single progress value drives every
 * element's position and size between the two endpoint layouts:
 * progress 0 is the STANDARD (collapsed) state, progress 1 is the
 * OPEN (expanded) state. Nothing crossfades, so no frame can show a
 * double image; the avatar passes through every intermediate position
 * and size on its way, on RelaySprings.bouncy like everything else.
 *
 * The endpoints are pixel-equivalent to the two layouts this
 * replaces, because every constant below IS one of those layouts'
 * own numbers and every position is derived from the measured sizes
 * of the same elements (the back button's measured width is the
 * collapsed avatar's x; the measured text heights center the
 * collapsed text column and stack the expanded one):
 *
 *  - STANDARD (the old collapsed Row, height 64dp): back button,
 *    40dp avatar, 12dp gap, name + transport column, 16dp end pad.
 *  - OPEN (the old expanded Column): back button overlaying the
 *    top-start corner, 48dp avatar centered at a 2dp top pad, 4dp
 *    gap, centered name capped by 56dp side pads, centered transport
 *    label, the "Add contact?" button centered under the label when
 *    present, 10dp bottom pad.
 *
 * Only the interpolated frames are new, and they are the straight
 * line between the two endpoints for every attribute (position,
 * size, the texts' width cap) — the shape can never squash: the
 * avatar's size interpolates as a single dp value and ContactAvatar
 * renders its shape by uniform scale inside its square slot at any
 * size, with the monogram's text style identical at both endpoints.
 *
 * Pure JVM math; unit-tested (ChatHeaderGeometryTest).
 */
internal object ChatHeaderGeometry {

    /** The collapsed row's height — the top-bar height it rests at. */
    const val COLLAPSED_HEIGHT_DP = 64f

    /** Avatar slot size in the standard (collapsed) state. */
    const val AVATAR_COLLAPSED_DP = 40f

    /** Avatar slot size in the open (expanded) state. */
    const val AVATAR_EXPANDED_DP = 48f

    /** Expanded stack: padding above the avatar. */
    const val EXPANDED_TOP_PAD_DP = 2f

    /** Expanded stack: gap between the avatar and the name. */
    const val EXPANDED_AVATAR_GAP_DP = 4f

    /** Expanded stack: padding below the last element. */
    const val EXPANDED_BOTTOM_PAD_DP = 10f

    /** Expanded name/label width cap: this pad on EACH side. */
    const val EXPANDED_TEXT_SIDE_CAP_DP = 56f

    /** Collapsed row: gap between the avatar and the text column. */
    const val COLLAPSED_TEXT_GAP_DP = 12f

    /** Collapsed row: padding after the text column. */
    const val COLLAPSED_END_PAD_DP = 16f

    fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    /** The avatar slot's size at [progress] (one value — the slot
     * stays square, so the shape inside can only scale uniformly). */
    fun avatarSizeDp(progress: Float): Float =
        lerp(AVATAR_COLLAPSED_DP, AVATAR_EXPANDED_DP, progress)

    /**
     * The open state's total height: the expanded stack, given the
     * measured heights of the name, the transport label, and the
     * "Add contact?" button (0 when the button is absent — exactly
     * the conditional content of the old expanded column).
     */
    fun expandedHeightDp(
        nameHeightDp: Float,
        labelHeightDp: Float,
        addContactHeightDp: Float,
    ): Float =
        EXPANDED_TOP_PAD_DP + AVATAR_EXPANDED_DP + EXPANDED_AVATAR_GAP_DP +
            nameHeightDp + labelHeightDp + addContactHeightDp +
            EXPANDED_BOTTOM_PAD_DP

    /** The header's own height at [progress]. */
    fun headerHeightDp(
        progress: Float,
        nameHeightDp: Float,
        labelHeightDp: Float,
        addContactHeightDp: Float,
    ): Float = lerp(
        COLLAPSED_HEIGHT_DP,
        expandedHeightDp(nameHeightDp, labelHeightDp, addContactHeightDp),
        progress,
    )

    /**
     * The avatar's left edge at [progress]. Its CENTER travels from
     * the collapsed slot (immediately right of the back button, whose
     * measured layout width is [backWidthDp]) to the header's
     * horizontal center; the left edge follows from the animated size,
     * so both endpoints land exactly.
     */
    fun avatarXDp(progress: Float, headerWidthDp: Float, backWidthDp: Float): Float {
        val centerX = lerp(
            backWidthDp + AVATAR_COLLAPSED_DP / 2f,
            headerWidthDp / 2f,
            progress,
        )
        return centerX - avatarSizeDp(progress) / 2f
    }

    /** The avatar's top edge: vertically centered in the collapsed
     * row, at the expanded stack's top pad when open. */
    fun avatarYDp(progress: Float): Float = lerp(
        (COLLAPSED_HEIGHT_DP - AVATAR_COLLAPSED_DP) / 2f,
        EXPANDED_TOP_PAD_DP,
        progress,
    )

    /** The collapsed text column's left edge: back button, avatar,
     * gap — the old row's own arithmetic. */
    fun collapsedTextXDp(backWidthDp: Float): Float =
        backWidthDp + AVATAR_COLLAPSED_DP + COLLAPSED_TEXT_GAP_DP

    /**
     * The widest the name/label may measure at [progress]: collapsed,
     * what the row leaves after the back button, avatar, gap and end
     * pad; open, the width between the 56dp side caps. (Both texts
     * share this cap, as both old layouts capped them alike.)
     */
    fun textMaxWidthDp(
        progress: Float,
        headerWidthDp: Float,
        backWidthDp: Float,
    ): Float = lerp(
        headerWidthDp - collapsedTextXDp(backWidthDp) - COLLAPSED_END_PAD_DP,
        headerWidthDp - 2f * EXPANDED_TEXT_SIDE_CAP_DP,
        progress,
    ).coerceAtLeast(0f)

    /** A text's left edge: the collapsed column edge when standard,
     * centered on the header when open. */
    fun textXDp(
        progress: Float,
        headerWidthDp: Float,
        backWidthDp: Float,
        textWidthDp: Float,
    ): Float = lerp(
        collapsedTextXDp(backWidthDp),
        (headerWidthDp - textWidthDp) / 2f,
        progress,
    )

    /** The name's top edge: the collapsed column is vertically
     * centered as a pair; the open stack starts below the avatar. */
    fun nameYDp(progress: Float, nameHeightDp: Float, labelHeightDp: Float): Float =
        lerp(
            (COLLAPSED_HEIGHT_DP - nameHeightDp - labelHeightDp) / 2f,
            EXPANDED_TOP_PAD_DP + AVATAR_EXPANDED_DP + EXPANDED_AVATAR_GAP_DP,
            progress,
        )

    /** The transport label sits directly under the name in BOTH
     * endpoint layouts, at every progress between them. */
    fun labelYDp(progress: Float, nameHeightDp: Float, labelHeightDp: Float): Float =
        nameYDp(progress, nameHeightDp, labelHeightDp) + nameHeightDp

    /** The back button's top edge: centered in the collapsed row,
     * pinned to the header's top corner when open. Never negative —
     * the spring's overshoot past open must not push it off the top
     * edge and clip it. */
    fun backYDp(progress: Float, backHeightDp: Float): Float = lerp(
        (COLLAPSED_HEIGHT_DP - backHeightDp) / 2f,
        0f,
        progress,
    ).coerceAtLeast(0f)

    /** The "Add contact?" button's left edge: centered on the header
     * (its open-state home; it is invisible in the standard state). */
    fun addContactXDp(headerWidthDp: Float, buttonWidthDp: Float): Float =
        (headerWidthDp - buttonWidthDp) / 2f

    /** The "Add contact?" button's top edge: directly under the
     * transport label, riding the label's own travel. */
    fun addContactYDp(
        progress: Float,
        nameHeightDp: Float,
        labelHeightDp: Float,
    ): Float = labelYDp(progress, nameHeightDp, labelHeightDp) + labelHeightDp

    /** The button rides the expansion itself: fully transparent in
     * the standard state, fully opaque when open. */
    fun addContactAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** The button answers taps only once the header has (for every
     * practical purpose) arrived open — a mid-flight or collapsed
     * tap on its fading form is swallowed, never routed. */
    fun addContactInteractive(progress: Float): Boolean = progress >= 0.99f
}
