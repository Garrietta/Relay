package com.garrietta.relay.ui.chat

import com.garrietta.relay.data.repo.ScrollPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `group cluster scrolls away only for groups, only when scrolled`() {
        // The restored behavior (Garrett 2026-10-20): on vertical
        // scroll the group cluster exits to the left; at the top it
        // is in line. 1:1 chats have no cluster — their header follows
        // the separate collapse path and this axis never fires.
        assertTrue(
            ChatHeaderLogic.groupClusterCollapsed(isGroup = true, scrollCollapsed = true),
        )
        assertFalse(
            ChatHeaderLogic.groupClusterCollapsed(isGroup = true, scrollCollapsed = false),
        )
        assertFalse(
            ChatHeaderLogic.groupClusterCollapsed(isGroup = false, scrollCollapsed = true),
        )
        assertFalse(
            ChatHeaderLogic.groupClusterCollapsed(isGroup = false, scrollCollapsed = false),
        )
    }

    @Test
    fun `add contact shows for a bare-number title and prefills the peer address`() {
        // Unknown peer: the header title IS their number (the
        // Unknown-category signal). The prefill is the conversation's
        // resolved peer address in its full form — not the possibly
        // reformatted title the user happens to see.
        assertEquals(
            ChatHeaderLogic.AddContactTarget.Phone("+13172866929"),
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "(317) 286-6929", peerAddress = "+13172866929",
            ),
        )
        // Short-code senders (his campaign-SMS examples) are bare
        // numbers too, and their title and address agree.
        assertEquals(
            ChatHeaderLogic.AddContactTarget.Phone("454545"),
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "454545", peerAddress = "454545",
            ),
        )
        // A raw unformatted title works the same.
        assertEquals(
            ChatHeaderLogic.AddContactTarget.Phone("+15551234567"),
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "+15551234567", peerAddress = "+15551234567",
            ),
        )
    }

    @Test
    fun `add contact hides for a resolved contact name`() {
        // A name in the title means contact resolution found them —
        // known contacts never see the button.
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "Grace Ambrose", peerAddress = "+15551234567",
            ),
        )
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "Dad", peerAddress = "+15551234567",
            ),
        )
    }

    @Test
    fun `add contact never shows for groups or the self-chat`() {
        // Even with a bare-number title, membership/self axes win.
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = true, isSelf = false,
                peerName = "(317) 286-6929", peerAddress = "+13172866929",
            ),
        )
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = true,
                peerName = "(317) 286-6929", peerAddress = "+13172866929",
            ),
        )
    }

    @Test
    fun `add contact prefills email for an unresolved email peer only`() {
        // RCS email participant with no resolved name: the title is
        // the address itself — show, prefilled with the email.
        assertEquals(
            ChatHeaderLogic.AddContactTarget.Email("someone@icloud.com"),
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "someone@icloud.com",
                peerAddress = "someone@icloud.com",
            ),
        )
        // The same identity WITH a resolved contact name in the title
        // is already known (the Adam Rippy shape) — hide.
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "Adam Rippy", peerAddress = "adam@icloud.com",
            ),
        )
    }

    @Test
    fun `add contact hides without an identity or for the ghost title`() {
        // No address resolved yet (participants still loading and no
        // recipient fallback): nothing to prefill, no button.
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "454545", peerAddress = "",
            ),
        )
        // The UNKNOWN_SENDER! ghost's title is letters and junk —
        // never a bare number, never offered as a contact.
        assertNull(
            ChatHeaderLogic.addContactTarget(
                isGroup = false, isSelf = false,
                peerName = "UNKNOWN_SENDER!", peerAddress = "UNKNOWN_SENDER!",
            ),
        )
    }

    @Test
    fun `cluster state can never change the group header height decision`() {
        // Height-neutrality, proven at the decision level: the height
        // axis (headerCollapsed) takes no cluster input, so for BOTH
        // cluster states — present and scrolled away — the group
        // header stays at the same pinned height, and the pinned
        // value does not move.
        for (scrolled in listOf(true, false)) {
            assertFalse(
                ChatHeaderLogic.headerCollapsed(isGroup = true, scrollCollapsed = scrolled),
            )
            assertEquals(76, ChatHeaderLogic.GROUP_HEADER_HEIGHT_DP)
        }
        // And the two axes genuinely vary independently: same scroll
        // state, opposite cluster verdicts would be a coupling bug.
        assertTrue(ChatHeaderLogic.groupClusterCollapsed(true, true))
        assertFalse(ChatHeaderLogic.groupClusterCollapsed(true, false))
    }

    // ---- Initial collapse state (Garrett 2026-10-20) ----
    //
    // The repro: a chat left AT the newest message reopened with the
    // header already collapsed (the group cluster missing at rest),
    // because the initializer sampled the message list before its
    // first emission landed and read "no emission yet" as "opens
    // scrolled" for every saved chat. The snapshot records the
    // save-time newest message, so "left at the bottom" is decidable
    // without the live list.

    @Test
    fun `savedAtBottom - the snapshot's anchor is its save-time newest at offset zero`() {
        assertTrue(
            ChatHeaderLogic.savedAtBottom(
                ScrollPosition(messageId = 42L, scrollOffset = 0, newestMessageId = 42L, newestMessageDate = 1_000L),
            ),
        )
        // Anchor is an older message: left mid-history.
        assertFalse(
            ChatHeaderLogic.savedAtBottom(
                ScrollPosition(messageId = 7L, scrollOffset = 0, newestMessageId = 42L, newestMessageDate = 1_000L),
            ),
        )
        // Anchor is the newest but left mid-bounce (offset non-zero):
        // the landing's own driver state is collapsed, so the header
        // starts collapsed to match it.
        assertFalse(
            ChatHeaderLogic.savedAtBottom(
                ScrollPosition(messageId = 42L, scrollOffset = 30, newestMessageId = 42L, newestMessageDate = 1_000L),
            ),
        )
        // Legacy snapshot (no newest fields recorded): cannot prove
        // bottom — reads false even when the anchor might be it.
        assertFalse(
            ChatHeaderLogic.savedAtBottom(
                ScrollPosition(messageId = 42L, scrollOffset = 0, newestMessageId = -1L, newestMessageDate = -1L),
            ),
        )
    }

    @Test
    fun `initial state - no saved position starts expanded`() {
        assertFalse(ChatHeaderLogic.initialHeaderCollapsed(null, null, null))
        assertFalse(ChatHeaderLogic.initialHeaderCollapsed(null, 42L, 1_000L))
    }

    @Test
    fun `initial state - no emission yet, left at the bottom starts EXPANDED`() {
        // Garrett's repro: first emission lags the first frame, the
        // chat was left at the newest message. Old code: collapsed.
        val saved = ScrollPosition(
            messageId = 42L, scrollOffset = 0,
            newestMessageId = 42L, newestMessageDate = 1_000L,
        )
        assertFalse(ChatHeaderLogic.initialHeaderCollapsed(saved, null, null))
    }

    @Test
    fun `initial state - no emission yet, left scrolled starts collapsed`() {
        val scrolled = ScrollPosition(
            messageId = 7L, scrollOffset = 0,
            newestMessageId = 42L, newestMessageDate = 1_000L,
        )
        assertTrue(ChatHeaderLogic.initialHeaderCollapsed(scrolled, null, null))
        // Legacy snapshot without an emission: conservative, as before.
        val legacy = ScrollPosition(
            messageId = 7L, scrollOffset = 0,
            newestMessageId = -1L, newestMessageDate = -1L,
        )
        assertTrue(ChatHeaderLogic.initialHeaderCollapsed(legacy, null, null))
    }

    @Test
    fun `initial state - live emission readings are unchanged`() {
        val bottom = ScrollPosition(
            messageId = 42L, scrollOffset = 0,
            newestMessageId = 42L, newestMessageDate = 1_000L,
        )
        // Anchor is the live newest: expanded.
        assertFalse(ChatHeaderLogic.initialHeaderCollapsed(bottom, 42L, 1_000L))
        // Tail grew after the save: the open lands at the new bottom,
        // so expanded even though the anchor is no longer the newest.
        assertFalse(ChatHeaderLogic.initialHeaderCollapsed(bottom, 99L, 2_000L))
        // Scrolled anchor, no growth: collapsed.
        val scrolled = ScrollPosition(
            messageId = 7L, scrollOffset = 0,
            newestMessageId = 42L, newestMessageDate = 1_000L,
        )
        assertTrue(ChatHeaderLogic.initialHeaderCollapsed(scrolled, 42L, 1_000L))
        // Bottom anchor at a non-zero offset: collapsed, matching the
        // landing's driver state (the long-standing offset rule).
        val bounced = ScrollPosition(
            messageId = 42L, scrollOffset = 30,
            newestMessageId = 42L, newestMessageDate = 1_000L,
        )
        assertTrue(ChatHeaderLogic.initialHeaderCollapsed(bounced, 42L, 1_000L))
    }
}
