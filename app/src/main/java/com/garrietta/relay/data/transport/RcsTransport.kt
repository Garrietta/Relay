package com.garrietta.relay.data.transport

import android.content.Context
import android.content.Intent
import android.util.Log
import com.garrietta.relay.rcs.pairing.MicrogPairing
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.pairing.GaiaPairing
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.proto.RcsConversation
import com.garrietta.relay.rcs.proto.Rpc
import com.garrietta.relay.rcs.proto.encodeDevice
import com.garrietta.relay.rcs.session.AuthData
import com.garrietta.relay.rcs.session.AuthStore
import com.garrietta.relay.rcs.session.RcsSession
import com.garrietta.relay.rcs.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
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

    /**
     * The in-flight pairing coroutine, tracked so [cancelPairing] can abort
     * it promptly. Set by the pairing UI before calling [awaitQrPairing].
     */
    @Volatile
    private var pairingJob: Job? = null

    fun trackPairingJob(job: Job?) {
        pairingJob?.cancel()
        pairingJob = job
    }

    /** Begins QR pairing; returns the QR content + session handles. */
    suspend fun beginQrPairing(): QrPairing.BegunPairing {
        _pairingState.value = RcsPairingState.PAIRING
        try {
            return QrPairing().begin()
        } catch (e: Exception) {
            _pairingState.value = if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
            throw e
        }
    }

    /**
     * Polls for the phone's pair event, persists on success, starts the
     * session. Returns true when paired.
     *
     * The state machine can never strand in PAIRING: a [withTimeout] bounds
     * the whole attempt, and the finally block resets to the true session
     * state on cancellation, timeout, or failure.
     */
    suspend fun awaitQrPairing(begun: QrPairing.BegunPairing): Boolean {
        var done = false
        try {
            withTimeout(PAIRING_TIMEOUT_MS) {
                val paired = QrPairing().awaitPairEvent(begun)
                // Let the phone save the pair data before we reconnect (per spec).
                delay(2000)
                QrPairing().persist(com.garrietta.relay.rcs.session.AuthStore(appContext), begun, paired)
                done = true
            }
            _pairingState.value = RcsPairingState.PAIRED
            startSession()
            // Keep the long-poll alive under Doze (user is in the foreground here).
            com.garrietta.relay.rcs.service.RcsService.start(appContext)
            return true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "QR pairing timed out after ${PAIRING_TIMEOUT_MS}ms")
            throw PairingException("Pairing timed out — try again")
        } finally {
            pairingJob = null
            if (!done) {
                _pairingState.value =
                    if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
            }
        }
    }

    /**
     * Aborts any in-flight pairing and resets the state machine. Safe to
     * call when nothing is pairing. The pairing screen calls this on dispose
     * so backing out mid-pairing can never leave Settings stuck on
     * "Pairing...".
     */
    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _pairingState.value =
            if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
    }

    // ---------- microG (no-browser Google-account) pairing ----------

    /**
     * Pairs via the Google account already on the phone (microG): mints an
     * OAuthLogin token through microG's authenticator, exchanges it for web
     * session cookies, then runs the standard Gaia begin with those cookies.
     *
     * [launchConsent] is invoked with microG's consent Intent when the
     * authenticator demands it (first use); it must run on the main thread
     * and returns true when the user consented.
     *
     * Like [beginGaiaPairing]: sets PAIRING, resets to the true session
     * state on failure. No timeout here — the user drives the consent tap;
     * the pairing screen's dispose still aborts via [cancelPairing].
     */
    suspend fun beginMicrogGaiaPairing(
        accountName: String,
        launchConsent: suspend (Intent) -> Boolean,
    ): com.garrietta.relay.rcs.pairing.GaiaPairing.BegunGaia {
        _pairingState.value = RcsPairingState.PAIRING
        try {
            val cookies = MicrogPairing.fetchCookies(appContext, accountName, launchConsent)
            return beginGaiaPairing(cookies)
        } catch (e: Exception) {
            _pairingState.value = if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
            throw e
        }
    }

    // ---------- Gaia (Google-account / emoji) pairing ----------

    /**
     * Begins Gaia pairing with captured Google session cookies. Returns the
     * begun handshake; the UI then drives the UKEY2 emoji verification.
     */
    suspend fun beginGaiaPairing(cookies: String): com.garrietta.relay.rcs.pairing.GaiaPairing.BegunGaia {
        _pairingState.value = RcsPairingState.PAIRING
        try {
            return com.garrietta.relay.rcs.pairing.GaiaPairing().begin(cookies)
        } catch (e: Exception) {
            _pairingState.value = if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
            throw e
        }
    }

    /**
     * Result of the live UKEY2 handshake: the phone was reached, answered
     * with SERVER_INIT, and the emoji below is the real computed one — the
     * UI shows it for the user to match against the phone's prompt.
     */
    data class GaiaHandshakeResult(
        val begun: GaiaPairing.BegunGaia,
        val handshake: GaiaPairing.Ukey2Handshake,
        val serverPublicKey: java.security.PublicKey,
        val nextKey: ByteArray,
        val verificationCodeVersion: Int,
        val emoji: String,
    )

    /**
     * Runs the live UKEY2 handshake after a successful SignInGaia:
     *  1. Injects a provisional session (real tachyon token; request keys
     *     only arrive after CLIENT_FINISHED) — in memory only, so a failed
     *     handshake never clobbers a previous good pairing on disk.
     *  2. Brings up the Gaia-mode long-poll (cookie-authenticated) and waits
     *     for the channel, like the reference does before CLIENT_INIT.
     *  3. Sends CLIENT_INIT as an unencrypted GAIA_2 message addressed to the
     *     chosen phone, then awaits the correlated SERVER_INIT.
     *  4. Processes SERVER_INIT → real emoji via the reference tables.
     *
     * Throws PairingException on failure. The UI shows "Contacting your
     * phone…" while this is in flight.
     */
    suspend fun startGaiaHandshake(begun: GaiaPairing.BegunGaia): GaiaHandshakeResult {
        _pairingState.value = RcsPairingState.PAIRING
        try {
            val gaia = GaiaPairing()
            val mobile = DeviceInfo(
                userId = begun.mobileDevice.userId,
                sourceId = begun.mobileDevice.sourceId,
                network = begun.mobileDevice.network,
            )
            val ttlMs = (if (begun.tokenData.ttlSeconds > 0) begun.tokenData.ttlSeconds else 24 * 3600) * 1000L
            val provisional = AuthData(
                aesKey = ByteArray(32),
                hmacKey = ByteArray(32),
                refreshPrivateKey = byteArrayOf(),
                refreshPublicKey = begun.refreshPublicKey,
                tachyonToken = begun.tokenData.token,
                tachyonExpiryMs = System.currentTimeMillis() + ttlMs,
                sessionId = begun.sessionUuid,
                pairingId = "",
                mobileB64 = java.util.Base64.getEncoder().encodeToString(encodeDevice(mobile)),
                browserB64 = "",
            )
            session.injectAuth(provisional)

            val handshake = gaia.prepareUkey2()
            val container = GaiaPairing.buildClientInitContainer(handshake, RelayApi.WEBVIEW_UA)
            Log.d(TAG, "GaiaPairing CLIENT_INIT container=${container.size}B -> phone ${begun.chosenDevice.deviceId}")
            val responseBytes = session.sendGaia(
                action = Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_INIT,
                container = container,
                messageType = Rpc.MSG_TYPE_GAIA_2,
                destRegIds = listOf(begun.chosenDevice.deviceId),
                cookies = begun.cookies,
            )
            val result = gaia.processServerInit(handshake, responseBytes)
            return GaiaHandshakeResult(
                begun = begun,
                handshake = handshake,
                serverPublicKey = result.serverPublicKey,
                nextKey = result.nextKey,
                verificationCodeVersion = result.verificationCodeVersion,
                emoji = result.emoji,
            )
        } catch (e: Exception) {
            session.injectAuth(null)
            session.stop()
            _pairingState.value =
                if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
            throw e
        }
    }

    /**
     * Completes Gaia pairing after the user confirms the emoji match:
     * sends CLIENT_FINISHED as an unencrypted BUGLE_MESSAGE (with the
     * private-API confirmation string), handles the phone's finish
     * error codes, runs the Ditto v1 key derivation, persists the final
     * AuthData + cookies, and keeps the Gaia-mode session running.
     */
    suspend fun finishGaiaPairing(h: GaiaHandshakeResult): Boolean {
        var done = false
        try {
            withTimeout(PAIRING_TIMEOUT_MS) {
                val begun = h.begun
                val container = GaiaPairing.buildFinishContainer(h.handshake, RelayApi.WEBVIEW_UA)
                val responseBytes = session.sendGaia(
                    action = Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_FINISHED,
                    container = container,
                    messageType = Rpc.MSG_TYPE_BUGLE,
                    destRegIds = listOf(begun.chosenDevice.deviceId),
                    cookies = begun.cookies,
                )
                val finish = GaiaPairing.parseFinishResponse(responseBytes)
                if (finish.finishErrorType != 0L) {
                    throw PairingException(finishErrorMessage(finish))
                }
                val (aesKey, hmacKey) = GaiaPairing.deriveDittoV1Keys(h.nextKey)
                val ttlMs = (if (begun.tokenData.ttlSeconds > 0) begun.tokenData.ttlSeconds else 24 * 3600) * 1000L
                val finalAuth = AuthData(
                    aesKey = aesKey,
                    hmacKey = hmacKey,
                    refreshPrivateKey = byteArrayOf(),
                    refreshPublicKey = begun.refreshPublicKey,
                    tachyonToken = begun.tokenData.token,
                    tachyonExpiryMs = System.currentTimeMillis() + ttlMs,
                    sessionId = begun.sessionUuid,
                    pairingId = h.handshake.pairingAttemptId,
                    mobileB64 = java.util.Base64.getEncoder()
                        .encodeToString(encodeDevice(begun.mobileDevice)),
                    browserB64 = "",
                )
                val store = AuthStore(appContext)
                store.save(finalAuth)
                store.saveCookies(begun.cookies)
                session.injectAuth(finalAuth)
                done = true
            }
            _pairingState.value = RcsPairingState.PAIRED
            // The session is already up in Gaia mode; refresh the conversation
            // list and start the foreground service.
            scope.launch {
                runCatching { _rcsConversations.value = session.listConversations() }
            }
            com.garrietta.relay.rcs.service.RcsService.start(appContext)
            return true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gaia pairing finish timed out after ${PAIRING_TIMEOUT_MS}ms")
            throw PairingException("Pairing timed out — try again")
        } finally {
            pairingJob = null
            if (!done) {
                session.injectAuth(null)
                session.stop()
                _pairingState.value =
                    if (session.isPaired) RcsPairingState.PAIRED else RcsPairingState.NOT_PAIRED
            }
        }
    }

    /** Maps the phone's finish error codes to user-facing messages. */
    private fun finishErrorMessage(f: GaiaPairing.GaiaFinishResult): String = when (f.finishErrorCode) {
        5L -> "Wrong emoji — it didn't match the one on your phone. Try again."
        7L -> "Pairing was cancelled on your phone."
        2L, 3L, 6L -> "Pairing timed out — try again."
        else -> "Pairing failed (phone error ${f.finishErrorType}/${f.finishErrorCode})"
    }

    /** Saved Google session cookies, if a Gaia sign-in was captured. */
    fun loadGaiaCookies(): String? =
        com.garrietta.relay.rcs.session.AuthStore(appContext).loadCookies()

    fun unpair() {
        com.garrietta.relay.rcs.session.AuthStore(appContext).clear()
        session.stop()
        com.garrietta.relay.rcs.service.RcsService.stop(appContext)
        _pairingState.value = RcsPairingState.NOT_PAIRED
    }

    companion object {
        private const val TAG = "RcsTransport"
        /** Whole pairing attempt bound: 5 minutes, then back to idle. */
        private const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

enum class RcsPairingState {
    NOT_PAIRED,
    PAIRING,
    PAIRED,
}

