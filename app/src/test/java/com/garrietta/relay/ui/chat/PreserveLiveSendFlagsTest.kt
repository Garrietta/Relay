package com.garrietta.relay.ui.chat

import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [preserveLiveSendFlags] (Garrett 2026-10-20): the
 * carry that keeps a live send's provenance — and therefore its
 * spinner and its spinner→check morph — alive across the wholesale
 * list replacements (history loads, deep fill, reload fallbacks)
 * that rebuild every message from the wire with the flag unset.
 * Pure JVM. Run on CI via testDebugUnitTest.
 */
class PreserveLiveSendFlagsTest {

    private fun msg(
        id: Long,
        status: DeliveryStatus = DeliveryStatus.SENT,
        tmpId: String? = null,
        inFlight: Boolean = false,
    ) = Message(
        id = id,
        threadId = 1L,
        address = "",
        body = "x",
        date = id,
        isOutgoing = true,
        isMms = false,
        read = true,
        tmpId = tmpId,
        deliveryStatus = status,
        sendInFlight = inFlight,
    )

    @Test
    fun `flag carries across a wholesale reload by id`() {
        // Mid-send, a history reload republishes the same message
        // (same server id) rebuilt from the wire: flag unset there,
        // still outstanding here — the carry keeps the send live.
        val previous = listOf(msg(id = 42, inFlight = true))
        val incoming = listOf(msg(id = 42, inFlight = false))
        val out = preserveLiveSendFlags(previous, incoming)
        assertTrue(out.single().sendInFlight)
    }

    @Test
    fun `flag carries across the echo id swap by tmpId`() {
        // The optimistic bubble's id is a negative timestamp; the
        // echo's is the server id hash. tmpId is the bridge.
        val previous = listOf(msg(id = -99, tmpId = "t-1", inFlight = true))
        val incoming = listOf(msg(id = 42, tmpId = "t-1", inFlight = false))
        val out = preserveLiveSendFlags(previous, incoming)
        assertTrue(out.single().sendInFlight)
    }

    @Test
    fun `terminal incoming status clears the flag`() {
        // DELIVERED lands via a reload instead of the status patch:
        // the send is over; the flag must not outlive it.
        val previous = listOf(msg(id = 42, inFlight = true))
        val incoming = listOf(
            msg(id = 42, status = DeliveryStatus.DELIVERED, inFlight = true),
        )
        val out = preserveLiveSendFlags(previous, incoming)
        assertFalse(out.single().sendInFlight)
        assertEquals(DeliveryStatus.DELIVERED, out.single().deliveryStatus)
    }

    @Test
    fun `unflagged history passes through untouched`() {
        // The stranded subset's shape: a history list where nothing
        // was ever flagged must gain no flags — historical SENT
        // stays unprovenanced and renders its static check.
        val history = listOf(
            msg(id = 1, status = DeliveryStatus.SENT),
            msg(id = 2, status = DeliveryStatus.DELIVERED),
            msg(id = 3, status = DeliveryStatus.READ),
        )
        val out = preserveLiveSendFlags(emptyList(), history)
        assertEquals(history, out)
        assertTrue(out.none { it.sendInFlight })
    }

    @Test
    fun `a vanished message resurrects nothing`() {
        // A failed send removes its bubble; a later list without it
        // must not re-flag anything else.
        val previous = listOf(msg(id = -99, tmpId = "t-1", inFlight = true))
        val incoming = listOf(msg(id = 7, tmpId = "other"))
        val out = preserveLiveSendFlags(previous, incoming)
        assertFalse(out.single().sendInFlight)
    }

    @Test
    fun `already flagged non-terminal incoming keeps its flag`() {
        val previous = listOf(msg(id = 1))
        val incoming = listOf(msg(id = 42, inFlight = true))
        val out = preserveLiveSendFlags(previous, incoming)
        assertTrue(out.single().sendInFlight)
    }
}
