package com.garrietta.relay.data.repo

import com.garrietta.relay.ui.chat.ChatHeaderLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the resume-time contact-refresh decision (Garrett
 * 2026-10-20): a return from the background invalidates the contact
 * lookup ONLY when it actually holds cached resolutions — an empty
 * lookup means the resume is a no-op and no open chat pays for a
 * re-resolution pass. The second half pins the chain the feature
 * exists for, at the pure-logic level: before the refresh, the
 * header's peer name is the bare number and "Add contact?" shows;
 * after it, the input the header feeds the same rule is the
 * device-contact-resolved name, and the button is gone. Pure JVM.
 * Run on CI via testDebugUnitTest.
 */
class ContactRefreshLogicTest {

    @Test
    fun `empty lookup makes the resume a no-op`() {
        assertFalse(ContactRefreshLogic.shouldInvalidate(cachedEntryCount = 0))
    }

    @Test
    fun `any cached resolution makes the resume invalidate`() {
        assertTrue(ContactRefreshLogic.shouldInvalidate(cachedEntryCount = 1))
        assertTrue(ContactRefreshLogic.shouldInvalidate(cachedEntryCount = 2))
        assertTrue(ContactRefreshLogic.shouldInvalidate(cachedEntryCount = 500))
    }

    @Test
    fun `nonsense counts never invalidate`() {
        // Defensive: a count can only be a map size, but the rule
        // must not fire on a negative value if one ever arrived.
        assertFalse(ContactRefreshLogic.shouldInvalidate(cachedEntryCount = -1))
    }

    @Test
    fun `after the refresh resolves the contact, the add-contact button hides`() {
        val address = "+15551234567"
        // Before: no device contact — the header feeds the rule the
        // bare-number title (the server/participant name IS the
        // number), and the button offers the save with the number
        // prefilled.
        val before = ChatHeaderLogic.addContactTarget(
            isGroup = false,
            isSelf = false,
            peerName = "+1 555-123-4567",
            peerAddress = address,
        )
        assertEquals(ChatHeaderLogic.AddContactTarget.Phone(address), before)
        // After the save + resume refresh: the header feeds the rule
        // the device-contact-resolved name in front of the same
        // identity — a resolved name is never a bare number, so the
        // peer is known and the button is gone.
        val after = ChatHeaderLogic.addContactTarget(
            isGroup = false,
            isSelf = false,
            peerName = "Sam Carter",
            peerAddress = address,
        )
        assertNull(after)
    }

    @Test
    fun `email peer hides the button once the refresh resolves a name`() {
        val address = "friend@icloud.com"
        val before = ChatHeaderLogic.addContactTarget(
            isGroup = false,
            isSelf = false,
            peerName = address,
            peerAddress = address,
        )
        assertEquals(ChatHeaderLogic.AddContactTarget.Email(address), before)
        val after = ChatHeaderLogic.addContactTarget(
            isGroup = false,
            isSelf = false,
            peerName = "Sam Carter",
            peerAddress = address,
        )
        assertNull(after)
    }
}
