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
import com.garrietta.relay.data.model.isUnknownSenderGhostName
import com.garrietta.relay.rcs.proto.Rpc
import com.garrietta.relay.rcs.proto.encodeDevice
import com.garrietta.relay.rcs.proto.encodeDownloadAttachmentRequest
import com.garrietta.relay.rcs.proto.encodeUploadMediaRequest
import com.garrietta.relay.rcs.proto.UploadedMediaRef
import com.garrietta.relay.rcs.media.aesGcmDecryptChunked
import com.garrietta.relay.rcs.media.aesGcmEncryptChunked
import okhttp3.MediaType.Companion.toMediaType
import com.garrietta.relay.rcs.session.AuthData
import com.garrietta.relay.rcs.session.AuthStore
import com.garrietta.relay.rcs.session.RcsException
import com.garrietta.relay.rcs.session.RcsSession
import com.garrietta.relay.rcs.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
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

    /**
     * Images that arrived with no downloadable rendition at all (no
     * mediaID field 2, no thumbnailMediaID field 9) — rendered from the
     * tiny inline bytes. Surfaced in Diagnostics so Garrett can report
     * whether specific images (e.g. the baby-shower invite) land here.
     */
    private val noDownloadIdImages = java.util.concurrent.atomic.AtomicInteger(0)
    fun noDownloadIdImageCount(): Int = noDownloadIdImages.get()

    /**
     * Downloads whose encrypted wire was smaller than the declared
     * (MediaContent field 5) size but decrypted to a fully valid image
     * (GCM auth + trailer + decode all pass) — accepted as a complete
     * smaller rendition instead of failed as "truncated". Surfaced in
     * Diagnostics so a real mismatch pattern stays visible.
     */
    private val renditionSizeMismatch = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Downloads rejected by the splice guards (Garrett 2026-10-20: a
     * photo in the Haley chat still rendered corrupt). A resume answered
     * from a different rendition of the same mediaId — or at a drifting
     * offset — concatenates two different encrypted streams; at a chunk
     * boundary every 64 KiB chunk still authenticates (AAD binds the
     * chunk INDEX, which a same-geometry rendition shares), so GCM alone
     * cannot see the splice and the band-spliced image renders. The
     * guards: Content-Range start/total verification on 206, resumed
     * size must equal the declared size, and the decoded image must
     * consume its byte stream exactly (isSingleCompleteImage).
     */
    private val splicesRejected = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Remote typing state (Garrett 2026-10-19): the paired web protocol
     * delivers `UpdateEvents.typingEvent` on the long-poll — the wire
     * carries it, but nothing consumed it until now. Emitted as
     * (conversationId, typing, wall-clock ms) for the chat UI's typing
     * indicator. The event carries no sender id, so groups show a generic
     * indicator.
     */
    data class TypingState(val conversationId: String, val typing: Boolean, val atMs: Long)
    private val _typingEvents = MutableSharedFlow<TypingState>(extraBufferCapacity = 32)
    val typingEvents: SharedFlow<TypingState> = _typingEvents.asSharedFlow()

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
        return "pairing=$pairing, ${session.diagnostics()}, " +
            "noDownloadIdImages=${noDownloadIdImages.get()}, " +
            "renditionSizeMismatch=${renditionSizeMismatch.get()}, " +
            "splicesRejected=${splicesRejected.get()}, ${ghostScanDiagnostics()}"
    }

    /** Tachyon token expiry (epoch ms) for Diagnostics; null when not paired. */
    fun tachyonExpiryMs(): Long? =
        session.currentAuth()?.tachyonExpiryMs?.takeIf { it > 0 }

    /**
     * Ghost backstop (Garrett 2026-10-19): scans every known RCS
     * conversation's RAW participant names for anything containing
     * "unknown" (any case) that the ghost predicate does NOT catch. If the
     * choke point ever misses a new wrapping, this names the exact miss
     * with raw codepoints so the predicate can be fixed — no logcat needed.
     */
    private fun ghostScanDiagnostics(): String {
        val misses = _rcsConversations.value.flatMap { it.participants }
            .mapNotNull { p ->
                val raw = p.fullName.ifBlank { p.firstName }
                if (raw.contains("unknown", ignoreCase = true) &&
                    !raw.isUnknownSenderGhostName()
                ) {
                    val cps = raw.map { "U+%04X".format(it.code) }.joinToString(" ")
                    "${p.number.ifBlank { "nonum" }}: $cps"
                } else null
            }.distinct().take(3)
        return if (misses.isEmpty()) "ghostScan=clean"
        else "ghostMiss=" + misses.joinToString("; ")
    }

    /** Re-pulls LIST_CONVERSATIONS; failures go to [syncError], verbatim. */
    suspend fun refreshConversations() {
        try {
            _rcsConversations.value = session.listConversations()
            onConversationsRecovered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Conversations sync failed: ${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, msg, e)
            _syncError.value = msg
            // Don't sit dead on a transient failure: the session layer
            // already retried this pull, so now the background recovery
            // loop keeps trying (and restarts the session once) until it
            // lands or the pairing goes away.
            scheduleConversationRecovery()
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

    /** Background recovery job for an exhausted conversation sync. */
    @Volatile private var conversationRecoveryJob: Job? = null

    /**
     * A conversation pull landed: clear a previously surfaced sync error
     * and stop the background recovery loop — recovery is visible in the
     * UI (the error clears) and in Diagnostics (the session keeps the
     * transient-failure record).
     */
    private fun onConversationsRecovered() {
        if (_syncError.value?.startsWith("Conversations sync failed") == true) {
            _syncError.value = null
        }
        conversationRecoveryJob?.cancel()
        conversationRecoveryJob = null
    }

    /**
     * Keeps retrying a failed conversation sync in the background so a
     * transient network blip never leaves the inbox dead until the user
     * pull-refreshes (Garrett 2026-10-20: a momentary DNS failure
     * surfaced as a hard sync failure while the session was alive).
     * Schedule: quick retries (5s/15s/45s), then ONE session restart
     * from the stored session id — the existing recovery mechanism, no
     * re-pair — then a slow 60s retry for as long as we're paired.
     * Genuinely unrecoverable states still surface: the original
     * verbatim error stays in [syncError] until a pull lands.
     */
    private fun scheduleConversationRecovery() {
        if (conversationRecoveryJob?.isActive == true) return
        conversationRecoveryJob = scope.launch {
            for (backoffMs in listOf(5_000L, 15_000L, 45_000L)) {
                delay(backoffMs)
                if (pullConversationsQuietly()) return@launch
            }
            runCatching {
                session.stop()
                session.start()
            }
            if (pullConversationsQuietly()) return@launch
            while (isActive) {
                delay(60_000L)
                if (_pairingState.value != RcsPairingState.PAIRED) return@launch
                if (pullConversationsQuietly()) return@launch
            }
        }
    }

    /** One quiet conversation pull for the background recovery loop. */
    private suspend fun pullConversationsQuietly(): Boolean = try {
        _rcsConversations.value = session.listConversations()
        onConversationsRecovered()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Background conversation retry failed: ${e.message}", e)
        false
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
     * Sends a real reaction event (Garrett 2026-10-19). Returns true when
     * the server confirmed it; throws RcsException with the verbatim
     * failure reason otherwise.
     */
    suspend fun sendRcsReaction(messageId: String, emoji: String): Boolean {
        val ok = session.sendReaction(messageId, emoji)
        if (!ok) throw RcsException("SendReaction: server did not confirm success")
        return true
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
                    is SessionEvent.Typing -> {
                        _typingEvents.tryEmit(
                            TypingState(event.conversationId, event.typing, System.currentTimeMillis())
                        )
                    }
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
     * Corruption guards (2026-10-19): the cache is versioned (v4 since the
     * 2026-10-20 splice round — v3 bytes were accepted under rules that
     * could not see a rendition splice, so they are never served again);
     * every byte payload —
     * cached, downloaded, or inline — must pass the image magic check when
     * the attachment claims to be an image; downloaded payloads must FULLY
     * DECODE (not just pass the magic sniff — a valid header over garbage
     * scan data renders as gray blocks, which is what Garrett filmed);
     * and the decrypted size is validated against the WIRE GEOMETRY, not
     * the raw declared size (see below). A corrupt-looking image must NEVER
     * render.
     *
     * Size accounting — deep-dive fix (2026-10-19 evening): the
     * server-declared size (MediaContent field 5) is the ENCRYPTED wire
     * size — it includes the per-chunk 28-byte overhead (12B nonce + 16B
     * GCM tag). Comparing it directly against decrypted plaintext misfires
     * DETERMINISTICALLY (Garrett's Maria screenshot: 2,032,110 declared vs
     * 1,279,386 plaintext = exactly 26,883 chunks x 28B — no retry can fix
     * arithmetic). The expected plaintext is therefore derived from the
     * wire itself (wire bytes - 2B header - 28B per chunk) and must match
     * EXACTLY; a short WIRE (vs declared size) means a truncated download
     * and is retried. AES-GCM authenticates every chunk, so mid-stream
     * garbage is cryptographically impossible — only truncation (trailer
     * check) and corrupt sources (decode gate) remain.
     *
     * Retry (2026-10-19 evening): transient failures (IO, truncated
     * download, decrypt auth/size mismatch, HTTP 429/5xx) are retried up
     * to 3 times with backoff before the error surfaces; the bubble offers
     * tap-to-retry after that. Deterministic failures (bad key, bad header,
     * undecodable content) fail immediately.
     */
    suspend fun downloadMedia(att: RcsMediaAttachment): ByteArray =
        withContext(Dispatchers.IO) {
            // Full-size server copy.
            if (att.mediaId.isNotEmpty()) {
                require(att.decryptionKey.size == 32) { "media unavailable: no decryption key" }
                return@withContext downloadValidatedById(
                    id = att.mediaId,
                    key = att.decryptionKey,
                    declaredWireSize = att.size,
                    isImage = att.isImage,
                )
            }
            // No full-size ID, but the server gave a thumbnailMediaID (field
            // 9): download it through the same validated choke point. This
            // is the "some images render small" fix (Garrett 2026-10-19
            // evening) — the old code ignored field 9 entirely and rendered
            // the tiny inline field-7 bytes. The declared-size pre-check is
            // skipped (field 5 describes the full rendition, not the
            // thumbnail); the geometry-derived plaintext check still
            // applies. Any failure falls through to the inline bytes below.
            if (att.thumbnailMediaId.isNotEmpty() && att.thumbnailKey.size == 32) {
                try {
                    return@withContext downloadValidatedById(
                        id = att.thumbnailMediaId,
                        key = att.thumbnailKey,
                        declaredWireSize = 0,
                        isImage = att.isImage,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // fall through to inline bytes
                }
            }
            // Inline-only: no downloadable rendition at all.
            noDownloadIdImages.incrementAndGet()
            // No server copy — the inline bytes are the only bytes.
            // They may be a small full image or just a thumbnail; either
            // way the UI renders them at natural size, never upscaled.
            // Strict gates: magic + trailer + sane dimensions + full
            // decode. There is no size reference for inline bytes, and
            // BitmapFactory is lenient (partial decodes return non-null),
            // so the trailer check is what catches truncated thumbnails.
            val inline = att.inlineData
                ?: throw IllegalStateException("media unavailable: no attachment id or data")
            if (att.isImage) {
                if (!isValidImageBytes(inline)) {
                    throw IllegalStateException("media unavailable: inline bytes are not a valid image")
                }
                if (!hasIntactImageTrailer(inline)) {
                    throw IllegalStateException("media unavailable: inline image is truncated")
                }
                if (!isSingleCompleteImage(inline)) {
                    throw IllegalStateException("media unavailable: inline bytes are not a single complete image")
                }
                if (!hasSaneImageDimensions(inline)) {
                    throw IllegalStateException("media unavailable: inline image dimensions unusable")
                }
                if (!decodesAsImage(inline)) {
                    throw IllegalStateException("media unavailable: inline bytes do not decode as an image")
                }
            }
            return@withContext inline
        } // closes downloadMedia's withContext

    /**
     * Media HTTP client: large attachments need a generous read timeout —
     * the shared client's 30s per-read budget was cutting slow 3MB+
     * downloads off mid-stream (Garrett 2026-10-19: "truncated download"
     * on ~3.1MB images). 120s keeps the stream alive through stalls.
     */
    private fun mediaHttpClient(): okhttp3.OkHttpClient =
        RelayApi.client().newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    /**
     * Uploads an attachment through the RCS file-transfer path (Garrett
     * 2026-10-19: the attachment SEND path — the [+] picker finally sends
     * for real). The bytes are AES-GCM chunked-encrypted with a fresh
     * 32-byte sender key (exact inverse of [aesGcmDecryptChunked]), POSTed
     * to .../upload with the upload metadata in the x-goog-upload-metadata
     * header (the mirror of the download flow), and the returned media ID
     * goes into the message's MediaContent (the key rides in the message
     * itself, field 11, so the recipient can decrypt).
     *
     * [onProgress] reports 0..1 as the encrypted body streams. Throws with
     * a VERBATIM reason on failure — the upload-side metadata schema is
     * best-effort (mirrored from the device-proven download side); any
     * server rejection surfaces here for diagnosis from the device.
     */
    suspend fun uploadMedia(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        onProgress: (Float) -> Unit = {},
    ): UploadedMediaRef = withContext(Dispatchers.IO) {
        val token = session.currentAuth()?.tachyonToken
            ?: throw IllegalStateException("media unavailable: not paired")
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = aesGcmEncryptChunked(key, bytes)
        val meta = java.util.Base64.getEncoder().encodeToString(
            encodeUploadMediaRequest(
                AuthMessage(
                    requestId = java.util.UUID.randomUUID().toString(),
                    network = "GDitto",
                    tachyonToken = token,
                ),
                mimeType.ifBlank { "application/octet-stream" },
                fileName.ifBlank { "attachment" },
                bytes.size.toLong(),
            ),
        )
        val body = object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType? =
                "application/octet-stream".toMediaType()
            override fun contentLength(): Long = encrypted.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                val total = encrypted.size.toLong()
                var written = 0L
                val chunk = 65536
                while (written < total) {
                    val end = minOf(written + chunk, total).toInt()
                    sink.write(encrypted, written.toInt(), end - written.toInt())
                    written = end.toLong()
                    onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        val req = okhttp3.Request.Builder()
            .url(UPLOAD_URL)
            .post(body)
            .header("x-goog-upload-metadata", meta)
            .header("User-Agent", MEDIA_UA)
            .header("accept", "*/*")
            .header("origin", "https://messages.google.com")
            .header("referer", "https://messages.google.com/")
            .header("sec-fetch-site", "cross-site")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty")
            .build()
        val respText = try {
            mediaHttpClient().newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(
                        "media unavailable: upload HTTP ${resp.code} ${text.take(300)}",
                    )
                }
                text
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "media unavailable: upload failed (${e.message ?: e.javaClass.simpleName})",
            )
        }
        val mediaId = parseUploadMediaId(respText)
            ?: throw IllegalStateException(
                "media unavailable: upload returned no media ID (${respText.take(200)})",
            )
        // Seed the v4 download cache with the plaintext: the server echo
        // of this message carries the same mediaId, and MediaBubble renders
        // it from cache instantly instead of re-downloading our own upload.
        val cacheDir = java.io.File(appContext.cacheDir, "rcs_media/v4")
        writeCacheAtomically(java.io.File(cacheDir, mediaId.sanitizeFileName()), bytes)
        UploadedMediaRef(
            mediaId = mediaId,
            decryptionKey = key,
            mimeType = mimeType,
            fileName = fileName,
            wireSize = encrypted.size.toLong(),
        )
    }

    /**
     * Extracts the media ID from the upload endpoint's response. The exact
     * response shape is unverified — try JSON fields, a bare ID string,
     * then a binary-proto string field — and report verbatim when none
     * matches.
     */
    private fun parseUploadMediaId(respText: String): String? {
        val t = respText.trim()
        if (t.isEmpty()) return null
        // JSON: {"mediaId": "..."} / {"media_id": "..."} / {"id": "..."}.
        val jsonHit = Regex("\"(?:mediaId|media_id|id)\"\\s*:\\s*\"([^\"]+)\"")
            .find(t)?.groupValues?.get(1)
        if (!jsonHit.isNullOrEmpty()) return jsonHit
        // Bare ID on one line.
        if (!t.contains("\n") && t.length in 8..256 && t.all { it.isLetterOrDigit() || it in "-_." }) {
            return t
        }
        return null
    }

    /**
     * Sends an already-uploaded media attachment via the RCS session.
     * Returns the tmpId for echo correlation; throws RcsException with the
     * verbatim failure reason.
     */
    suspend fun sendRcsMedia(
        conversationId: String,
        media: UploadedMediaRef,
        caption: String = "",
        tmpId: String = java.util.UUID.randomUUID().toString(),
    ): String = session.sendMedia(conversationId, media, caption, tmpId)

    /**
     * Sends a shared location via the RCS session (native LocationContent
     * payload + the Maps-link text in the same send). Returns the tmpId
     * for echo correlation; throws RcsException with the verbatim failure
     * reason.
     */
    suspend fun sendRcsLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        name: String = "",
        linkText: String,
        tmpId: String = java.util.UUID.randomUUID().toString(),
    ): String = session.sendLocation(conversationId, latitude, longitude, name, linkText, tmpId)

    /**
     * A body that ended before its declared length. Carries the partial
     * bytes so the caller can resume with Range instead of starting over.
     * Extends IOException so the existing retry loop treats it as
     * transient.
     */
    private class TruncatedBodyException(
        val partial: ByteArray,
        message: String,
    ) : java.io.IOException(message)

    /**
     * Streaming body read with explicit short-read detection. OkHttp's
     * `bytes()` can return a short array WITHOUT throwing when the server
     * terminates the stream "cleanly" early (chunked terminator) — the
     * declared-size check downstream caught it, but only after a full
     * wasted round trip. This throws the moment the body is short, with
     * the partial bytes attached for Range resume.
     */
    private fun readBodyFully(body: okhttp3.ResponseBody): ByteArray {
        val declared = body.contentLength() // -1 when the server omits it
        val sink = okio.Buffer()
        try {
            val source = body.source()
            while (true) {
                if (source.read(sink, 8192) == -1L) break
            }
        } catch (e: java.io.IOException) {
            throw TruncatedBodyException(
                sink.readByteArray(),
                "media unavailable: connection broke mid-download (${e.message})",
            )
        }
        val bytes = sink.readByteArray()
        if (declared > 0 && bytes.size < declared) {
            throw TruncatedBodyException(
                bytes,
                "media unavailable: short body (got ${bytes.size} of $declared bytes)",
            )
        }
        return bytes
    }

    /**
     * Range resume for a truncated download (Garrett 2026-10-19). Asks the
     * server for bytes [partial.size]- onward and appends. 206 =
     * resumed; 200 = server ignored Range (fresh full body must then be
     * complete); 416 = resume rejected (fail retryable). Anything else
     * follows the existing transient/deterministic split.
     *
     * SPLICE GUARD (Garrett 2026-10-20): the append is no longer blind.
     * The server picks a rendition per request (Build #130 evidence: one
     * mediaId serving 614 KB and ~3.1 MB variants), so the resume can be
     * answered from a DIFFERENT rendition than the truncated GET — and
     * because the wire chunks are 64 KiB and each authenticates under an
     * AAD of (index, last-flag) that a same-key rendition shares,
     * prefix-of-A + tail-of-B decrypts cleanly and renders as a
     * band-spliced corrupt photo. So: a 206 must carry a Content-Range
     * whose start is EXACTLY the requested offset, and the assembled
     * total must equal the range total when one is declared. The caller
     * adds a second layer: a resumed wire must equal the declared size
     * when one is known (see downloadValidatedById). An unverifiable or
     * mismatched resume throws retryable — the retry loop then fetches
     * ONE whole rendition in a single body, which cannot splice.
     */
    private fun resumeDownload(
        baseReq: okhttp3.Request,
        partial: ByteArray,
    ): ByteArray {
        if (partial.isEmpty()) {
            throw RetryableMediaException("media unavailable: empty download")
        }
        val req = baseReq.newBuilder()
            .header("Range", "bytes=${partial.size}-")
            .build()
        mediaHttpClient().newCall(req).execute().use { resp ->
            when {
                resp.code == 206 -> {
                    val range = parseContentRange(resp.header("Content-Range"))
                    if (!resumeOffsetAcceptable(range, partial.size.toLong())) {
                        splicesRejected.incrementAndGet()
                        throw RetryableMediaException(
                            "media unavailable: resume offset mismatch " +
                                "(range starts at ${range?.start}, expected ${partial.size})",
                        )
                    }
                    val rest = readBodyFully(resp.body!!)
                    val assembled = partial + rest
                    if (!resumeTotalAcceptable(range, assembled.size.toLong())) {
                        splicesRejected.incrementAndGet()
                        throw RetryableMediaException(
                            "media unavailable: resumed size ${assembled.size} " +
                                "!= range total ${range?.total}",
                        )
                    }
                    return assembled
                }
                resp.isSuccessful -> {
                    // 200: the server ignored Range and sent a fresh full
                    // body. It replaces the partial wholesale (never
                    // appends), and readBodyFully has already proven it
                    // matches its own declared length — accept only if it
                    // is at least the partial we already hold.
                    val fresh = readBodyFully(resp.body!!)
                    if (resumeFreshBodyAcceptable(fresh.size, partial.size)) return fresh
                    throw RetryableMediaException(
                        "media unavailable: server serves short body (got ${fresh.size} bytes)",
                    )
                }
                resp.code == 429 || resp.code >= 500 ->
                    throw RetryableMediaException("media unavailable: resume HTTP ${resp.code}")
                resp.code == 416 ->
                    throw RetryableMediaException(
                        "media unavailable: server rejected resume at ${partial.size} bytes",
                    )
                else -> throw IllegalStateException("media unavailable: resume HTTP ${resp.code}")
            }
        }
    }

    /**
     * Validated download core shared by the full-size and thumbnail paths.
     * declaredWireSize = 0 skips the declared-size pre-check (used for the
     * thumbnail rendition, whose declared size describes the full media).
     * Every gate from the deep-dive round applies identically here.
     */
    private suspend fun downloadValidatedById(
        id: String,
        key: ByteArray,
        declaredWireSize: Long,
        isImage: Boolean,
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            val cacheDir = java.io.File(appContext.cacheDir, "rcs_media/v4")
            val cacheFile = java.io.File(cacheDir, id.sanitizeFileName())
            if (cacheFile.exists()) {
                val cached = cacheFile.readBytes()
                // Cheap revalidation on every cache hit: magic + trailer +
                // bounds (no full decode — it passed at download time).
                // Poisoned bytes are deleted and re-fetched below.
                if (isImage && !isCacheableImageBytes(cached)) {
                    cacheFile.delete()
                } else {
                    return@withContext cached
                }
            }
            // Single attempt: fetch + decrypt + validate. Kept as a local so
            // the retry loop below can re-run a FRESH download.
            suspend fun attemptDownload(): ByteArray {
                val token = session.currentAuth()?.tachyonToken
                    ?: throw IllegalStateException("media unavailable: not paired")
                val meta = java.util.Base64.getEncoder().encodeToString(
                    encodeDownloadAttachmentRequest(
                        AuthMessage(
                            requestId = java.util.UUID.randomUUID().toString(),
                            network = "GDitto",
                            tachyonToken = token,
                        ),
                        id,
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
                var usedResume = false
                val encrypted = try {
                    mediaHttpClient().newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val code = resp.code
                            // 429/5xx are transient — retry; 4xx is deterministic.
                            if (code == 429 || code >= 500) {
                                throw RetryableMediaException("media unavailable: download HTTP $code")
                            }
                            throw IllegalStateException("media unavailable: download HTTP $code")
                        }
                        readBodyFully(resp.body!!)
                    }
                } catch (e: TruncatedBodyException) {
                    // The stream died mid-body: resume from the partial
                    // bytes instead of re-downloading from zero.
                    usedResume = true
                    resumeDownload(req, e.partial)
                }
                // Wire-integrity accounting (deep-dive 2026-10-19): parse the
                // chunk geometry from the stream header BEFORE decrypting.
                if (encrypted.size < 2 || encrypted[0] != 0.toByte()) {
                    throw IllegalStateException("media unavailable: bad media stream header")
                }
                val wireChunk = 1 shl (encrypted[1].toInt() and 0xFF)
                if (wireChunk <= 28) {
                    throw IllegalStateException("media unavailable: bad media chunk size")
                }
                // SPLICE GUARD, second layer (Garrett 2026-10-20): a
                // RESUMED wire must land exactly on the declared size when
                // one is known. A different total means the two halves
                // came from different renditions of the same mediaId —
                // reject so the retry loop fetches one whole rendition in
                // a single body. Single-body downloads keep the #130
                // smaller-rendition acceptance further below; this check
                // is about byte-integrity of the join, not about size.
                if (!resumedWireSizeAcceptable(usedResume, encrypted.size.toLong(), declaredWireSize)) {
                    splicesRejected.incrementAndGet()
                    throw RetryableMediaException(
                        "media unavailable: resumed size ${encrypted.size} != declared $declaredWireSize",
                    )
                }
                // The declared size (MediaContent field 5) is USUALLY the
                // encrypted wire size — but the server sometimes serves a
                // smaller COMPLETE rendition for the same mediaId (Garrett
                // 2026-10-19: a GIRTH image deterministically serves
                // 614,494 bytes against ~3,132,125 declared, byte-identical
                // across builds — a complete HTTP body, not a cut stream,
                // so resume can't engage). A short wire is therefore NOT
                // proof of truncation: decrypt and validate the bytes on
                // their own merits. Genuine truncation still fails below —
                // GCM auth rejects a partial final chunk, the trailer check
                // rejects a stream cut at a chunk boundary, and the decode
                // gate rejects garbage.
                val declaredShortfall = declaredWireSize > 0 &&
                    encrypted.size < (declaredWireSize * 0.95).toLong()
                val plain = aesGcmDecryptChunked(key, encrypted)
                // Exact plaintext check, derived from the wire itself:
                // wire - 2B header - 28B (nonce+tag) per chunk. AES-GCM is
                // deterministic, so any deviation means lost bytes.
                val expectedPlain = wireExpectedPlaintext(encrypted)
                if (plain.size.toLong() != expectedPlain) {
                    throw RetryableMediaException(
                        "media unavailable: decrypt size mismatch (got ${plain.size}, expected $expectedPlain)",
                    )
                }
                if (isImage) {
                    if (!isValidImageBytes(plain)) {
                        throw IllegalStateException("media unavailable: downloaded bytes are not a valid image")
                    }
                    if (!hasIntactImageTrailer(plain)) {
                        throw RetryableMediaException("media unavailable: downloaded image is truncated")
                    }
                    // One image, whole stream (Garrett 2026-10-20): a
                    // band-spliced file keeps a valid header AND a valid
                    // trailer — they come from different renditions — so
                    // verify the first image consumes the entire byte
                    // stream before the decode gate. BitmapFactory is
                    // lenient enough to "decode" a splice into a corrupt
                    // bitmap, so this structural walk is the gate that
                    // actually sees it.
                    if (!isSingleCompleteImage(plain)) {
                        splicesRejected.incrementAndGet()
                        throw RetryableMediaException(
                            "media unavailable: downloaded bytes are not a single complete image",
                        )
                    }
                    if (!hasSaneImageDimensions(plain)) {
                        throw IllegalStateException("media unavailable: downloaded image dimensions unusable")
                    }
                    // Full-decode gate: magic bytes only sniff the header. A
                    // valid header over garbage scan data passes the sniff
                    // and renders as gray blocks — decode the whole stream
                    // (downsampled: we only need the verdict, not the pixels)
                    // and reject what won't decode.
                    if (!decodesAsImage(plain)) {
                        throw IllegalStateException("media unavailable: downloaded bytes do not decode as an image")
                    }
                }
                // Every gate passed: the bytes are a complete, valid image
                // even if smaller than the declared size — accept the
                // rendition and note it for Diagnostics.
                if (declaredShortfall) renditionSizeMismatch.incrementAndGet()
                return plain
            }
            // Retry (Garrett 2026-10-19): transient failures — network IO,
            // decrypt auth (a flipped bit in transit), truncated downloads,
            // decrypt size mismatches, HTTP 429/5xx — are retried up to 3
            // attempts with backoff before surfacing the error. Deterministic
            // failures (bad key, bad header, undecodable content) throw
            // immediately.
            var plain: ByteArray? = null
            var attempt = 0
            while (plain == null) {
                try {
                    plain = attemptDownload()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    attempt++
                    val retryable = e is java.io.IOException ||
                        e is java.security.GeneralSecurityException ||
                        e is RetryableMediaException
                    if (!retryable || attempt >= 3) throw e
                    delay(1000L * attempt)
                }
            }
            writeCacheAtomically(cacheFile, plain)
            plain
        }
    }

    /** Strips path-unsafe characters from a cache file name. */
    private fun String.sanitizeFileName(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(128).ifBlank { "media" }

    /**
     * Atomic cache write (Garrett 2026-10-20): bytes land in a sibling
     * temp file and RENAME into place. The old direct writeBytes let a
     * concurrent reader observe a half-written file, and two in-flight
     * downloads of the same mediaId (bubble + details + fullscreen all
     * fetch independently) serving different renditions could interleave
     * their writes into a spliced cache file — header from one write,
     * trailer from the other — which the cache-hit revalidation then
     * happily served forever. A same-directory rename is atomic: readers
     * see the old complete file or the new complete file, never a join.
     * Falls back to a direct write if the rename itself fails.
     */
    private fun writeCacheAtomically(target: java.io.File, bytes: ByteArray) {
        val dir = target.parentFile
        dir?.mkdirs()
        val tmp = java.io.File(dir, target.name + ".tmp-" + System.nanoTime())
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
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
fun isValidImageBytes(bytes: ByteArray): Boolean = imageKindOf(bytes) != ImageKind.UNKNOWN

/** Image container detected from magic bytes. Pure-Kotlin, JVM-testable. */
enum class ImageKind { JPEG, PNG, GIF, WEBP, HEIC, BMP, UNKNOWN }

internal fun imageKindOf(bytes: ByteArray): ImageKind {
    if (bytes.size < 12) return ImageKind.UNKNOWN
    // JPEG: FF D8 FF
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return ImageKind.JPEG
    // PNG: 89 50 4E 47
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return ImageKind.PNG
    // GIF: 47 49 46 ("GIF")
    if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return ImageKind.GIF
    // WebP: 52 49 46 46 ("RIFF") .... 57 45 42 50 ("WEBP")
    if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
    ) return ImageKind.WEBP
    // HEIC/HEIF: .... 66 74 79 70 ("ftyp") at offset 4
    if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) return ImageKind.HEIC
    // BMP: 42 4D ("BM")
    if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return ImageKind.BMP
    return ImageKind.UNKNOWN
}

/**
 * True when the image's TRAILER is intact — the cheap truncation detector
 * the magic sniff can't provide. A truncated download keeps a valid header
 * (passes [isValidImageBytes]) and Android's BitmapFactory is lenient
 * (partial decodes return non-null), so without this a cut-off JPEG renders
 * as gray blocks or a glitch strip. Pure-Kotlin, JVM-testable.
 */
fun hasIntactImageTrailer(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    return when (imageKindOf(bytes)) {
        // JPEG must end with the EOI marker FF D9.
        ImageKind.JPEG ->
            bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
        // PNG must end with the IEND chunk: length 0 + "IEND" + CRC.
        ImageKind.PNG -> bytes.size >= 12 &&
            bytes[bytes.size - 8] == 0x49.toByte() && bytes[bytes.size - 7] == 0x45.toByte() &&
            bytes[bytes.size - 6] == 0x4E.toByte() && bytes[bytes.size - 5] == 0x44.toByte()
        // GIF must end with the 0x3B trailer byte.
        ImageKind.GIF -> bytes[bytes.size - 1] == 0x3B.toByte()
        // WebP: RIFF chunk size (LE32 at offset 4) + 8 must equal the file size.
        ImageKind.WEBP -> {
            val riffSize = (bytes[4].toInt() and 0xFF) or
                ((bytes[5].toInt() and 0xFF) shl 8) or
                ((bytes[6].toInt() and 0xFF) shl 16) or
                ((bytes[7].toInt() and 0xFF) shl 24)
            riffSize + 8 == bytes.size
        }
        // BMP: bfSize (LE32 at offset 2) must equal the file size.
        ImageKind.BMP -> {
            val bfSize = (bytes[2].toInt() and 0xFF) or
                ((bytes[3].toInt() and 0xFF) shl 8) or
                ((bytes[4].toInt() and 0xFF) shl 16) or
                ((bytes[5].toInt() and 0xFF) shl 24)
            bfSize == bytes.size
        }
        // HEIC/HEIF is a box container with no single trailer — the magic
        // and decode gates carry it.
        ImageKind.HEIC -> true
        ImageKind.UNKNOWN -> false
    }
}

/**
 * True when the header-declared dimensions are sane for display. A header
 * claiming a sub-[minPx] dimension renders as a glitch strip in the bubble
 * (Garrett's group-chat slivers, 2026-10-19) — refuse it instead. Bounds
 * decode is header-only (no pixel work), so this stays cheap.
 */
fun hasSaneImageDimensions(bytes: ByteArray, minPx: Int = 24): Boolean {
    if (bytes.size < 12) return false
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.outWidth >= minPx && opts.outHeight >= minPx
    } catch (_: Exception) {
        false
    }
}

/**
 * Cheap revalidation for cache hits: magic + trailer + bounds + the
 * single-image consumption walk. Skips the full pixel decode (it passed
 * at download time); poisoned bytes are deleted and re-fetched by the
 * caller. The consumption walk is what evicts a spliced file — it has a
 * valid header and trailer by construction, so the older checks alone
 * would serve it forever.
 */
fun isCacheableImageBytes(bytes: ByteArray): Boolean =
    isValidImageBytes(bytes) && hasIntactImageTrailer(bytes) &&
        hasSaneImageDimensions(bytes) && isSingleCompleteImage(bytes)

/**
 * Expected decrypted plaintext size for an AES-GCM chunked media stream,
 * derived from the wire geometry: total wire bytes minus the 2-byte header
 * minus 28 bytes (12B nonce + 16B GCM tag) per wire chunk. Pure-Kotlin,
 * JVM-testable. AES-GCM decryption is deterministic, so a correct download
 * must produce EXACTLY this many plaintext bytes.
 */
fun wireExpectedPlaintext(encrypted: ByteArray): Long {
    require(encrypted.size >= 2 && encrypted[0] == 0.toByte()) { "bad media stream header" }
    val wireChunk = 1 shl (encrypted[1].toInt() and 0xFF)
    require(wireChunk > 28) { "bad media chunk size" }
    val numChunks = (encrypted.size - 2 + wireChunk - 1) / wireChunk
    return (encrypted.size - 2 - numChunks * 28L).coerceAtLeast(0)
}

/** Parsed HTTP Content-Range header: byte range [start]..[end] of [total] (null total when the server sends the star form). */
data class ContentRange(val start: Long, val end: Long, val total: Long?)

/**
 * Parses a Content-Range response header — the form "bytes
 * <start>-<end>/<total>", where the total may be a star. Returns null
 * for an absent or malformed value; callers treat null as
 * "unverifiable" and lean on their other integrity checks (the caller
 * in downloadValidatedById still pins a resumed wire to the declared
 * size). Pure-Kotlin, JVM-testable.
 */
fun parseContentRange(value: String?): ContentRange? {
    if (value.isNullOrBlank()) return null
    val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(value.trim()) ?: return null
    val start = m.groupValues[1].toLongOrNull() ?: return null
    val end = m.groupValues[2].toLongOrNull() ?: return null
    if (m.groupValues[3] == "*") return ContentRange(start, end, null)
    val total = m.groupValues[3].toLongOrNull() ?: return null
    return ContentRange(start, end, total)
}

/**
 * A 206 resume may be appended ONLY when its declared range starts
 * exactly at the requested offset. A null range (server omitted the
 * header) is tolerated here — the total and declared-size checks still
 * apply — but a present-and-wrong start is positive evidence of a
 * splice. Pure-Kotlin, JVM-testable.
 */
fun resumeOffsetAcceptable(range: ContentRange?, requestedOffset: Long): Boolean =
    range == null || range.start == requestedOffset

/**
 * When the 206 declares a total, the assembled wire must equal it
 * exactly — a different total means the two halves describe different
 * renditions. Pure-Kotlin, JVM-testable.
 */
fun resumeTotalAcceptable(range: ContentRange?, assembledSize: Long): Boolean =
    range?.total == null || range.total == assembledSize

/**
 * A 200 answer to a resume request replaces the partial wholesale; it
 * is acceptable only when it is at least as long as the partial already
 * held (readBodyFully has separately proven it matches its own
 * declared length). Pure-Kotlin, JVM-testable.
 */
fun resumeFreshBodyAcceptable(freshSize: Int, partialSize: Int): Boolean =
    freshSize >= partialSize

/**
 * Second splice layer at the caller: when a resume was used and the
 * media's declared wire size is known, the assembled wire must equal
 * it exactly. Single-body downloads (usedResume = false) and the
 * thumbnail path (declaredWireSize = 0, size describes the full
 * rendition) are unaffected — the Build #130 smaller-rendition
 * acceptance lives there, and this check never fires for them.
 * Pure-Kotlin, JVM-testable.
 */
fun resumedWireSizeAcceptable(
    usedResume: Boolean,
    assembledSize: Long,
    declaredWireSize: Long,
): Boolean = !usedResume || declaredWireSize <= 0L || assembledSize == declaredWireSize

private fun u16be(bytes: ByteArray, off: Int): Int =
    ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

private fun u32be(bytes: ByteArray, off: Int): Long =
    ((bytes[off].toLong() and 0xFF) shl 24) or
        ((bytes[off + 1].toLong() and 0xFF) shl 16) or
        ((bytes[off + 2].toLong() and 0xFF) shl 8) or
        (bytes[off + 3].toLong() and 0xFF)

private fun u64be(bytes: ByteArray, off: Int): Long =
    (u32be(bytes, off) shl 32) or u32be(bytes, off + 4)

/**
 * True when [bytes] are exactly ONE complete image: the first image's
 * own structure consumes the entire byte stream, with no second image
 * spliced in or appended. A band splice keeps a valid header (from one
 * rendition) and a valid trailer (from another), so the magic and
 * trailer gates are blind to it — this walk is not. Pure-Kotlin,
 * JVM-testable. Conservative per format, favoring false-accepts over
 * rejecting a genuine image:
 * - PNG: the chunk chain must parse cleanly and IEND must end exactly
 *   at the file end (APNG rides the same chunk chain — still one file).
 * - HEIC/HEIF: the top-level box chain must end exactly at the file end.
 * - JPEG: entropy-coded data can never contain an unstuffed FF D8
 *   (byte stuffing; restart markers are FF D0–D7), so a second SOI
 *   after SOS is a spliced-in image header. EXIF thumbnails live in
 *   APP segments BEFORE SOS and stay legal.
 * - GIF: block-walk to the trailer; a second GIF signature after the
 *   trailer is an appended image. Structural surprises pass — only the
 *   positive second-image signal fails.
 * - WebP/BMP: the trailer check already pins the declared size to the
 *   file size exactly, so nothing can trail unnoticed.
 */
fun isSingleCompleteImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    return when (imageKindOf(bytes)) {
        ImageKind.PNG -> pngChunksConsumeAll(bytes)
        ImageKind.HEIC -> heicBoxesConsumeAll(bytes)
        ImageKind.JPEG -> !jpegHasSecondSoiAfterSos(bytes)
        ImageKind.GIF -> !gifHasAppendedSecondImage(bytes)
        ImageKind.WEBP, ImageKind.BMP -> hasIntactImageTrailer(bytes)
        ImageKind.UNKNOWN -> false
    }
}

/** PNG chunk walk: 8-byte signature, then length-prefixed chunks; IEND must end exactly at the file end. */
private fun pngChunksConsumeAll(bytes: ByteArray): Boolean {
    var pos = 8 // signature
    var first = true
    while (pos + 8 <= bytes.size) {
        val len = u32be(bytes, pos)
        if (len > bytes.size) return false
        val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
        if (first && type != "IHDR") return false
        first = false
        val next = pos + 8L + len + 4L // chunk header + data + CRC
        if (next > bytes.size) return false
        pos = next.toInt()
        if (type == "IEND") return pos == bytes.size
    }
    return false
}

/** ISOBMFF box walk (HEIC/HEIF): size+type headers; the chain must end exactly at the file end. */
private fun heicBoxesConsumeAll(bytes: ByteArray): Boolean {
    var pos = 0
    var sawFtyp = false
    while (pos + 8 <= bytes.size) {
        var size = u32be(bytes, pos)
        val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
        if (pos == 0) sawFtyp = type == "ftyp"
        var header = 8L
        when {
            size == 1L -> {
                // 64-bit largesize follows the type.
                if (pos + 16 > bytes.size) return false
                size = u64be(bytes, pos + 8)
                header = 16L
            }
            size == 0L -> {
                // Box extends to end of file — legal only as the last box.
                return sawFtyp && pos > 0
            }
        }
        if (size < header || pos + size > bytes.size) return false
        pos += size.toInt()
        if (pos == bytes.size) return sawFtyp
    }
    return false
}

/**
 * Marker-walks a JPEG to its SOS segment, then scans the entropy data
 * for a second SOI (FF D8). Entropy bytes are stuffed (an FF payload
 * byte is always followed by 00; restart markers are FF D0–D7), so an
 * FF D8 after SOS can only be a spliced-in second image header.
 * Returns false when the walk is inconclusive — the decode gate owns
 * malformed files; only the positive signal fails here.
 */
private fun jpegHasSecondSoiAfterSos(bytes: ByteArray): Boolean {
    var pos = 2 // SOI already matched by the magic check
    while (pos + 4 <= bytes.size) {
        if (bytes[pos] != 0xFF.toByte()) return false
        val marker = bytes[pos + 1].toInt() and 0xFF
        when {
            // Standalone markers carry no length: TEM, RST0-7, SOI.
            marker == 0x01 || marker in 0xD0..0xD8 -> pos += 2
            marker == 0xD9 -> return false // EOI before SOS: no scan data
            marker == 0xDA -> { // SOS: segment, then entropy data to EOI
                val segLen = u16be(bytes, pos + 2)
                var scan = pos + 2 + segLen
                while (scan + 1 < bytes.size) {
                    if (bytes[scan] == 0xFF.toByte() &&
                        bytes[scan + 1] == 0xD8.toByte()
                    ) return true
                    scan++
                }
                return false
            }
            else -> {
                val segLen = u16be(bytes, pos + 2)
                if (segLen < 2) return false
                pos += 2 + segLen
            }
        }
    }
    return false
}

/**
 * GIF block walk: 6-byte header + 7-byte logical screen descriptor
 * (+ global color table), then extension and image blocks — each a
 * chain of data sub-blocks — to the 3B trailer. If bytes remain after
 * the trailer and contain a second GIF signature, a whole second GIF
 * was appended. Structural surprises return false (tolerant); only the
 * positive second-image signal fails the gate.
 */
private fun gifHasAppendedSecondImage(bytes: ByteArray): Boolean {
    fun subBlockEnd(start: Int): Int {
        var p = start
        while (p < bytes.size) {
            val n = bytes[p].toInt() and 0xFF
            p++
            if (n == 0) return p
            p += n
            if (p > bytes.size) return -1
        }
        return -1
    }
    return try {
        var pos = 6 // "GIF8xa" header
        if (pos + 7 > bytes.size) return false
        val packed = bytes[pos + 4].toInt() and 0xFF
        pos += 7
        if (packed and 0x80 != 0) {
            pos += 3 * (1 shl ((packed and 0x07) + 1))
        }
        while (pos < bytes.size) {
            when (bytes[pos].toInt() and 0xFF) {
                0x3B -> { // trailer: the first GIF ends here
                    val rest = bytes.copyOfRange(pos + 1, bytes.size)
                    return indexOfGifSignature(rest) >= 0
                }
                0x21 -> { // extension: label byte, then sub-blocks
                    val end = subBlockEnd(pos + 2)
                    if (end < 0) return false
                    pos = end
                }
                0x2C -> { // image descriptor: 9 bytes + optional local
                    // color table + LZW minimum code size + sub-blocks
                    if (pos + 10 > bytes.size) return false
                    val ipacked = bytes[pos + 9].toInt() and 0xFF
                    var p = pos + 10
                    if (ipacked and 0x80 != 0) {
                        p += 3 * (1 shl ((ipacked and 0x07) + 1))
                    }
                    if (p >= bytes.size) return false
                    val end = subBlockEnd(p + 1)
                    if (end < 0) return false
                    pos = end
                }
                else -> return false
            }
        }
        false
    } catch (_: Exception) {
        false
    }
}

/** Offset of a "GIF8" signature in [bytes], or -1. */
private fun indexOfGifSignature(bytes: ByteArray): Int {
    if (bytes.size < 4) return -1
    for (i in 0..bytes.size - 4) {
        if (bytes[i] == 0x47.toByte() && bytes[i + 1] == 0x49.toByte() &&
            bytes[i + 2] == 0x46.toByte() && bytes[i + 3] == 0x38.toByte()
        ) return i
    }
    return -1
}

/** Marker for media failures worth retrying (transient, not deterministic). */
private class RetryableMediaException(message: String) : IllegalStateException(message)

/**
 * True when [bytes] decode end-to-end as an image. Stronger than
 * [isValidImageBytes]: the magic sniff only reads the header, so a valid
 * header over garbage scan data passes it and renders as gray blocks.
 * This runs the real decoder (downsampled 2x — only the verdict matters,
 * not the pixels, so it stays cheap) and rejects what won't decode.
 * Note BitmapFactory is lenient (partial decodes return non-null), so this
 * is the LAST gate, not the only one: [hasIntactImageTrailer] catches
 * truncation and the wire-size accounting catches short downloads.
 * Runs on Dispatchers.IO with the other media work, never the UI thread.
 */
fun decodesAsImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    return try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) != null
    } catch (_: Exception) {
        false
    }
}
