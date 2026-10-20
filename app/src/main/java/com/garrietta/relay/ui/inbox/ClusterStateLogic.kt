package com.garrietta.relay.ui.inbox

/**
 * The inbox bottom cluster's two forms (Garrett 2026-10-20): at the
 * top of the list it is the full cluster — the New conversation pill
 * plus the expressive search button; scrolled away from the top it
 * condenses into ONE circular expressive button in the bottom-right
 * corner carrying just the new-conversation action — his Koda
 * mini-player behavior ("when I scroll down the now playing player
 * minimizes to a small now playing option").
 */
enum class InboxClusterForm {
    /** New conversation pill + search button, side by side. */
    FULL,

    /** One circular expressive new-conversation button, bottom-right. */
    MINIMIZED,
}

/**
 * Pure scroll → cluster-form decisions (Garrett 2026-10-20).
 *
 * POSITION-based, driven by the same LazyListState the collapsing
 * header reads — one scroll truth, no competing drivers. This
 * supersedes the old direction-based auto-hide, which slid the whole
 * cluster off the bottom on any scroll-down and brought it back on
 * any scroll-up: the end state of "scrolled down" is now the
 * minimized circle — present and tappable — not an empty slot.
 *
 * The trigger, exactly:
 * - FULL only in the top zone: first visible item is item 0 AND its
 *   scroll offset is under [RESTORE_TOP_OFFSET_PX] — the bound the
 *   old auto-hide used for "back at the very top".
 * - MINIMIZED once the list is scrolled past [MINIMIZE_OFFSET_PX] of
 *   the first item, or onto any later item — Koda's model restores
 *   the full bar only at the top, so a scroll-UP away from the top
 *   does NOT restore early; it just keeps the circle.
 * - Between the two bounds (offsets 120..239 on item 0) the current
 *   form stands — a hysteresis band, so the boundary can't flicker.
 *
 * Offsets are LazyListState's raw pixel offsets, the same units the
 * header collapse and the old auto-hide driver consumed.
 *
 * The cluster's layout SLOT is the same height in both forms
 * ([SLOT_HEIGHT_DP]: the 64dp content row + the 24dp bottom padding),
 * because the form morph is a crossfade INSIDE the slot, never a
 * resize of it. That is what keeps the #171 chrome band's bottom edge
 * (measured from the slot's top) correct in both forms and through
 * the morph — see [slotHeightDp] and ChromeBandClipMath.
 *
 * Pure JVM logic; unit-tested (ClusterStateLogicTest).
 */
object ClusterStateLogic {

    /** At/under this first-item offset (item 0) the cluster is FULL. */
    const val RESTORE_TOP_OFFSET_PX = 120

    /** At/past this first-item offset the cluster is MINIMIZED. */
    const val MINIMIZE_OFFSET_PX = 240

    /** The cluster slot's content height: the pill's fixed 64dp. */
    const val CONTENT_HEIGHT_DP = 64

    /** The cluster slot's bottom padding. */
    const val BOTTOM_PADDING_DP = 24

    /** The cluster slot's total height — identical in both forms. */
    const val SLOT_HEIGHT_DP = CONTENT_HEIGHT_DP + BOTTOM_PADDING_DP

    /**
     * The cluster form for a list resting at ([firstVisibleItemIndex],
     * [firstVisibleItemScrollOffsetPx]), given the [current] form:
     * decisive outside the hysteresis band, sticky inside it.
     */
    fun formFor(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffsetPx: Int,
        current: InboxClusterForm,
    ): InboxClusterForm = when {
        firstVisibleItemIndex == 0 &&
            firstVisibleItemScrollOffsetPx < RESTORE_TOP_OFFSET_PX ->
            InboxClusterForm.FULL

        firstVisibleItemIndex > 0 ||
            firstVisibleItemScrollOffsetPx >= MINIMIZE_OFFSET_PX ->
            InboxClusterForm.MINIMIZED

        // Inside the hysteresis band: hold the current form.
        else -> current
    }

    /**
     * The form to compose on the FIRST frame, before any scroll event
     * — a chat-exit restore point can sit deep in the list, and the
     * cluster must start in its final form exactly like the rows and
     * the header do. No hysteresis here: not-in-the-top-zone is
     * minimized.
     */
    fun initialFormFor(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffsetPx: Int,
    ): InboxClusterForm =
        if (firstVisibleItemIndex == 0 &&
            firstVisibleItemScrollOffsetPx < RESTORE_TOP_OFFSET_PX
        ) {
            InboxClusterForm.FULL
        } else {
            InboxClusterForm.MINIMIZED
        }

    /**
     * The cluster slot's height for [form]: the same constant for
     * FULL and MINIMIZED — the invariant the chrome band's bottom
     * edge relies on (the band tracks the slot's top, and the slot
     * never resizes, so the band never has to know the form).
     */
    fun slotHeightDp(form: InboxClusterForm): Int = SLOT_HEIGHT_DP
}
