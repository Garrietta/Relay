package com.garrietta.relay.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the chat header layout decisions (Garrett
 * 2026-10-20): a group chat's header height stays the LARGER of the
 * two forms his device video showed — the group header never
 * collapses, whatever the scroll state says; the 1:1 header keeps
 * following the scroll-collapse state exactly. Pure JVM.
 * Run on CI via testDebugUnitTest.
 */
class ChatHeaderLogicTest {

    @Test
    fun `group header never collapses - scrolled or not`() {
        // The bug: the group header consumed the 1:1 collapse state,
        // so scrolling flipped it to the compact top-bar height and
        // the message list jumped. Both scroll states must render the
        // group header expanded (its constant height).
        assertFalse(ChatHeaderLogic.headerCollapsed(isGroup = true, scrollCollapsed = true))
        assertFalse(ChatHeaderLogic.headerCollapsed(isGroup = true, scrollCollapsed = false))
    }

    @Test
    fun `one-to-one header follows the scroll state exactly`() {
        assertTrue(ChatHeaderLogic.headerCollapsed(isGroup = false, scrollCollapsed = true))
        assertFalse(ChatHeaderLogic.headerCollapsed(isGroup = false, scrollCollapsed = false))
    }

    @Test
    fun `group header height is the expanded cluster height`() {
        // The larger variant from the video: the header row is the
        // 76dp avatar-cluster box (back arrow and the name column
        // center against it). Pinned so a future cluster resize has
        // to move this constant deliberately, with the header height.
        assertEquals(76, ChatHeaderLogic.GROUP_HEADER_HEIGHT_DP)
    }
}
