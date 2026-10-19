package com.garrietta.relay.data.transport

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.garrietta.relay.rcs.pairing.MicrogPairing
import com.garrietta.relay.rcs.pairing.PairingException
import com.garrietta.relay.rcs.pairing.QrPairing
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.pairing.GaiaPairing
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.proto.RcsConversation
import com.garrietta.relay.rcs.proto.RcsMessage
import com.garrietta.relay.rcs.proto.AuthMessage
import com.garrietta.relay.rcs.proto.RcsMediaAttachment
import com.garrietta.relay.rcs.proto.RcsParticipant
import com.garrietta.relay.rcs.proto.Rpc
import com.garrietta.relay.rcs.proto.encodeDevice
import com.garrietta.relay.rcs.proto.encodeDownloadAttachmentRequest
import com.garrietta.relay.rcs.media.aesGcmDecryptChunked
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
import kotlinx.coroutines.withContext

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

    /** Synchronous paired check (cold-start wiring, repository branching). */
    val isPairedNow: Boolean get() = _pairingState.value == RcsPairingState.PAIRED

    override val isAvailable: Flow<Boolean> = _pairingState.map { it == RcsPairingState.PAIRED }

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override fun observeIncoming(): Flow<IncomingMessage> = _incoming.asSharedFlow()

    /** RCS-side conversation list for future inbox merging. */
    private val _rcsConversations = MutableStateFlow<List<RcsConversation>>(emptyList())
    val rcsConversations: Flow<List<RcsConversation>> = _rcsConversations.asStateFlow()

    /**
     * Verbatim sync/send errors for the UI — Garrett has no logcat access,
     * so every sync/send failure surfaces here exactly as the server/client
     * reported it (same diagnosability standard as the pairing rounds).
     */
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: Flow<String?> = _syncError.asStateFlow()
    fun clearSyncError() { _syncError.value = null }

    /** Last-good server conversation list — the paired inbox's source. */
    fun currentRcsConversations(): List<RcsConversation> = _rcsConversations.value

    /**
     * Live transport state for Settings → Diagnostics: session running?
     * long-poll alive? last RPC (action + age)? last response (age,
     * correlated?)? last poll error? — so a sync failure on Garrett's
     * device can be diagnosed without logcat.
     */
    fun transportDiagnostics(): String {
        val pairing = _pairingState.value
        return "pairing=$pairing, ${session.diagnostics()}"
    }

    /** Re-pulls LIST_CONVERSATIONS; failures go to [syncError], verbatim. */
    suspend fun refreshConversations() {
        try {
            _rcsConversations.value = session.listConversations()
        } catch (e: Exception) {
            val msg = "Conversations sync failed: ${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, msg, e)
            _syncError.value = msg
            return
        }
        // Deeper history in the background (Garrett 2026-10-19: "way more
        // messages here"). The first pull stays fast (50); a larger pull
        // then fills in older conversations without blocking the UI.
        // Merged by id, newest first. Best-effort: failures only log.
        try {
            val deep = session.listConversations(count = 400L)
            if (deep.isNotEmpty()) {
                _rcsConversations.value =
                    (deep + _rcsConversations.value).distinctBy { it.id }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deep conversation fill failed: ${e.message}", e)
        }
    }

    /** Resolves a number to a server conversation, creating it if needed. */
    suspend fun conversationForNumber(number: String): String =
        session.conversationFor(number)

    /** Server message history for one conversation (direction via [participants]). */
    suspend fun rcsMessages(
        conversationId: String,
        participants: List<RcsParticipant> = emptyList(),
        count: Long = 50L,
    ): List<RcsMessage> = session.listMessages(conversationId, count, participants)

    /** Live incoming RCS messages (server echoes + new arrivals). */
    fun observeRcsMessages(): Flow<RcsMessage> = session.incoming

    /**
     * Sends text via the RCS session. Returns the tmpId for echo
     * correlation; throws RcsException with the verbatim failure reason.
     */
    suspend fun sendRcsText(conversationId: String, text: String, tmpId: String): String =
        session.sendText(conversationId, text, tmpId)

    suspend fun markRcsRead(conversationId: String, messageId: String) {
        runCatching { session.markRead(conversationId, messageId) }
    }

    /**
     * Tachyon token watchdog (TTL ~24h): refreshes when <1h remains by
     * re-issuing SignInGaia with the stored session id + refresh public key
     * — no re-pair, the pairing itself is untouched. Returns false (with a
     * verbatim [syncError]) when refresh isn't possible.
     */
    suspend fun maybeRefreshToken(): Boolean {
        val store = AuthStore(appContext)
        val a = store.load() ?: return false
        if (System.currentTimeMillis() + 3_600_000 < a.tachyonExpiryMs) return true
        return refreshTokenNow()
    }

    suspend fun refreshTokenNow(): Boolean {
        val store = AuthStore(appContext)
        val a = store.load()
        val cookies = store.loadCookies()
        if (a == null || cookies.isNullOrBlank()) {
            _syncError.value = "RCS session expired and no saved sign-in — please pair again."
            return false
        }
        return try {
            val td = GaiaPairing().refreshToken(cookies, a.sessionId, a.refreshPublicKey)
            val ttlMs = (if (td.ttlSeconds > 0) td.ttlSeconds else 24 * 3600) * 1000L
            val updated = a.copy(
                tachyonToken = td.token,
                tachyonExpiryMs = System.currentTimeMillis() + ttlMs,
            )
            store.save(updated)
            session.injectAuth(updated)
            Log.d(TAG, "RcsTransport: Tachyon token refreshed, new TTL ${ttlMs / 3_600_000}h")
            true
        } catch (e: Exception) {
            val msg = "RCS token refresh failed: ${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, msg, e)
            _syncError.value = msg
            false
        }
    }

    init {
        if (session.isPaired) {
            _pairingState.value = RcsPairingState.PAIRED
            startSession()
        }
        scope.launch {
            session.events.collect { event ->
                when (event) {
                    is SessionEvent.AuthExpired, SessionEvent.Unpaired -> {
                        // One recovery attempt before declaring the pairing
                        // dead: refresh the Tachyon token and restart the
                        // session. Only if that fails do we drop to NOT_PAIRED.
                        val recovered = try { refreshTokenNow() } catch (_: Exception) { false }
                        if (recovered) {
                            session.stop()
                            if (session.start()) {
                                refreshConversations()
                            }
                        } else {
                            _pairingState.value = RcsPairingState.NOT_PAIRED
                            session.stop()
                            _syncError.value = "RCS signed out — please pair again."
                        }
                    }
                    else -> Unit
                }
            }
        }
        // Token watchdog: the Tachyon TTL is ~24h; check every 15 minutes.
        scope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                if (_pairingState.value == RcsPairingState.PAIRED) {
                    runCatching { maybeRefreshToken() }
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
            scope.launch { refreshConversations() }
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
        /** Hex-encoded derivation inputs for diagnosing emoji mismatches. */
        val debugHex: Map<String, String>? = null,
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
                debugHex = result.debugHex,
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
     * Progress of the CLIENT_FINISHED step, surfaced to the pairing UI.
     */
    sealed interface GaiaFinishProgress {
        /** A CLIENT_FINISHED attempt was sent (1-based attempt number). */
        data class Sending(val attempt: Int) : GaiaFinishProgress
        /** The server hasn't confirmed yet — still waiting on the phone tap. */
        data object WaitingForPhone : GaiaFinishProgress
    }

    /**
     * Completes Gaia pairing: CLIENT_FINISHED goes out IMMEDIATELY (sending
     * it is what notifies Google Messages / triggers the phone's emoji
     * prompt) and this blocks on the correlated FINISHED response. Relay
     * shows the emoji and does NOTHING else — no verification action in
     * Relay; authorization happens ON THE PHONE (the emoji tap in Google
     * Messages).
     *
     * Sends CLIENT_FINISHED as an unencrypted BUGLE_MESSAGE (with the
     * private-API confirmation string). There is no separate
     * phone-confirmation event on the long-poll — the correlated FINISHED
     * response IS the signal (per the reference sendGaiaPairingMessage, which
     * blocks on it): the server answers once the phone's tap reaches it. If
     * the tap hasn't landed yet the server holds or rejects, so this sends
     * up to [FINISH_MAX_ATTEMPTS] sequential attempts (one in flight at a
     * time — never spammed) under a [FINISH_TIMEOUT_MS] total cap, reporting
     * [GaiaFinishProgress] — it never hangs forever.
     *
     * On success runs the Ditto v1 key derivation, persists the final
     * AuthData + cookies, and keeps the Gaia-mode session running. If our
     * side already persisted a pairing (e.g. a retry after the phone
     * confirmed), this completes gracefully instead of erroring.
     */
    suspend fun finishGaiaPairing(
        h: GaiaHandshakeResult,
        onProgress: suspend (GaiaFinishProgress) -> Unit = {},
    ): Boolean {
        // Already paired locally (e.g. retry after a successful finish) —
        // complete gracefully.
        if (session.isPaired) {
            Log.d(TAG, "GaiaPairing finish: already paired locally, completing gracefully")
            _pairingState.value = RcsPairingState.PAIRED
            return true
        }
        var done = false
        try {
            val begun = h.begun
            val deadlineMs = System.currentTimeMillis() + FINISH_TIMEOUT_MS
            var attempt = 0
            var backoffMs = 2000L
            var lastError: Exception? = null
            while (attempt < FINISH_MAX_ATTEMPTS && System.currentTimeMillis() < deadlineMs) {
                attempt++
                onProgress(GaiaFinishProgress.Sending(attempt))
                try {
                    val container =
                        GaiaPairing.buildFinishContainer(h.handshake, RelayApi.WEBVIEW_UA)
                    Log.d(TAG, "GaiaPairing CLIENT_FINISHED attempt=$attempt/$FINISH_MAX_ATTEMPTS " +
                            "container=${container.size}B phone=${begun.chosenDevice.deviceId}")
                    val responseBytes = session.sendGaia(
                        action = Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_FINISHED,
                        container = container,
                        messageType = Rpc.MSG_TYPE_BUGLE,
                        destRegIds = listOf(begun.chosenDevice.deviceId),
                        cookies = begun.cookies,
                        // Never overshoot the total budget: clamp the
                        // per-attempt wait to the remaining time.
                        timeoutMs = minOf(
                            FINISH_ATTEMPT_TIMEOUT_MS,
                            (deadlineMs - System.currentTimeMillis()).coerceAtLeast(1L),
                        ),
                    )
                    Log.d(TAG, "GaiaPairing CLIENT_FINISHED response=${responseBytes.size}B " +
                            "attempt=$attempt")
                    val finish = GaiaPairing.parseFinishResponse(responseBytes)
                    if (finish.finishErrorType == 0L) {
                        persistFinishedPairing(h)
                        done = true
                        break
                    }
                    // The phone reported an error — retry only the
                    // timeout-ish ones (the tap may not have landed yet).
                    when (finish.finishErrorCode) {
                        5L -> throw PairingException(
                            "Wrong emoji — it didn't match the one on your phone. Try again.")
                        7L -> throw PairingException("Pairing was cancelled on your phone.")
                        2L, 3L, 6L -> {
                            Log.w(TAG, "GaiaPairing finish rejected (phone error " +
                                    "${finish.finishErrorType}/${finish.finishErrorCode}); " +
                                    "waiting for phone confirmation")
                            onProgress(GaiaFinishProgress.WaitingForPhone)
                            lastError = PairingException(
                                "Your phone hasn't confirmed yet — tap the emoji in " +
                                    "Google Messages, then try again.")
                        }
                        else -> throw PairingException(
                            "Pairing failed (phone error ${finish.finishErrorType}/" +
                                "${finish.finishErrorCode})")
                    }
                } catch (e: PairingException) {
                    throw e // user-actionable: never retry
                } catch (e: TimeoutCancellationException) {
                    // No correlated response in time — the server is likely
                    // holding FINISHED until the phone tap lands.
                    Log.w(TAG, "GaiaPairing CLIENT_FINISHED attempt=$attempt response timeout; " +
                            "waiting for phone confirmation")
                    onProgress(GaiaFinishProgress.WaitingForPhone)
                    lastError = e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "GaiaPairing CLIENT_FINISHED attempt=$attempt failed: ${e.message}")
                    lastError = e
                }
                if (attempt < FINISH_MAX_ATTEMPTS) {
                    val remaining = deadlineMs - System.currentTimeMillis()
                    if (remaining <= 0) break
                    delay(minOf(backoffMs, remaining))
                    backoffMs *= 2
                }
            }
            if (!done) {
                throw lastError ?: PairingException("Pairing timed out — try again")
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
            Log.w(TAG, "Gaia pairing finish timed out")
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

    /** Derives the Ditto v1 keys, persists AuthData + cookies, injects the session. */
    private fun persistFinishedPairing(h: GaiaHandshakeResult) {
        val begun = h.begun
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
    }

    /** Maps the phone's finish error codes to user-facing messages. */
    private fun finishErrorMessage(f: GaiaPairing.GaiaFinishResult): String = when (f.finishErrorCode) {
        5L -> "Wrong emoji — it didn't match the one on your phone. Try again."
        7L -> "Pairing was cancelled on your phone."
        2L, 3L, 6L -> "Pairing timed out — try again."
        else -> "Pairing failed (phone error ${f.finishErrorType}/${f.finishErrorCode})"
    }

    // (image magic validation lives in top-level isValidImageBytes below)

    /**
     * Downloads and AES-GCM-decrypts an RCS media attachment, cached on disk
     * by mediaID. Throws with a verbatim reason when unavailable — the UI
     * surfaces it instead of rendering an empty bubble.
     *
     * Clean-room from the protocol's media flow: DownloadAttachmentRequest
     * (base64) in the x-goog-download-metadata header of GET .../upload,
     * then AES-GCM chunked stream decrypt with the message's decryption key.
     *
     * NOTE: MediaContent.mediaData (field 7) is only a low-res THUMBNAIL —
     * returning it raw renders as blocky garbage (diagnosed 2026-10-19).
     * When a mediaId exists we ALWAYS download the authoritative bytes;
     * the inline thumbnail is used only when there is nothing to download.
     *
     * Corruption guards (2026-10-19): the cache is versioned (v3) so stale
     * bytes from earlier builds are never served; every byte payload —
     * cached, downloaded, or inline — must pass the image magic check when
     * the attachment claims to be an image; downloaded payloads must FULLY
     * DECODE (not just pass the magic sniff — a valid header over garbage
     * scan data renders as gray blocks, which is what Garrett filmed);
     * and a decrypted payload that doesn't essentially match the
     * server-declared size is rejected (AES-GCM chunked decrypt yields the
     * exact plaintext, so a real mismatch means wrong bytes). A
     * corrupt-looking image must NEVER render.
     */
    suspend fun downloadMedia(att: RcsMediaAttachment): ByteArray =
        withContext(Dispatchers.IO) {
            if (att.mediaId.isEmpty()) {
                // No server copy — the inline bytes are the only bytes.
                // They may be a small full image or just a thumbnail; either
                // way the UI renders them at natural size, never upscaled.
                val inline = att.inlineData
                    ?: throw IllegalStateException("media unavailable: no attachment id or data")
                if (att.isImage && !isValidImageBytes(inline)) {
                    throw IllegalStateException("media unavailable: inline bytes are not a valid image")
                }
                if (att.isImage && !decodesAsImage(inline)) {
                    throw IllegalStateException("media unavailable: inline bytes do not decode as an image")
                }
                return@withContext inline
            }
            require(att.decryptionKey.size == 32) { "media unavailable: no decryption key" }
            val cacheDir = java.io.File(appContext.cacheDir, "rcs_media/v3")
            val cacheFile = java.io.File(cacheDir, att.mediaId.sanitizeFileName())
            if (cacheFile.exists()) {
                val cached = cacheFile.readBytes()
                if (att.isImage && !isValidImageBytes(cached)) {
                    cacheFile.delete()
                } else {
                    return@withContext cached
                }
            }
            val token = session.currentAuth()?.tachyonToken
                ?: throw IllegalStateException("media unavailable: not paired")
            val meta = java.util.Base64.getEncoder().encodeToString(
                encodeDownloadAttachmentRequest(
                    AuthMessage(
                        requestId = java.util.UUID.randomUUID().toString(),
                        network = "GDitto",
                        tachyonToken = token,
                    ),
                    att.mediaId,
                ),
            )
            val req = okhttp3.Request.Builder()
                .url(UPLOAD_URL)
                .get()
                .header("x-goog-download-metadata", meta)
                .header("User-Agent", MEDIA_UA)
                .header("accept", "*/*")
                .header("origin", "https://messages.google.com")
                .header("referer", "https://messages.google.com/")
                .header("sec-fetch-site", "cross-site")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-dest", "empty")
                .build()
            val encrypted = RelayApi.client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("media unavailable: download HTTP ${resp.code}")
                }
                resp.body!!.bytes()
            }
            val plain = aesGcmDecryptChunked(att.decryptionKey, encrypted)
            // Sanity: the server-declared size is the full plaintext media
            // (AES-GCM chunked decrypt yields the EXACT plaintext — the only
            // overhead stripped is per-chunk tags). A real mismatch means we
            // got the wrong bytes; the old 50% tolerance let corrupt payloads
            // through. 5% headroom covers declared-size rounding.
            if (att.size > 0 && plain.size < (att.size * 0.95).toLong()) {
                throw IllegalStateException(
                    "media unavailable: server returned ${plain.size} bytes, expected ~${att.size}",
                )
            }
            if (att.isImage && !isValidImageBytes(plain)) {
                throw IllegalStateException("media unavailable: downloaded bytes are not a valid image")
            }
            // Full-decode gate: magic bytes only sniff the header. A valid
            // header over garbage scan data passes the sniff and renders as
            // gray blocks — decode the whole stream (downsampled: we only
            // need the verdict, not the pixels) and reject what won't decode.
            if (att.isImage && !decodesAsImage(plain)) {
                throw IllegalStateException("media unavailable: downloaded bytes do not decode as an image")
            }
            cacheDir.mkdirs()
            cacheFile.writeBytes(plain)
            plain
        }

    /** Strips path-unsafe characters from a cache file name. */
    private fun String.sanitizeFileName(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(128).ifBlank { "media" }

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
        private const val UPLOAD_URL = "https://instantmessaging-pa.googleapis.com/upload"
        /** Browser UA for the media download, mirroring the web client. */
        private const val MEDIA_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /** Whole pairing attempt bound: 5 minutes, then back to idle. */
        private const val PAIRING_TIMEOUT_MS = 5 * 60 * 1000L
        /**
         * CLIENT_FINISHED: up to 3 sequential attempts (one in flight at a
         * time), 45s per-attempt response wait, 120s total cap — Garrett
         * needs time to see the emoji, switch apps, and tap on the phone.
         */
        private const val FINISH_MAX_ATTEMPTS = 3
        private const val FINISH_ATTEMPT_TIMEOUT_MS = 45_000L
        private const val FINISH_TIMEOUT_MS = 120_000L
    }
}

enum class RcsPairingState {
    NOT_PAIRED,
    PAIRING,
    PAIRED,
}


/**
 * True when [bytes] start with a known image magic number (JPEG, PNG, GIF,
 * WebP, HEIC/HEIF, BMP). Top-level for unit testing. Used to refuse to
 * render (or cache) bytes that are not actually an image — a corrupt-looking
 * image must never render; it surfaces as an error instead.
 */
fun isValidImageBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    // JPEG: FF D8 FF
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
    // PNG: 89 50 4E 47
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
    // GIF: 47 49 46 ("GIF")
    if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
    // WebP: 52 49 46 46 ("RIFF") .... 57 45 42 50 ("WEBP")
    if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    ) return true
    // HEIC/HEIF: .... 66 74 79 70 ("ftyp") at offset 4
    if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) return true
    // BMP: 42 4D ("BM")
    if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return true
    return false
}

/**
 * True when [bytes] decode end-to-end as an image. Stronger than
 * [isValidImageBytes]: the magic sniff only reads the header, so a valid
 * header over garbage scan data passes it and renders as gray blocks.
 * This runs the real decoder (downsampled 8x — only the verdict matters,
 * not the pixels, so it stays cheap) and rejects what won't decode.
 * Runs on Dispatchers.IO with the other media work, never the UI thread.
 */
fun decodesAsImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    return try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) != null
    } catch (_: Exception) {
        false
    }
}
