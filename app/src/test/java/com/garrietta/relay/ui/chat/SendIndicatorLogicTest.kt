package com.garrietta.relay.ui.chat

import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.rcsStatusToDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-message send indicator mapping (Garrett
 * 2026-10-20): the seven-shape spinner beside an outgoing bubble
 * while its send is in flight, morphing into a check mark on
 * DELIVERED (his word — completed means delivered). Pure JVM.
 * Run on CI via testDebugUnitTest.
 *
 * The spinner is licensed by live-send PROVENANCE
 * (Message.sendInFlight — set only by this session's send pipeline),
 * never by the status value alone: a status that arrives from a
 * history load describes the past, and a past send is not happening
 * right now.
 */
class SendIndicatorLogicTest {

    private fun state(
        isOutgoing: Boolean = true,
        status: DeliveryStatus = DeliveryStatus.SENDING,
        uploadInProgress: Boolean = false,
        sendInFlight: Boolean = true,
    ): SendIndicatorState = SendIndicatorLogic.stateFor(
        isOutgoing, status, uploadInProgress, sendInFlight,
    )

    @Test
    fun `incoming messages never show an indicator`() {
        for (status in DeliveryStatus.entries) {
            assertEquals(
                SendIndicatorState.HIDDEN,
                state(isOutgoing = false, status = status),
            )
        }
    }

    @Test
    fun `upload phase belongs to the bubble scrim, not the indicator`() {
        // Media sends: while uploadProgress is live the indicator
        // stays hidden whatever the delivery status says — the
        // indicator owns only the post-upload delivery phase.
        assertEquals(
            SendIndicatorState.HIDDEN,
            state(status = DeliveryStatus.SENDING, uploadInProgress = true),
        )
        assertEquals(
            SendIndicatorState.HIDDEN,
            state(status = DeliveryStatus.DELIVERED, uploadInProgress = true),
        )
    }

    @Test
    fun `no status shows nothing`() {
        assertEquals(SendIndicatorState.HIDDEN, state(status = DeliveryStatus.NONE))
    }

    @Test
    fun `live sending spins`() {
        assertEquals(
            SendIndicatorState.SPINNER,
            state(status = DeliveryStatus.SENDING, sendInFlight = true),
        )
    }

    @Test
    fun `live sent keeps spinning - delivered is the completion`() {
        // RCS, this session's send: at SENT the echo has landed but
        // the receipts are still flowing; the spinner must not
        // resolve to the check until DELIVERED lands.
        assertEquals(
            SendIndicatorState.SPINNER,
            state(status = DeliveryStatus.SENT, sendInFlight = true),
        )
    }

    @Test
    fun `stranded subset - history sent with no delivered code in its payload shows the check`() {
        // THE BUG (Garrett 2026-10-20): some long-delivered outgoing
        // messages sat on the spinner forever — e.g. his photo in the
        // Kevin/Bram/Charlie group, sent from another client days
        // earlier. Their history payload carries no delivered code:
        // the wire status is absent/other, the history mapping's
        // catch-all derives SENT, and every history message is
        // server-tracked — so the old rule (SENT + tracked = spinner)
        // spun them permanently: status pushes only patch the open
        // chat, and nothing ever re-asks history. Provenance is the
        // fix: no live send in this session, no spinner — the message
        // resolves to its static marker.
        for (wireCode in listOf(0, 1, 3, 15)) {
            val derived = rcsStatusToDelivery(wireCode, outgoing = true)
            assertEquals(DeliveryStatus.SENT, derived)
            assertEquals(
                SendIndicatorState.CHECK,
                state(status = derived, sendInFlight = false),
            )
        }
    }

    @Test
    fun `historical sending state without provenance resolves to the check`() {
        // Defensive: SENDING is only ever assigned by the live send
        // pipeline (always with provenance). If one ever arrives from
        // a load path, it must not claim a send is happening now.
        assertEquals(
            SendIndicatorState.CHECK,
            state(status = DeliveryStatus.SENDING, sendInFlight = false),
        )
    }

    @Test
    fun `telephony sent is terminal and reads as the check`() {
        // Telephony sends carry no live provenance and no delivery
        // receipts ever arrive — SENT is their final state, matching
        // the old single tick.
        assertEquals(
            SendIndicatorState.CHECK,
            state(status = DeliveryStatus.SENT, sendInFlight = false),
        )
    }

    @Test
    fun `delivered is the check and read is the read marker`() {
        assertEquals(SendIndicatorState.CHECK, state(status = DeliveryStatus.DELIVERED))
        assertEquals(SendIndicatorState.READ, state(status = DeliveryStatus.READ))
        // Terminal states read the same whether or not the message
        // was this session's send.
        assertEquals(
            SendIndicatorState.CHECK,
            state(status = DeliveryStatus.DELIVERED, sendInFlight = false),
        )
        assertEquals(
            SendIndicatorState.READ,
            state(status = DeliveryStatus.READ, sendInFlight = false),
        )
    }

    @Test
    fun `morph fires only on the live spin-to-complete transition`() {
        assertTrue(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.SPINNER, SendIndicatorState.CHECK,
            ),
        )
        assertTrue(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.SPINNER, SendIndicatorState.READ,
            ),
        )
        // History-loaded delivered: first sight is already CHECK —
        // the composable's seen-state starts there, so no morph.
        assertFalse(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.CHECK, SendIndicatorState.CHECK,
            ),
        )
        assertFalse(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.HIDDEN, SendIndicatorState.CHECK,
            ),
        )
        // Ordinary status marches are not morphs.
        assertFalse(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.SPINNER, SendIndicatorState.SPINNER,
            ),
        )
        assertFalse(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.CHECK, SendIndicatorState.READ,
            ),
        )
        assertFalse(
            SendIndicatorLogic.morphsToCheck(
                SendIndicatorState.SPINNER, SendIndicatorState.HIDDEN,
            ),
        )
    }
}
