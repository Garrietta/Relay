package com.garrietta.relay.rcs.pairing

import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.net.postRpc
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.proto.BrowserDetails
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.proto.decodeRegisterPhoneRelayResponse
import com.garrietta.relay.rcs.proto.decodeRpcPairData
import com.garrietta.relay.rcs.proto.encodeAuthMessage
import com.garrietta.relay.rcs.proto.AuthMessage
import com.garrietta.relay.rcs.proto.encodeAuthenticationContainer
import com.garrietta.relay.rcs.proto.encodeBrowserDetails
import com.garrietta.relay.rcs.proto.encodeDevice
import com.garrietta.relay.rcs.proto.encodeEcdsaKeys
import com.garrietta.relay.rcs.proto.encodeKeyData
import com.garrietta.relay.rcs.proto.encodeUrlData
import com.garrietta.relay.rcs.proto.decodePollPayload
import com.garrietta.relay.rcs.proto.PollPayload
import com.garrietta.relay.rcs.proto.Rpc
import com.garrietta.relay.rcs.session.AuthData
import com.garrietta.relay.rcs.session.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.UUID

/**
 * QR pairing — clean-room implementation of the Messages-for-web pairing flow.
 *
 * 1. Generate request-crypto keys (AES/HMAC) + ECDSA refresh keypair.
 * 2. POST binary AuthenticationContainer to RegisterPhoneRelay.
 * 3. Build the QR content: base64(URLData{pairingKey, AESKey, HMACKey})
 *    appended to https://support.google.com/messages/?p=web_computer#?c=
 * 4. Long-poll ReceiveMessages until the phone answers with a PairEvent.
 * 5. Persist AuthData (token, devices, keys).
 *
 * The Gaia (emoji) pairing path is a separate flow and is NOT implemented in
 * this MVP — see the pairing screen, which reserves a slot for it.
 */
class QrPairing(private val client: OkHttpClient = RelayApi.client()) {

    data class BegunPairing(
        val qrContent: String,
        val pairingId: String,
        val sessionId: String,
        val aesKey: ByteArray,
        val hmacKey: ByteArray,
        val refreshPrivateKey: ByteArray,
        val refreshPublicKey: ByteArray,
        val pairingKey: ByteArray,
        val browser: DeviceInfo,
        val initialToken: ByteArray,
        val initialTtlSeconds: Long,
    )

    /**
     * Step 1–3: register the relay and produce the QR content string.
     * Throws on non-2xx or unparseable responses.
     */
    suspend fun begin(): BegunPairing = withContext(Dispatchers.IO) {
        val aesKey = RcsCrypto.generateAesKey()
        val hmacKey = RcsCrypto.generateHmacKey()
        val ecKeys = RcsCrypto.generateEcdsaKeyPair()
        val pairingId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()

        val authProto = encodeAuthMessage(
            AuthMessage(requestId = UUID.randomUUID().toString(), network = "Bugle"),
        )
        val browserProto = encodeBrowserDetails(
            BrowserDetails(
                userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            ),
        )
        val container = encodeAuthenticationContainer(
            authMessageProto = authProto,
            browserDetailsProto = browserProto,
            keyDataProto = encodeKeyData(encodeEcdsaKeys(ecKeys.public.encoded)),
        )

        val res = postRpc(client, RelayApi.PATH_REGISTER_PHONE_RELAY, RelayApi.CT_PROTO, container)
        if (!res.isOk()) throw PairingException("RegisterPhoneRelay HTTP ${res.code}: ${res.text.take(200)}")
        val result = try {
            decodeRegisterPhoneRelayResponse(res.body)
        } catch (e: Exception) {
            throw PairingException("RegisterPhoneRelay: bad response: ${e.message}")
        }
        if (result.pairingKey.isEmpty()) throw PairingException("RegisterPhoneRelay: empty pairingKey")

        val qrPayload = Base64.getEncoder().encodeToString(
            encodeUrlData(result.pairingKey, aesKey, hmacKey),
        )
        BegunPairing(
            qrContent = "https://support.google.com/messages/?p=web_computer#?c=$qrPayload",
            pairingId = pairingId,
            sessionId = sessionId,
            aesKey = aesKey,
            hmacKey = hmacKey,
            refreshPrivateKey = ecKeys.private.encoded,
            refreshPublicKey = ecKeys.public.encoded,
            pairingKey = result.pairingKey,
            browser = result.browser,
            initialToken = result.tokenData.token,
            initialTtlSeconds = result.tokenData.ttlSeconds,
        )
    }

    /**
     * Step 4: poll the long-poll endpoint until a PairEvent arrives or
     * [maxAttempts] elapse. Returns the paired data on success.
     *
     * Each poll is one ReceiveMessages long-poll round; the server holds it
     * open until an event (or timeout) and returns a JSON array of payloads.
     */
    suspend fun awaitPairEvent(
        begun: BegunPairing,
        onPoll: suspend () -> Unit = {},
        maxAttempts: Int = 40,
    ): com.garrietta.relay.rcs.proto.PairedData = withContext(Dispatchers.IO) {
        val lpClient = RelayApi.client(longPoll = true)
        repeat(maxAttempts) {
            onPoll()
            val body = com.garrietta.relay.rcs.proto.encodeReceiveMessagesRequest(
                UUID.randomUUID().toString(), begun.initialToken,
            )
            val res = postRpc(lpClient, RelayApi.PATH_RECEIVE_MESSAGES, RelayApi.CT_PBLITE, body.toByteArray())
            if (!res.isOk()) throw PairingException("ReceiveMessages HTTP ${res.code}")
            for (payload in com.garrietta.relay.rcs.proto.decodeLongPollBody(res.text)) {
                val data = payload as? PollPayload.Data ?: continue
                if (data.message.bugleRoute != Rpc.ROUTE_PAIR_EVENT) continue
                val paired = decodeRpcPairData(data.message.messageData)
                    ?: throw PairingException("Pairing was revoked by the phone")
                return@withContext paired
            }
            // No pair event in this round — immediately re-poll (server already timed out).
        }
        throw PairingException("Timed out waiting for the phone to scan the QR")
    }

    /** Step 5: persist the completed pairing. Callers should sleep ~2s first (phone-side save). */
    fun persist(
        store: AuthStore,
        begun: BegunPairing,
        paired: com.garrietta.relay.rcs.proto.PairedData,
    ) {
        val ttlMs = (if (paired.tokenData.ttlSeconds > 0) paired.tokenData.ttlSeconds else 24 * 3600) * 1000L
        store.save(
            AuthData(
                aesKey = begun.aesKey,
                hmacKey = begun.hmacKey,
                refreshPrivateKey = begun.refreshPrivateKey,
                refreshPublicKey = begun.refreshPublicKey,
                tachyonToken = paired.tokenData.token,
                tachyonExpiryMs = System.currentTimeMillis() + ttlMs,
                sessionId = begun.sessionId,
                pairingId = begun.pairingId,
                mobileB64 = Base64.getEncoder().encodeToString(encodeDevice(paired.mobile)),
                browserB64 = Base64.getEncoder().encodeToString(encodeDevice(paired.browser)),
            ),
        )
    }
}

class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)
