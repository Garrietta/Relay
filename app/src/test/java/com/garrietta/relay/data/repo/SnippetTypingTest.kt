package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for Garrett's 2026-10-20 inbox parity rules: GM snippet
 * prefixes ("You: " / "{FirstName}: ") and the "{FirstName} is
 * typing..." label resolution from the wire's typist number.
 */
class SnippetTypingTest {

    // ---- snippetPrefix ----

    @Test
    fun outgoingLatestGetsYouPrefix() {
        assertEquals("You: ", snippetPrefix(latestFromMe = true, isGroup = false, latestSenderName = ""))
    }

    @Test
    fun outgoingGroupLatestIsStillYou() {
        assertEquals("You: ", snippetPrefix(latestFromMe = true, isGroup = true, latestSenderName = "Trenton Meier"))
    }

    @Test
    fun groupIncomingGetsFirstNameOnly() {
        assertEquals("Trenton: ", snippetPrefix(latestFromMe = false, isGroup = true, latestSenderName = "Trenton Meier"))
        assertEquals("Mom: ", snippetPrefix(latestFromMe = false, isGroup = true, latestSenderName = "Mom"))
    }

    @Test
    fun groupIncomingWithoutNameGetsNoPrefix() {
        assertEquals("", snippetPrefix(latestFromMe = false, isGroup = true, latestSenderName = ""))
        assertEquals("", snippetPrefix(latestFromMe = false, isGroup = true, latestSenderName = "   "))
    }

    @Test
    fun oneToOneIncomingGetsNoPrefix() {
        assertEquals("", snippetPrefix(latestFromMe = false, isGroup = false, latestSenderName = "Haley Marietta"))
    }

    // ---- typingLabel ----

    private fun participant(name: String, number: String) =
        Participant(address = number, displayName = name, photoUri = null)

    @Test
    fun typingLabelResolvesTypistByNumber() {
        val others = listOf(
            participant("Dad", "+17655550100"),
            participant("Grace Ambrose", "+17655550101"),
        )
        // Same number, national format (no country code) — last-10 match.
        assertEquals("Grace is typing...", typingLabel(others, "7655550101"))
        assertEquals("Dad is typing...", typingLabel(others, "+1 (765) 555-0100"))
    }

    @Test
    fun typingLabelOneToOneFallsBackToThePeer() {
        val others = listOf(participant("Papaw Ambrose", "+17655550102"))
        assertEquals("Papaw is typing...", typingLabel(others, null))
        assertEquals("Papaw is typing...", typingLabel(others, ""))
    }

    @Test
    fun typingLabelUnresolvableGroupTypistIsGeneric() {
        val others = listOf(
            participant("Dad", "+17655550100"),
            participant("Grace Ambrose", "+17655550101"),
        )
        // A number that matches no participant: never a fabricated name.
        assertEquals("Someone is typing...", typingLabel(others, "+442071234567"))
        assertEquals("Someone is typing...", typingLabel(others, null))
    }

    @Test
    fun typingLabelSelfChatHasNoLabel() {
        assertNull(typingLabel(emptyList(), "+17655550100"))
        assertNull(typingLabel(emptyList(), null))
    }

    // ---- samePhoneNumber ----

    @Test
    fun samePhoneNumberToleratesFormatting() {
        assertTrue(samePhoneNumber("+1 (765) 555-0101", "7655550101"))
        assertTrue(samePhoneNumber("7655550101", "7655550101"))
        assertFalse(samePhoneNumber("7655550101", "7655550102"))
        assertFalse(samePhoneNumber("", "7655550101"))
    }
}
