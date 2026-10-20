package com.garrietta.relay.ui.chat

/**
 * Pure header-layout decisions for the chat screen (Garrett 2026-10-20).
 *
 * The 1:1 header collapses on scroll (large photo → top-bar row). The
 * GROUP header must NOT: on his device video the "Dad, Grace" group
 * header rested at two different heights — the expanded form (76dp
 * avatar cluster inline) at the top of the thread and a compact
 * top-bar form once scrolled, because the group header consumed the
 * SAME scroll-collapse state as the 1:1 header and dropped its cluster
 * when it flipped. Every flip resized the Scaffold top bar, so the
 * message list's top inset jumped mid-scroll. His directive: "Group
 * chat header height should stay the larger of the two types you see
 * in this video."
 *
 * So: a group header's height is a CONSTANT — [GROUP_HEADER_HEIGHT_DP],
 * the expanded cluster row — in every state (entry, any scroll offset,
 * return from details). The collapse state machine keeps serving the
 * 1:1 header exactly as before; it simply never reaches a group.
 * Pure JVM logic; unit-tested (ChatHeaderLogicTest).
 */
object ChatHeaderLogic {

    /**
     * The group header row's constant content height: the expanded
     * presentation — back arrow, the 76dp avatar cluster box, and the
     * name/transport column centered against it. (The Scaffold adds
     * the status-bar inset above this row, as it does today.)
     */
    const val GROUP_HEADER_HEIGHT_DP = 76

    /**
     * Whether the header renders collapsed for a chat whose scroll
     * state says [scrollCollapsed]: groups NEVER collapse (their
     * height is the constant above); 1:1 chats follow the scroll
     * state exactly.
     */
    fun headerCollapsed(isGroup: Boolean, scrollCollapsed: Boolean): Boolean =
        !isGroup && scrollCollapsed
}
