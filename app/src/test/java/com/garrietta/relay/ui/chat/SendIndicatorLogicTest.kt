package com.garrietta.relay.ui.chat

import com.garrietta.relay.data.model.DeliveryStatus
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
 */
class SendIndicatorLogicTest {

    private fun state(
        isOutgoing: Boolean = true,
        status: DeliveryStatus = DeliveryStatus.SENDING,
        uploadInProgress: Boolean = false,
        serverTracked: Boolean = true,
    ): SendIndicatorState = SendIndicatorLogic.stateFor(
        isOutgoing, status, uploadInProgress, serverTracked,
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
    fun `sending spins`() {
        assertEquals(SendIndicatorState.SPINNER, state(status = DeliveryStatus.SENDING))
    }

    @Test
    fun `server-tracked sent keeps spinning - delivered is the completion`() {
        // RCS: at SENT the receipts are still flowing; the spinner
        // must not resolve to the check until DELIVERED lands.
        assertEquals(
            SendIndicatorState.SPINNER,
            state(status = DeliveryStatus.SENT, serverTracked = true),
        )
    }

    @Test
    fun `untracked sent is terminal and reads as the check`() {
        // Telephony sends carry no tmpId/serverId and no delivery
        // receipts ever arrive — SENT is their final state, matching
        // the old single tick.
        assertEquals(
            SendIndicatorState.CHECK,
            state(status = DeliveryStatus.SENT, serverTracked = false),
        )
    }

    @Test
    fun `delivered is the check and read is the read marker`() {
        assertEquals(SendIndicatorState.CHECK, state(status = DeliveryStatus.DELIVERED))
        assertEquals(SendIndicatorState.READ, state(status = DeliveryStatus.READ))
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
