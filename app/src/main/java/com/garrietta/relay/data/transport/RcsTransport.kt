package com.garrietta.relay.data.transport

import android.content.Context
import android.util.Log
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.garrietta.relay.rcs.proto.RcsConversation
import com.garrietta.relay.rcs.session.RcsSession
import com.garrietta.relay.rcs.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * RCS transport — live implementation of the Messages-for-web protocol client.
 *
 * Pairing (QR) → session (long-poll + 5s ack loop) → send/receive. The phone's
 * Google Messages app remains the source of truth; this transport is a paired
 * web-style client. See com.garrietta.relay.rcs for the protocol machinery.
 *
 * Overnight status: the full protocol path is implemented up to "displays QR,
 * awaiting scan". The live connection cannot be verified until Garrett scans
 * the QR with Google Messages in the morning.
 */
class RcsTransport(context: Context) : Transport {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = RcsSession(appContext)

    override val id: String = "rcs"
    override val displayName: String = "RCS"

    private val _pairingState = MutableStateFlow(
        if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED,
    )

    /** Human-readable pairing state for the Settings screen. */
    val pairingState: Flow<RcsPairingState> = _pairingState.asStateFlow()

    override val isAvailable: Flow<Boolean> = _pairingState.map { it == RcsPairingState.PAIRED }

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override fun observeIncoming(): Flow<IncomingMessage> = _incoming.asSharedFlow()

    /** RCS-side conversation list for future inbox merging. */
    private val _rcsConversations = MutableStateFlow<List<RcsConversation>>(emptyList())
    val rcsConversations: Flow<List<RcsConversation>> = _rcsConversations.asStateFlow()

    init {
        if (session.isPaired) {
            _pairingState.value = RcsPairingState.PAIRED
            startSession()
        }
        scope.launch {
            session.events.collect { event ->
                when (event) {
                    is SessionEvent.AuthExpired, SessionEvent.Unpaired -> {
                        _pairingState.value = RcsPairingState.NOT_PAIRED
                        session.stop()
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            session.incoming.collect { msg ->
                _incoming.emit(
                    IncomingMessage(
                        transportId = id,
                        address = msg.conversationId,
                        body = msg.body,
                        timestamp = msg.timestampMs,
                        isMms = false,
                    ),
                )
            }
        }
    }

    private fun startSession() {
        if (session.start()) {
            scope.launch {
                runCatching { _rcsConversations.value = session.listConversations() }
                    .onFailure { Log.w(TAG, "listConversations failed: ${it.message}") }
            }
        }
    }

    override suspend fun sendMessage(recipient: String, body: String): SendResult {
        if (_pairingState.value != RcsPairingState.PAIRED) {
            return SendResult.Unavailable("RCS is not paired yet")
        }
        return try {
            val conversationId = session.conversationFor(recipient)
            session.sendText(conversationId, body)
            SendResult.Sent
        } catch (e: Exception) {
            Log.w(TAG, "RCS send failed", e)
            SendResult.Failed(e.message ?: "RCS send failed")
        }
    }

    // ---------- pairing (used by the pairing UI) ----------

    /** Begins QR pairing; returns the QR content + session handles. */
    suspend fun beginQrPairing(): QrPairing.BegunPairing {
        _pairingState.value = RcsPairingState.PAIRING
        try {
            return QrPairing().begin()
        } catch (e: Exception) {
            _pairingState.value = RcsPairingState.NOT_PAIRED
            throw e
        }
    }

    /**
     * Polls for the phone's pair event, persists on success, starts the
     * session. Returns true when paired.
     */
    suspend fun awaitQrPairing(begun: QrPairing.BegunPairing): Boolean {
        try {
            val paired = QrPairing().awaitPairEvent(begun)
            // Let the phone save the pair data before we reconnect (per spec).
            delay(2000)
            QrPairing().persist(com.garrietta.relay.rcs.session.AuthStore(appContext), begun, paired)
            _pairingState.value = RcsPairingState.PAIRED
            startSession()
            // Keep the long-poll alive under Doze (user is in the foreground here).
            com.garrietta.relay.rcs.service.RcsService.start(appContext)
            return true
        } catch (e: PairingException) {
            _pairingState.value = RcsPairingState.NOT_PAIRED
            throw e
        }
    }

    fun cancelPairing() {
        _pairingState.value =
            if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
    }

    fun unpair() {
        com.garrietta.relay.rcs.session.AuthStore(appContext).clear()
        session.stop()
        com.garrietta.relay.rcs.service.RcsService.stop(appContext)
        _pairingState.value = RcsPairingState.NOT_PAIRED
    }

    companion object {
        private const val TAG = "RcsTransport"
    }
}

enum class RcsPairingState {
    NOT_PAIRED,
    PAIRING,
    PAIRED,
}
