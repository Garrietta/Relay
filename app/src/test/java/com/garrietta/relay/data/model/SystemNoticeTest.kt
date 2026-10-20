package com.garrietta.relay.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system-notice discriminators (Garrett 2026-10-20): group EVENT
 * sentences arrive as ordinary message rows with no wire type marker —
 * the pre-rendered sentence template is the only signal, so the
 * patterns are anchored full-string with the actor capped at two
 * tokens, and these tests pin both the matches and the near-misses.
 */
class SystemNoticeTest {

    @Test fun `group created sentences are event notices`() {
        // His device-verified string (Build #155 video, 2026-10-20).
        assertTrue(isGroupEventNotice("Dad created this group chat with you and Grace"))
        assertTrue(isGroupEventNotice("You created this group chat with Dad and Grace"))
        assertTrue(isGroupEventNotice("Grace Ambrose created this group chat with you and Dad"))
        assertTrue(isGroupEventNotice("Dad created this group chat"))
    }

    @Test fun `group left and renamed sentences are event notices`() {
        assertTrue(isGroupEventNotice("Grace Ambrose left the group"))
        assertTrue(isGroupEventNotice("Dad changed the group name to Weekend Plans"))
        assertTrue(isGroupEventNotice("You renamed the group to Cabin Trip"))
    }

    @Test fun `ordinary prose mentioning the phrases is not an event notice`() {
        // Narration ABOUT an event is a real message, not the event.
        assertFalse(isGroupEventNotice("He said Dad created this group chat with us"))
        assertFalse(isGroupEventNotice("Dad created this group chat with you and Grace!"))
        assertFalse(isGroupEventNotice("I left the group project doc in the car"))
        assertFalse(isGroupEventNotice("Grace left the group chat open on her laptop"))
        assertFalse(isGroupEventNotice("We renamed the group chat to Cabin Trip last week lol"))
        assertFalse(isGroupEventNotice("created this group chat with you and Grace"))
        assertFalse(isGroupEventNotice(""))
        assertFalse(isGroupEventNotice("What brand water heater"))
    }

    @Test fun `transport notices still classify as before`() {
        assertTrue(isTransportSystemNotice("RCS chat with Dad"))
        assertTrue(isTransportSystemNotice("Texting with +15550002222 (SMS/MMS)"))
        assertFalse(isTransportSystemNotice("Dad created this group chat with you and Grace"))
    }
}
