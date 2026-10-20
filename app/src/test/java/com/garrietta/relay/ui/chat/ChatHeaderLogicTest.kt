package com.garrietta.relay.ui.chat

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
}
