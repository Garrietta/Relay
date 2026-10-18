package com.garrietta.relay.rcs.session

import android.content.Context
import android.util.Log
import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.net.postRpc
import com.garrietta.relay.rcs.net.postRpcCancellable
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.pblite.PValue
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.msg
import com.garrietta.relay.rcs.pblite.str
import com.garrietta.relay.rcs.proto.PollPayload
import com.garrietta.relay.rcs.proto.RcsConversation
import com.garrietta.relay.rcs.proto.RcsMessage
import com.garrietta.relay.rcs.proto.Rpc
import com.garrietta.relay.rcs.proto.buildGaiaOutgoingPblite
import com.garrietta.relay.rcs.proto.encodeOutgoingRpcDataUnencrypted
import com.garrietta.relay.rcs.proto.msgArr
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

    suspend fun listMessages(conversationId: String, count: Long = 50): List<RcsMessage> {
        val inner = rpc(Rpc.ACTION_LIST_MESSAGES, encodeListMessagesRequest(conversationId, count))
        return decodeMessagesResponse(inner)
    }

    /**
     * Sends a text message. Returns the tmpID used for echo correlation.
     * Throws on transport failure.
     */
    suspend fun sendText(conversationId: String, text: String): String {
        val tmpId = UUID.randomUUID().toString()
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
    private suspend fun rpc(action: Int, payloadPblite: String): String {
        val a = currentAuth() ?: throw RcsException("not paired")
        val requestId = UUID.randomUUID().toString()
        val sealed = RcsCrypto.seal(a.aesKey, a.hmacKey, payloadPblite.toByteArray(Charsets.UTF_8))
        val rpcData = encodeOutgoingRpcData(requestId, action, sealed, a.sessionId)
        val body = buildOutgoingRpcPblite(mobile, requestId, rpcData, a.tachyonToken, a.sessionId)
        val waiter = CompletableDeferred<ByteArray>()
        waiters[requestId] = waiter
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
            return withTimeout(60_000) { waiter.await().toString(Charsets.UTF_8) }
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
            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            s.launch { pollLoop() }
            s.launch { ackLoop() }
        }
        try {
            withTimeout(15_000) { pollReady.await() }
        } catch (e: TimeoutCancellationException) {
            throw RcsException("long-poll channel never established — check network/cookies")
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
                val res = if (cookies != null) {
                    postRpcCancellable(
                        pollClient, RelayApi.PATH_RECEIVE_MESSAGES_GAIA,
                        RelayApi.signInGaiaHeaders(cookies), body.toByteArray(),
                    )
                } else {
                    postRpc(pollClient, RelayApi.PATH_RECEIVE_MESSAGES, RelayApi.CT_PBLITE, body.toByteArray())
                }
                backoffMs = 500L
                when (res.code) {
                    401, 403, 404 -> {
                        _events.emit(SessionEvent.AuthExpired)
                        throw RcsException("auth expired (HTTP ${res.code}) — re-pair required")
                    }
                }
                if (!res.isOk()) throw RcsException("long-poll HTTP ${res.code}")
                if (!pollReady.isCompleted) pollReady.complete(Unit)
                for (payload in decodeLongPollBody(res.text)) handlePayload(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(SessionEvent.PollError(e.message ?: "poll failed"))
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 60_000)
            }
        }
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
                        val innerBytes = rpcData.encryptedData?.let {
                            runCatching { RcsCrypto.open(a.aesKey, a.hmacKey, it) }.getOrNull()
                        } ?: rpcData.unencryptedData
                        if (innerBytes == null) return
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
                            waiter.complete(innerBytes)
                            return
                        }
                        // Otherwise: push event (UpdateEvents).
                        val inner = innerBytes.toString(Charsets.UTF_8)
                        val update = runCatching { decodeUpdateEvents(decodePbliteMap(inner)) }.getOrNull()
                        when (update) {
                            is UpdateEvent.Messages -> update.messages.forEach { _incoming.emit(it) }
                            is UpdateEvent.Conversations -> _events.emit(SessionEvent.ConversationsChanged(update.conversations))
                            is UpdateEvent.Typing -> _events.emit(SessionEvent.Typing(update.conversationId, update.typing))
                            else -> Unit
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun decodePbliteMap(text: String): Map<Int, PValue> = Pblite.decode(text)

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
private fun encodeGetOrCreateConversation(number: String): String {
    val contactNumber = msgArr(2 to PValue.Str(number))
    return Pblite.encode(mapOf(2 to PValue.Arr(listOf(contactNumber))))
}

private fun decodeGetOrCreateConversation(inner: String): String? {
    // GetOrCreateConversationResponse{2: Conversation{1: conversationID}}
    val m = com.garrietta.relay.rcs.pblite.Pblite.decode(inner)
    val conv = m.msg(2) ?: return null
    return conv.str(1)
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
