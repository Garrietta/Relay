package com.garrietta.relay.rcs.session

import android.content.Context
import android.util.Log
import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.net.postRpc
import com.garrietta.relay.rcs.net.postRpcCancellable
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.proto.FieldVal
import com.garrietta.relay.rcs.proto.ProtoCodec
import com.garrietta.relay.rcs.proto.firstMsg
import com.garrietta.relay.rcs.proto.firstStr
import com.garrietta.relay.rcs.proto.PollPayload
import com.garrietta.relay.rcs.proto.RcsConversation
import com.garrietta.relay.rcs.proto.RcsMessage
import com.garrietta.relay.rcs.proto.RcsParticipant
import com.garrietta.relay.rcs.proto.Rpc
import com.garrietta.relay.rcs.proto.buildGaiaOutgoingPblite
import com.garrietta.relay.rcs.proto.encodeOutgoingRpcDataUnencrypted
import com.garrietta.relay.rcs.proto.UpdateEvent
import com.garrietta.relay.rcs.proto.buildOutgoingRpcPblite
import com.garrietta.relay.rcs.proto.decodeConversationsResponse
import com.garrietta.relay.rcs.proto.decodeLongPollBody
import com.garrietta.relay.rcs.proto.decodeMessagesResponse
import com.garrietta.relay.rcs.proto.decodeRpcMessageData
import com.garrietta.relay.rcs.proto.decodeRpcPairData
import com.garrietta.relay.rcs.proto.decodeSendMessageStatus
import com.garrietta.relay.rcs.proto.decodeUpdateEvents
import com.garrietta.relay.rcs.proto.encodeAckMessagesRequest
import com.garrietta.relay.rcs.proto.encodeListConversationsRequest
import com.garrietta.relay.rcs.proto.encodeListMessagesRequest
import com.garrietta.relay.rcs.proto.encodeMessageReadRequest
import com.garrietta.relay.rcs.proto.encodeOutgoingRpcData
import com.garrietta.relay.rcs.proto.encodeReceiveMessagesRequest
import com.garrietta.relay.rcs.proto.encodeSendMessageRequest
import com.garrietta.relay.rcs.proto.isWeirdPreResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live RCS session — clean-room implementation.
 *
 * Owns: the long-poll receive loop, the decoupled ack loop (strict 5s
 * ticker + immediate flush at threshold), request/response correlation by
 * requestID, reconnect with exponential backoff (500ms initial), and the
 * high-level operations (conversations, send, history).
 *
 * DOCUMENTED ASSUMPTIONS (unverified vs Google's servers — see report):
 *  A1. Uplink RPCs POST to .../v1.Messaging/SendMessage; responses arrive
 *      asynchronously via the long-poll, correlated by IncomingRPCMessage
 *      responseID == our requestID.
 *  A2. Sealed inner payloads are pblite JSON (UTF-8) before sealing.
 *  A3. Acks reference IncomingRPCMessage.responseID.
 *  A4. The envelope HMAC input order is (ciphertext ‖ iv).
 */
class RcsSession(context: Context) {
    private val appContext = context.applicationContext
    private val store = AuthStore(appContext)
    private val pollClient: OkHttpClient = RelayApi.client(longPoll = true)
    private val rpcClient: OkHttpClient = RelayApi.client()

    private var scope: CoroutineScope? = null

    companion object {
        private const val TAG = "RcsSession"
        /** Gaia pairing message TTL (300s, in microseconds) — reference CustomTTL. */
        const val GAIA_TTL_MICROS = 300_000_000L
    }
    private val _incoming = MutableSharedFlow<RcsMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<RcsMessage> = _incoming.asSharedFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /** requestID → waiter for the inner response bytes (pblite JSON or binary proto). */
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    /** (responseID → device) pairs queued for ack. */
    private val ackQueue = Channel<Pair<String, DeviceInfo>>(Channel.UNLIMITED)

    @Volatile private var auth: AuthData? = null
    @Volatile private var mobile: DeviceInfo = DeviceInfo()

    /**
     * Gaia-mode cookie jar: when set, the long-poll and [sendGaia] use the
     * cookie-authenticated Gaia endpoints (like the reference when
     * HasCookies()). Set during the pairing handshake; kept afterwards —
     * the reference keeps using the Google host while cookies exist.
     */
    @Volatile private var gaiaCookies: String? = null

    /** Completes when the long-poll channel is first established. Reset on (re)start. */
    @Volatile private var pollReady = CompletableDeferred<Unit>()

    /**
     * Latest long-poll failure detail (HTTP code + body snippet, or the
     * exception message). Set on every failed poll attempt; read by
     * [sendGaia] if the channel never establishes, so the UI/logcat show the
     * UNDERLYING cause instead of a generic "never established".
     */
    @Volatile private var pollLastError: String? = null

    // ---------- transport diagnostics (Settings → Diagnostics) ----------
    /** Last RPC action sent (Rpc.ACTION_*), for the Diagnostics screen. */
    @Volatile private var lastRpcAction: Int? = null
    /** Wall-clock ms when the last RPC was sent. 0 = none yet. */
    @Volatile private var lastRpcTimeMs: Long = 0L
    /** Wall-clock ms when the last correlated RPC response arrived. 0 = none yet. */
    @Volatile private var lastResponseTimeMs: Long = 0L
    /** Request id of the last correlated response (null = none yet). */
    @Volatile private var lastResponseRequestId: String? = null

    /**
     * One-line transport state for the Diagnostics screen and verbose
     * timeout errors: session running? long-poll alive? last RPC (action +
     * age)? last response (age, correlated?)? last poll error?
     */
    fun diagnostics(): String {
        val now = System.currentTimeMillis()
        fun age(ms: Long) = if (ms <= 0) "never" else "${(now - ms) / 1000}s ago"
        val pollState = when {
            scope == null -> "not running"
            pollReady.isCompleted -> "alive"
            pollLastError != null -> "failed"
            else -> "connecting"
        }
        return "session=${if (scope != null) "running" else "stopped"}, " +
            "poll=$pollState, " +
            "lastRpc=${lastRpcAction?.let { "${Rpc.actionName(it)}@${age(lastRpcTimeMs)}" } ?: "none"}, " +
            "lastResponse=${lastResponseRequestId?.let { "correlated@${age(lastResponseTimeMs)}" } ?: "none"}, " +
            "lastPollError=${pollLastError ?: "none"}"
    }

    /** True while the session coroutine scope is up. */
    fun isSessionRunning(): Boolean = scope != null

    /** True once the long-poll channel has been established. */
    fun isPollAlive(): Boolean = scope != null && pollReady.isCompleted

    /** Last long-poll failure detail, if any. */
    fun lastPollError(): String? = pollLastError

    /** Last RPC action sent (Rpc.ACTION_*), or null if none. */
    fun lastRpc(): Pair<Int, Long>? = lastRpcAction?.let { it to lastRpcTimeMs }

    /** Last correlated response (request id + arrival time), or null if none. */
    fun lastResponse(): Pair<String, Long>? =
        lastResponseRequestId?.let { it to lastResponseTimeMs }

    /**
     * Injects auth state without persisting (pairing handshake: the provisional
     * session lives in memory until CLIENT_FINISHED succeeds). Null clears it.
     */
    fun injectAuth(a: AuthData?) {
        auth = a
        mobile = a?.let { AuthStore.decodeDevice(it.mobileB64) } ?: DeviceInfo()
    }

    val isPaired: Boolean get() = store.load()?.hasToken == true

    fun currentAuth(): AuthData? = auth ?: store.load()?.also {
        auth = it
        mobile = AuthStore.decodeDevice(it.mobileB64)
    }

    /** Starts the long-poll + ack loops. No-op if already running or not paired. */
    fun start(): Boolean {
        if (scope != null) return true
        val a = currentAuth() ?: return false
        if (!a.hasToken) return false
        // Cookie-authenticated sessions use the Gaia endpoints, like the
        // reference when HasCookies(). (QR pairing never stores cookies, so
        // its path is unchanged.)
        if (gaiaCookies == null) gaiaCookies = store.loadCookies()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { pollLoop() }
        s.launch { ackLoop() }
        return true
    }

    fun stop() {
        scope?.cancel()
        scope = null
        gaiaCookies = null
    }

    // ---------- high-level operations ----------

    suspend fun listConversations(count: Long = 50): List<RcsConversation> {
        val inner = rpc(Rpc.ACTION_LIST_CONVERSATIONS, encodeListConversationsRequest(count))
        return decodeConversationsResponse(inner)
    }

    suspend fun listMessages(
        conversationId: String,
        count: Long = 50,
        participants: List<RcsParticipant> = emptyList(),
    ): List<RcsMessage> {
        val inner = rpc(Rpc.ACTION_LIST_MESSAGES, encodeListMessagesRequest(conversationId, count))
        return decodeMessagesResponse(inner, participants)
    }

    /**
     * Sends a text message. Returns the tmpID used for echo correlation.
     * The caller may supply [tmpId] (e.g. the optimistic bubble's id) so the
     * server echo can be matched; otherwise one is generated. Throws on
     * transport failure.
     */
    suspend fun sendText(conversationId: String, text: String, tmpId: String = UUID.randomUUID().toString()): String {
        val inner = rpc(
            Rpc.ACTION_SEND_MESSAGE,
            encodeSendMessageRequest(conversationId, tmpId, text, forceRcs = true),
        )
        val status = decodeSendMessageStatus(inner)
        if (status != 1) throw RcsException("SendMessage status=$status")
        return tmpId
    }

    /**
     * Resolves a phone number to a conversation, creating it if needed.
     * Cached per session.
     */
    private val convCache = ConcurrentHashMap<String, String>()

    suspend fun conversationFor(number: String): String {
        convCache[number]?.let { return it }
        val inner = rpc(
            Rpc.ACTION_GET_OR_CREATE_CONVERSATION,
            encodeGetOrCreateConversation(number),
        )
        val id = decodeGetOrCreateConversation(inner)
            ?: throw RcsException("GetOrCreateConversation: no conversation id")
        convCache[number] = id
        return id
    }

    suspend fun markRead(conversationId: String, messageId: String) {
        rpc(Rpc.ACTION_MESSAGE_READ, encodeMessageReadRequest(conversationId, messageId))
    }

    // ---------- RPC plumbing ----------

    /**
     * Sends one action RPC and suspends until the response arrives via the
     * long-poll (correlated by requestID) or 60s elapse. Returns the inner
     * pblite response string.
     */
    /**
     * Sends one encrypted RPC and awaits the correlated response as raw
     * bytes.
     *
     * The sealed inner payload is BINARY protobuf (the reference
     * proto.Marshals the request message before encrypting); the decrypted
     * response bytes are returned raw for the caller to decode with
     * ProtoCodec. Timeouts carry live session diagnostics so a failure on
     * Garrett's device says exactly what was (not) happening.
     */
    private suspend fun rpc(action: Int, payloadProto: ByteArray): ByteArray {
        val a = currentAuth() ?: throw RcsException("not paired")
        val requestId = UUID.randomUUID().toString()
        val sealed = RcsCrypto.seal(a.aesKey, a.hmacKey, payloadProto)
        val rpcData = encodeOutgoingRpcData(requestId, action, sealed, a.sessionId)
        val body = buildOutgoingRpcPblite(mobile, requestId, rpcData, a.tachyonToken, a.sessionId)
        val waiter = CompletableDeferred<ByteArray>()
        waiters[requestId] = waiter
        lastRpcAction = action
        lastRpcTimeMs = System.currentTimeMillis()
        try {
            // Cookie-authenticated sessions go to the Gaia host (reference:
            // makeProtobufHTTPRequest picks the Google host when HasCookies).
            val cookies = gaiaCookies
            val res = if (cookies != null) {
                postRpcCancellable(
                    rpcClient, RelayApi.PATH_SEND_MESSAGE_GAIA,
                    RelayApi.signInGaiaHeaders(cookies), body.toByteArray(),
                )
            } else {
                postRpc(rpcClient, RelayApi.PATH_SEND_MESSAGE, RelayApi.CT_PBLITE, body.toByteArray())
            }
            if (!res.isOk()) throw RcsException("uplink HTTP ${res.code}: ${res.text.take(200)}")
            try {
                return withTimeout(60_000) { waiter.await() }
            } catch (e: TimeoutCancellationException) {
                throw TimeoutCancellationException(
                    "Timed out waiting for 60000 ms (${diagnostics()})",
                    e,
                )
            }
        } finally {
            waiters.remove(requestId)
        }
    }

    /**
     * Sends one UNENCRYPTED Gaia pairing message (CLIENT_INIT / CLIENT_FINISHED)
     * and awaits the correlated response bytes.
     *
     * Mirrors the reference sendGaiaPairingMessage: GaiaPairingRequestContainer
     * bytes go in unencryptedProtoData, the message type is caller-chosen
     * (GAIA_2=20 for init, BUGLE=2 for finish), TTL is the pairing TTL, and
     * destRegIds addresses the chosen phone. Starts the Gaia-mode long-poll
     * (cookie-authenticated) if needed and waits for the channel — the
     * reference waits for long-poll connect before CLIENT_INIT.
     *
     * Returns the raw GaiaPairingResponseContainer bytes. Throws RcsException
     * (or PairingException callers map) on transport failure / timeout.
     */
    suspend fun sendGaia(
        action: Int,
        container: ByteArray,
        messageType: Int,
        destRegIds: List<String>,
        cookies: String,
        timeoutMs: Long = 30_000,
    ): ByteArray {
        val a = currentAuth() ?: throw RcsException("not paired")
        gaiaCookies = cookies
        if (scope == null) {
            pollReady = CompletableDeferred()
            pollLastError = null
            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            s.launch { pollLoop() }
            s.launch { ackLoop() }
        }
        try {
            withTimeout(15_000) { pollReady.await() }
        } catch (e: TimeoutCancellationException) {
            val detail = pollLastError ?: "no poll attempt completed"
            Log.w(TAG, "GaiaPairing sendGaia: long-poll channel never established ($detail)")
            throw RcsException("long-poll channel never established ($detail)")
        }
        val requestId = UUID.randomUUID().toString()
        val rpcData = encodeOutgoingRpcDataUnencrypted(requestId, action, container, a.sessionId)
        val body = buildGaiaOutgoingPblite(
            mobile, requestId, rpcData, a.tachyonToken, a.sessionId,
            GAIA_TTL_MICROS, messageType, destRegIds,
        )
        val waiter = CompletableDeferred<ByteArray>()
        waiters[requestId] = waiter
        try {
            val headers = RelayApi.signInGaiaHeaders(cookies)
            val res = postRpcCancellable(
                rpcClient, RelayApi.PATH_SEND_MESSAGE_GAIA, headers,
                body.toByteArray(Charsets.UTF_8),
            )
            if (!res.isOk()) throw RcsException("gaia uplink HTTP ${res.code}: ${res.text.take(200)}")
            Log.d(TAG, "GaiaPairing send action=$action messageType=$messageType " +
                    "container=${container.size}B destRegIds=$destRegIds")
            return withTimeout(timeoutMs) { waiter.await() }
        } finally {
            waiters.remove(requestId)
        }
    }

    // ---------- long-poll loop ----------

    private suspend fun pollLoop() {
        var backoffMs = 500L
        while (scope?.isActive == true) {
            try {
                val a = currentAuth() ?: throw RcsException("pairing lost")
                val body = encodeReceiveMessagesRequest(UUID.randomUUID().toString(), a.tachyonToken)
                // Gaia mode: cookie-authenticated endpoints on the Gaia host,
                // like the reference when HasCookies().
                val cookies = gaiaCookies
                // The call is executed directly (not via the one-shot
                // postRpc helper): a long-poll is a HANGING stream, so the
                // channel counts as ESTABLISHED the moment HTTP 2xx headers
                // arrive — like the reference's onFirstConnect ("Long polling
                // opened"), which fires before the stream is read. Waiting
                // for the full body (resp.body.bytes()) would block for the
                // whole 60s+ poll window, so pollReady could never satisfy
                // sendGaia's 15s gate (diagnosed 2026-10-18: "long-poll
                // channel never established" on a healthy channel).
                val req = if (cookies != null) {
                    Request.Builder()
                        .url(RelayApi.PATH_RECEIVE_MESSAGES_GAIA)
                        .headers(RelayApi.signInGaiaHeaders(cookies))
                        .post(body.toByteArray().toRequestBody(RelayApi.CT_PBLITE.toMediaType()))
                        .build()
                } else {
                    Request.Builder()
                        .url(RelayApi.PATH_RECEIVE_MESSAGES)
                        .headers(RelayApi.headers(RelayApi.CT_PBLITE))
                        .post(body.toByteArray().toRequestBody(RelayApi.CT_PBLITE.toMediaType()))
                        .build()
                }
                pollClient.newCall(req).execute().use { resp ->
                    backoffMs = 500L
                    if (!resp.isSuccessful) {
                        val snippet = runCatching { resp.body?.string() }.getOrNull()?.take(200)
                        pollLastError = "long-poll HTTP ${resp.code}" +
                                (if (!snippet.isNullOrBlank()) ": $snippet" else "")
                        Log.w(TAG, "GaiaPairing $pollLastError")
                        if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                            _events.emit(SessionEvent.AuthExpired)
                            throw RcsException("auth expired (HTTP ${resp.code}) — re-pair required")
                        }
                        throw RcsException("long-poll HTTP ${resp.code}")
                    }
                    // 2xx headers arrived: the channel is OPEN. Signal
                    // readiness BEFORE consuming the hanging stream.
                    if (!pollReady.isCompleted) pollReady.complete(Unit)
                    // Read the hanging stream INCREMENTALLY (see
                    // readPollStream): the server flushes one pblite
                    // LongPollingPayload per chunk and holds the poll open
                    // ~60s. Payloads must dispatch as they arrive —
                    // SERVER_INIT correlation depends on it.
                    readPollStream(resp)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // HTTP failures above already recorded "long-poll HTTP <code>"
                // (+ body snippet) in pollLastError; anything else records
                // its own message here so sendGaia can surface the cause.
                if (e !is RcsException) {
                    pollLastError = "long-poll error: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "GaiaPairing $pollLastError")
                }
                _events.emit(SessionEvent.PollError(e.message ?: "poll failed"))
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 60_000)
            }
        }
    }

    /**
     * Incrementally reads a long-poll response stream, dispatching each
     * pblite payload the moment it arrives. Framing (reference readLongPoll):
     * the stream opens with "[[", then a comma-separated series of
     * LongPollingPayload JSON values, then "]]" + EOF (the server holds each
     * poll ~60s). Blocking reads run on Dispatchers.IO (this loop's
     * dispatcher); scope cancellation interrupts the read and the outer loop
     * exits.
     */
    private suspend fun readPollStream(resp: okhttp3.Response) {
        val reader = resp.body?.charStream()?.buffered(8192) ?: return
        val buf = StringBuilder()
        val chunk = CharArray(32768)
        var opened = false
        try {
            while (true) {
                val n = reader.read(chunk)
                if (n < 0) break // EOF: server closed the poll window
                buf.append(chunk, 0, n)
                if (!opened) {
                    var i = 0
                    while (i < buf.length && buf[i].isWhitespace()) i++
                    if (buf.length - i < 2) continue // need more bytes
                    if (buf[i] != '[' || buf[i + 1] != '[') {
                        Log.w(TAG, "GaiaPairing long-poll: unexpected stream opening")
                        return
                    }
                    buf.delete(0, i + 2)
                    opened = true
                }
                while (drainOnePayload(buf)) { /* keep draining */ }
                if (buf.startsWith("]]")) return // stream end marker
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.InterruptedIOException) {
            // Scope cancelled mid-read — the outer loop observes it via
            // isActive/delay and exits; no error to record.
        } catch (e: Exception) {
            Log.w(TAG, "GaiaPairing long-poll stream ended: ${e.message}")
        }
    }

    /**
     * Extracts and dispatches one complete payload from [buf] if present.
     * Returns true when a payload was dispatched (caller keeps draining).
     */
    private suspend fun drainOnePayload(buf: StringBuilder): Boolean {
        var i = 0
        while (i < buf.length && (buf[i] == ',' || buf[i].isWhitespace())) i++
        if (i > 0) buf.delete(0, i)
        if (buf.isEmpty() || buf.startsWith("]]")) return false
        val json = extractCompleteJson(buf) ?: return false
        runCatching {
            // Reuse the exact one-shot decode path on a single payload.
            for (payload in decodeLongPollBody("[$json]")) handlePayload(payload)
        }.onFailure { e ->
            Log.w(TAG, "GaiaPairing poll payload decode failed: ${e.message}")
        }
        return true
    }

    /**
     * Extracts the first complete top-level JSON value from [buf] and removes
     * it. Bracket depth is tracked with string/escape awareness, so brackets
     * inside strings never confuse framing.
     */
    private fun extractCompleteJson(buf: StringBuilder): String? {
        var depth = 0
        var inString = false
        var escape = false
        var started = false
        for (idx in buf.indices) {
            val c = buf[idx]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> {
                    depth++
                    started = true
                }
                ']', '}' -> {
                    depth--
                    if (started && depth == 0) {
                        val value = buf.substring(0, idx + 1)
                        buf.delete(0, idx + 1)
                        return value
                    }
                }
            }
        }
        return null
    }

    private suspend fun handlePayload(payload: PollPayload) {
        val a = currentAuth() ?: return
        when (payload) {
            is PollPayload.Heartbeat, PollPayload.StartRead -> Unit
            is PollPayload.Ack -> Unit // server backlog marker; nothing to do client-side
            PollPayload.Unknown -> Unit
            is PollPayload.Data -> {
                val msg = payload.message
                when (msg.bugleRoute) {
                    Rpc.ROUTE_PAIR_EVENT -> {
                        val paired = decodeRpcPairData(msg.messageData)
                        if (paired == null) {
                            _events.emit(SessionEvent.Unpaired)
                        } else {
                            // Token rotation / re-pair: persist the fresh token.
                            _events.emit(SessionEvent.TokenRefreshed)
                        }
                    }
                    Rpc.ROUTE_DATA_EVENT -> {
                        // Queue for ack FIRST (acking is load-bearing).
                        ackQueue.trySend(msg.responseId to mobile)
                        val rpcData = decodeRpcMessageData(msg.messageData)
                        // Reference (session_handler.go receiveResponse): in
                        // Gaia/cookie mode the server emits unencrypted-only
                        // "weird messages" BEFORE real action responses. They
                        // must never satisfy an RPC waiter — their binary
                        // protobuf payload is not pblite JSON and would hit
                        // the JSON parser ("Couldn't open chat" bug, 2026-10-18).
                        // Pairing actions are exempt (unencrypted by design).
                        if (!isWeirdPreResponse(rpcData, hasCookies = gaiaCookies != null)) {
                            val innerBytes = rpcData.encryptedData?.let {
                                runCatching { RcsCrypto.open(a.aesKey, a.hmacKey, it) }.getOrNull()
                            } ?: rpcData.unencryptedData
                            if (innerBytes != null) {
                                // Correlate responses: normal RPC responses echo our
                                // requestID in IncomingRPCMessage.responseID; the Gaia
                                // pairing server echoes it in RPCMessageData.sessionID
                                // (documented assumption — log both keys so the device
                                // test shows which one fires).
                                val waiter = waiters.remove(msg.responseId)
                                    ?: waiters.remove(rpcData.sessionId)
                                if (waiter != null) {
                                    Log.d(TAG, "response correlated: responseId=${msg.responseId} " +
                                            "rpcSessionId=${rpcData.sessionId}")
                                    lastResponseRequestId = msg.responseId.ifEmpty { rpcData.sessionId }
                                    lastResponseTimeMs = System.currentTimeMillis()
                                    waiter.complete(innerBytes)
                                    return
                                }
                                // Otherwise: push event (UpdateEvents, binary protobuf).
                                val update = runCatching {
                                    decodeUpdateEvents(ProtoCodec.decode(innerBytes))
                                }.getOrNull()
                                when (update) {
                                    is UpdateEvent.Messages -> update.messages.forEach { _incoming.emit(it) }
                                    is UpdateEvent.Conversations -> _events.emit(SessionEvent.ConversationsChanged(update.conversations))
                                    is UpdateEvent.Typing -> _events.emit(SessionEvent.Typing(update.conversationId, update.typing))
                                    else -> Unit
                                }
                            }
                        }
                        // Weird pre-response messages (and undecryptable
                        // payloads) are safely ignored — the real response
                        // arrives next and correlates normally.
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---------- ack loop (decoupled; 5s ticker + threshold flush) ----------

    /**
     * Decoupled ack loop on a strict 5s ticker. (Behaviorally identical to
     * tickerFlow(5000); implemented with delay() to avoid the experimental
     * tickerFlow API.)
     */
    private suspend fun ackLoop() {
        val pending = mutableListOf<Pair<String, DeviceInfo>>()
        while (true) {
            delay(5000)
            // Drain whatever arrived since the last tick.
            while (true) {
                pending.add(ackQueue.tryReceive().getOrNull() ?: break)
            }
            if (pending.isNotEmpty()) {
                flushAcks(pending.toList())
                pending.clear()
            }
        }
    }

    /** Immediate flush path used when the queue grows (keeps latency low). */
    private suspend fun flushAcks(acks: List<Pair<String, DeviceInfo>>) {
        val a = currentAuth() ?: return
        try {
            val body = encodeAckMessagesRequest(UUID.randomUUID().toString(), a.tachyonToken, acks)
            val res = postRpc(rpcClient, RelayApi.PATH_ACK_MESSAGES, RelayApi.CT_PBLITE, body.toByteArray())
            if (!res.isOk()) _events.emit(SessionEvent.PollError("ack HTTP ${res.code}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(SessionEvent.PollError("ack failed: ${e.message}"))
        }
    }

    // ---------- token refresh (best-effort) ----------

    /**
     * Refreshes the Tachyon token when <1h remains. Best-effort: failures are
     * logged, not fatal. (The morning demo will not exercise this path.)
     */
    suspend fun maybeRefreshToken() {
        val a = currentAuth() ?: return
        if (System.currentTimeMillis() + 3600_000 < a.tachyonExpiryMs) return
        // Full RegisterRefresh wiring is a follow-up; the primitive (ECDSA sign
        // of "requestID:timestampMicros") is implemented in RcsCrypto.
        _events.emit(SessionEvent.PollError("token refresh not yet wired — re-pair if expired"))
    }
}

/** Minimal pblite for GetOrCreateConversation{2:[ContactNumber{2:number}]}. */
private fun encodeGetOrCreateConversation(number: String): ByteArray {
    val contactNumber = FieldVal.Msg(mapOf(2 to FieldVal.Str(number)))
    return ProtoCodec.encode(mapOf(2 to FieldVal.Rep(listOf(contactNumber))))
}

private fun decodeGetOrCreateConversation(inner: ByteArray): String? {
    // GetOrCreateConversationResponse{2: Conversation{1: conversationID}}
    val m = ProtoCodec.decode(inner)
    return m.firstMsg(2)?.firstStr(1)
}

sealed interface SessionEvent {
    data class PollError(val message: String) : SessionEvent
    data object AuthExpired : SessionEvent
    data object Unpaired : SessionEvent
    data object TokenRefreshed : SessionEvent
    data class ConversationsChanged(val conversations: List<RcsConversation>) : SessionEvent
    data class Typing(val conversationId: String, val typing: Boolean) : SessionEvent
}

class RcsException(message: String, cause: Throwable? = null) : Exception(message, cause)
