package com.garrietta.relay.rcs.pairing

import android.util.Log
import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.net.RpcResult
import com.garrietta.relay.rcs.net.SapisidHash
import com.garrietta.relay.rcs.net.postRpcCancellable
import com.garrietta.relay.rcs.pblite.PValue
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.asList
import com.garrietta.relay.rcs.pblite.asMsg
import com.garrietta.relay.rcs.pblite.bytes
import com.garrietta.relay.rcs.pblite.num
import com.garrietta.relay.rcs.pblite.str
import com.garrietta.relay.rcs.proto.ProtoCodec
import com.garrietta.relay.rcs.proto.FieldVal
import com.garrietta.relay.rcs.session.AuthData
import com.garrietta.relay.rcs.session.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Google-account ("Gaia") pairing — the emoji-verification flow.
 *
 * Clean-room implementation from the protocol spec
 * (~/workspace/relay-work/rcs-protocol-spec.md §1b). Mirrors
 * messages.google.com "sign in with Google account + match the emoji".
 *
 * Flow:
 *  1. [begin]: POST Registration/SignInGaia (pblite) with Google session
 *     cookies + deviceID + ECDSA refresh public key → TokenData + device
 *     list. The primary phone is picked (newest lastSeen).
 *  2. [prepareUkey2]: ECDH P-256 keypair; CLIENT_INIT via
 *     ActionType_CREATE_GAIA_PAIRING_CLIENT_INIT (unencrypted).
 *  3. [processServerInit]: validate SERVER_INIT, ECDH → sharedSecret =
 *     sha256(DH); ukeyV1Auth = HKDF-SHA256(sharedSecret, salt="UKEY2 v1
 *     auth", info=initPayload‖serverInitBytes); emoji index =
 *     uint32be(ukeyV1Auth) % emojiCount. The user confirms the emoji
 *     matches on the phone.
 *  4. [finish]: send CLIENT_FINISHED with ToS; derive AES/HMAC ("Ditto"
 *     v1); persist PairingID + DestPhoneID.
 *
 * THEORETICAL (not yet validated against Google's servers): the protobuf
 * field numbers below are best-effort from the spec's behavioral
 * description. The WebView cookie acquisition and the emoji UI are solid;
 * the handshake bytes are the part the morning sign-in will validate.
 */
class GaiaPairing(private val client: OkHttpClient = RelayApi.client()) {

    data class GaiaDevice(
        val deviceId: String,
        val isPrimary: Boolean,
        val lastSeenMs: Long,
    )

    data class BegunGaia(
        val sessionUuid: String,
        val deviceId: String,
        val cookies: String,
        val refreshPublicKey: ByteArray,
        val tokenData: com.garrietta.relay.rcs.proto.TokenData,
        val devices: List<GaiaDevice>,
        val chosenDevice: GaiaDevice,
    )

    data class Ukey2Handshake(
        val ecdhPrivate: java.security.PrivateKey,
        val ecdhPublic: java.security.PublicKey,
        val clientRandom: ByteArray,
        val initPayload: ByteArray,
        val pairingAttemptId: String,
    )

    /**
     * Step 1: SignInGaia. Requires Google session cookies (from the WebView
     * sign-in). Returns the token + device list with the primary phone picked.
     */
    suspend fun begin(cookies: String): BegunGaia = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) throw PairingException("Gaia pairing needs Google session cookies")
        val sessionUuid = UUID.randomUUID().toString()
        val deviceId = "messages-web-$sessionUuid"
        val ecKeys = RcsCrypto.generateEcdsaKeyPair()

        // SignInGaia request (pblite). Field layout is best-effort per spec.
        val request = Pblite.encode(
            mapOf(
                1 to PValue.Str(deviceId),
                2 to PValue.Str(Base64.getEncoder().encodeToString(ecKeys.public.encoded)),
            )
        )
        // SignInGaia carries the complete browser-faithful header set
        // (RelayApi.signInGaiaHeaders): the relay headers (sec-ch-ua with
        // OUR WebView's Chrome version, x-goog-api-key, origin, referer,
        // sec-fetch-*, accept-language) plus the cookie-auth pair (Cookie
        // + Authorization: SAPISIDHASH). Google's cookie-authenticated
        // endpoints reject requests that carry cookies but no proof of
        // origin — cookies alone get an HTTP 401 (diagnosed 2026-10-18).
        val headers = RelayApi.signInGaiaHeaders(cookies)
        if (SapisidHash.extractSapisid(cookies) == null) {
            Log.w(TAG, "SignInGaia: no SAPISID cookie — Cookie header goes without SAPISIDHASH")
        }
        // Gaia mode host per mautrix (pkg/libgm/util/paths.go):
        // instantmessaging-pa.clients6.google.com — NOT bare
        // clients6.google.com, which 404s on the Registration path
        // (diagnosed 2026-10-18 from a device 404; the live web client
        // config at messages.google.com/web/config publishes the same host).
        val url = RelayApi.PATH_SIGN_IN_GAIA
        val body = request.toByteArray()
        // Header NAMES only — values (Cookie, SAPISIDHASH) are secrets and
        // are never logged.
        Log.d(TAG, "SignInGaia POST $url body=${body.size}B deviceId=$deviceId " +
                "headers=${headers.names()} cookies=[${cookieNames(cookies)}]")
        val res = postSignInGaiaWithRetry(client, url, headers, body)

        val (tokenData, devices) = decodeSignInGaiaResponse(res.text)
        val chosen = devices.filter { it.isPrimary }.maxByOrNull { it.lastSeenMs }
            ?: devices.maxByOrNull { it.lastSeenMs }
            ?: throw PairingException("SignInGaia: no devices on the account")
        BegunGaia(
            sessionUuid = sessionUuid,
            deviceId = deviceId,
            cookies = cookies,
            refreshPublicKey = ecKeys.public.encoded,
            tokenData = tokenData,
            devices = devices,
            chosenDevice = chosen,
        )
    }

    /**
     * SignInGaia with bounded retries: up to 3 attempts, exponential
     * backoff (1s, 2s). A 4xx is deterministic (wrong endpoint, bad
     * session) — fail fast, never retry. 5xx / network failures retry.
     * The raw HTML error page goes to logcat only; the exception carries a
     * one-line message for the UI (never raw HTML).
     */
    private suspend fun postSignInGaiaWithRetry(
        client: OkHttpClient,
        url: String,
        headers: Headers,
        body: ByteArray,
    ): RpcResult {
        var last: RpcResult? = null
        var backoffMs = 1000L
        repeat(3) { attempt ->
            val res = postRpcCancellable(client, url, headers, body)
            if (res.isOk()) return res
            if (res.code in 400..499) {
                Log.w(TAG, "SignInGaia HTTP ${res.code} (deterministic, not retrying): ${res.text.take(500)}")
                throw PairingException(cleanHttpError("SignInGaia", res.code))
            }
            Log.w(TAG, "SignInGaia HTTP ${res.code} (attempt ${attempt + 1}/3), backing off ${backoffMs}ms: ${res.text.take(200)}")
            last = res
            if (attempt < 2) delay(backoffMs)
            backoffMs *= 2
        }
        val code = last!!.code
        Log.w(TAG, "SignInGaia HTTP $code after 3 attempts: ${last!!.text.take(500)}")
        throw PairingException(cleanHttpError("SignInGaia", code))
    }

    private fun cleanHttpError(op: String, code: Int): String = when (code) {
        404 -> "$op: Google didn't recognize the pairing request (404)"
        401, 403 -> "$op: Google rejected the session (HTTP $code) — sign in again"
        else -> "$op failed (HTTP $code)"
    }

    /** Cookie NAMES only — values are secrets and are never logged. */
    private fun cookieNames(cookies: String): List<String> =
        cookies.split(";").mapNotNull { it.substringBefore("=").trim().takeIf { it.isNotEmpty() } }

    /**
     * Step 2: prepare the UKEY2 CLIENT_INIT payload.
     * Ukey2Message{message_type=CLIENT_INIT, message_data=Ukey2ClientInit{
     * version:1, random:32B, cipher_commitments:[{P256_SHA512,
     * commitment:sha512(finishPayload)}], next_protocol:"AES_256_CBC-HMAC_SHA256"}}.
     */
    fun prepareUkey2(): Ukey2Handshake {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val random = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val pairingAttemptId = UUID.randomUUID().toString()

        // finishPayload is what we'll send in CLIENT_FINISHED; its sha512
        // goes into the cipher commitment. (Theoretical layout.)
        val finishPayload = ProtoCodec.encode(
            mapOf(1 to FieldVal.Str("CLIENT_FINISHED"))
        )
        val sha512 = MessageDigest.getInstance("SHA-512").digest(finishPayload)
        val commitment = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(1L), // P256_SHA512
                2 to FieldVal.Bytes(sha512),
            )
        )
        val clientInit = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(1L), // version
                2 to FieldVal.Bytes(random),
                3 to FieldVal.Rep(listOf(FieldVal.Bytes(commitment))),
                4 to FieldVal.Str("AES_256_CBC-HMAC_SHA256"),
            )
        )
        val initPayload = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(1L), // CLIENT_INIT
                2 to FieldVal.Bytes(clientInit),
            )
        )
        return Ukey2Handshake(
            ecdhPrivate = kp.private,
            ecdhPublic = kp.public,
            clientRandom = random,
            initPayload = initPayload,
            pairingAttemptId = pairingAttemptId,
        )
    }

    /**
     * Step 3: process SERVER_INIT → derive the emoji index the user must
     * match on the phone. Returns (emojiIndex, serverPublicKey).
     *
     * ukeyV1Auth = HKDF-SHA256(sha256(DH), salt="UKEY2 v1 auth",
     *                          info=initPayload‖serverInitBytes)
     * emojiIndex = uint32be(ukeyV1Auth[0..4]) % emojiCount
     */
    fun processServerInit(
        handshake: Ukey2Handshake,
        serverInitBytes: ByteArray,
        serverPublicKey: java.security.PublicKey,
        emojiCount: Int,
    ): Int {
        val sharedSecret = RcsCrypto.ecdhSha256(handshake.ecdhPrivate, serverPublicKey)
        val info = handshake.initPayload + serverInitBytes
        val ukeyV1Auth = RcsCrypto.hkdfSha256(
            ikm = sharedSecret,
            salt = "UKEY2 v1 auth".toByteArray(),
            info = info,
            length = 32,
        )
        val index = ((ukeyV1Auth[0].toInt() and 0xFF) shl 24) or
                ((ukeyV1Auth[1].toInt() and 0xFF) shl 16) or
                ((ukeyV1Auth[2].toInt() and 0xFF) shl 8) or
                (ukeyV1Auth[3].toInt() and 0xFF)
        return Math.floorMod(index, emojiCount)
    }

    /**
     * Step 4: finish pairing. Sends CLIENT_FINISHED with the ToS string and
     * derives the session keys ("Ditto" v1):
     *   hash = sort-hash(clientKey, serverKey); 32-byte encryptionKeyInfo
     *   AES  = HKDF(hash, "Ditto salt 1", "Ditto info 1")
     *   HMAC = HKDF(hash, "Ditto salt 2", "Ditto info 2")
     */
    suspend fun finish(
        begun: BegunGaia,
        handshake: Ukey2Handshake,
        serverPublicKey: java.security.PublicKey,
    ): AuthData = withContext(Dispatchers.IO) {
        // Derive Ditto v1 keys (theoretical per spec).
        val sharedSecret = RcsCrypto.ecdhSha256(handshake.ecdhPrivate, serverPublicKey)
        val clientKey = RcsCrypto.uncompressedPublicKey(handshake.ecdhPublic)
        val serverKey = RcsCrypto.uncompressedPublicKey(serverPublicKey)
        val (first, second) = if (clientKey.lexicographicallyBefore(serverKey)) {
            clientKey to serverKey
        } else {
            serverKey to clientKey
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(first + second)
        // 32-byte fixed encryptionKeyInfo (theoretical; spec notes it exists).
        val encryptionKeyInfo = ByteArray(32) // zeroed placeholder
        val keyMaterial = RcsCrypto.hkdfSha256(hash, encryptionKeyInfo, ByteArray(0), 64)
        val aesKey = keyMaterial.copyOfRange(0, 32)
        val hmacKey = RcsCrypto.hkdfSha256(hash, "Ditto salt 1".toByteArray(), "Ditto info 1".toByteArray(), 32)
        val hmacKey2 = RcsCrypto.hkdfSha256(hash, "Ditto salt 2".toByteArray(), "Ditto info 2".toByteArray(), 32)

        // CLIENT_FINISHED with ToS (theoretical RPC shape).
        val finishPayload = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Str(handshake.pairingAttemptId),
                2 to FieldVal.Str(TOS_STRING),
            )
        )
        val res = postRpcCancellable(
            client,
            RelayApi.PATH_SEND_MESSAGE, // placeholder; real action is ActionType_CREATE_GAIA_PAIRING_CLIENT_FINISHED
            RelayApi.CT_PROTO,
            finishPayload,
        )
        if (!res.isOk()) throw PairingException("FinishGaiaPairing HTTP ${res.code}")

        val ttlMs = (if (begun.tokenData.ttlSeconds > 0) begun.tokenData.ttlSeconds else 24 * 3600) * 1000L
        AuthData(
            aesKey = aesKey,
            hmacKey = hmacKey2,
            refreshPrivateKey = ByteArray(0), // Gaia uses the ECDSA refresh key from begin()
            refreshPublicKey = begun.refreshPublicKey,
            tachyonToken = begun.tokenData.token,
            tachyonExpiryMs = System.currentTimeMillis() + ttlMs,
            sessionId = begun.sessionUuid,
            pairingId = handshake.pairingAttemptId,
            mobileB64 = "",
            browserB64 = "",
        )
    }

    /** Persists Gaia auth data + cookies. */
    fun persist(store: AuthStore, auth: AuthData, cookies: String) {
        store.save(auth)
        store.saveCookies(cookies)
    }

    private fun decodeSignInGaiaResponse(pblite: String): Pair<com.garrietta.relay.rcs.proto.TokenData, List<GaiaDevice>> {
        // Theoretical: parse TokenData + device list from the pblite response.
        // Real shapes will be corrected against the live response.
        return try {
            val decoded = Pblite.decode(pblite)
            // Best-effort: token at field 1, devices at field 2.
            val tokenBytes = decoded.bytes(1) ?: byteArrayOf()
            val tokenData = com.garrietta.relay.rcs.proto.TokenData(tokenBytes, 24 * 3600)
            // Devices: repeated field of [deviceId, isPrimary, lastSeenMs].
            val devices = (decoded[2] as? PValue.Arr)?.asList()?.mapNotNull { d ->
                val m = (d as? PValue.Arr)?.asMsg() ?: return@mapNotNull null
                GaiaDevice(
                    deviceId = m.str(1) ?: return@mapNotNull null,
                    isPrimary = (m.num(2) ?: 0L) == 1L,
                    lastSeenMs = m.num(3) ?: 0L,
                )
            } ?: emptyList()
            tokenData to devices
        } catch (e: Exception) {
            throw PairingException("SignInGaia: bad response: ${e.message}")
        }
    }

    private fun ByteArray.lexicographicallyBefore(other: ByteArray): Boolean {
        for (i in 0 until minOf(size, other.size)) {
            val a = this[i].toInt() and 0xFF
            val b = other[i].toInt() and 0xFF
            if (a != b) return a < b
        }
        return size < other.size
    }

    companion object {
        private const val TAG = "GaiaPairing"
        /** Terms-of-Service string sent with CLIENT_FINISHED (from spec). */
        const val TOS_STRING = "I agree to the Google Terms of Service"
    }
}
