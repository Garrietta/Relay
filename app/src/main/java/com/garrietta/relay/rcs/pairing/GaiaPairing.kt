package com.garrietta.relay.rcs.pairing

import android.util.Log
import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.net.RelayApi
import com.garrietta.relay.rcs.net.RpcResult
import com.garrietta.relay.rcs.net.SapisidHash
import com.garrietta.relay.rcs.net.postRpcCancellable
import com.garrietta.relay.rcs.pblite.PValue
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.asMsg
import com.garrietta.relay.rcs.pblite.bytes
import com.garrietta.relay.rcs.pblite.msg
import com.garrietta.relay.rcs.pblite.num
import com.garrietta.relay.rcs.pblite.rep
import com.garrietta.relay.rcs.pblite.str
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.proto.ProtoCodec
import com.garrietta.relay.rcs.proto.FieldVal
import com.garrietta.relay.rcs.proto.TokenData
import com.garrietta.relay.rcs.proto.firstBytes
import com.garrietta.relay.rcs.proto.firstMsg
import com.garrietta.relay.rcs.proto.firstVarint
import com.garrietta.relay.rcs.session.AuthData
import com.garrietta.relay.rcs.session.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
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
 *  2. [prepareUkey2]: ECDH P-256 pairing keypair; CLIENT_INIT payload
 *     (Ukey2Message wrapping Ukey2ClientInit), commitment = SHA-512 over the
 *     WRAPPED CLIENT_FINISH bytes.
 *  3. [processServerInit]: the session delivers SERVER_INIT; validate,
 *     ECDH → sharedSecret = sha256(DH); ukeyV1Auth/nextKey via HKDF-SHA256;
 *     emoji = uint32be(ukeyV1Auth) % table size (real emoji tables).
 *  4. finish: CLIENT_FINISHED goes over the session as an unencrypted
 *     GAIA_2/BUGLE message; Ditto v1 key derivation; persist.
 *
 * Step 1 (SignInGaia request/response shapes) verified against the reference
 * implementation 2026-10-18. Steps 2–4 verified against ukey.proto field
 * numbers + pair_google.go (PreparePayloads / ProcessServerInit /
 * sendGaiaPairingMessage / FinishGaiaPairing) 2026-10-18; the live exchange
 * is what this round validates on-device.
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
        /** The phone's Device proto from the SignInGaia response (for uplink Mobile). */
        val mobileDevice: DeviceInfo,
    )

    data class Ukey2Handshake(
        val ecdhPrivate: PrivateKey,
        val ecdhPublic: PublicKey,
        val clientRandom: ByteArray,
        /** Wrapped Ukey2Message(CLIENT_INIT) bytes — also the HKDF info prefix. */
        val initPayload: ByteArray,
        /** Wrapped Ukey2Message(CLIENT_FINISH) bytes — sent at finish. */
        val finishPayload: ByteArray,
        val pairingAttemptId: String,
    )

    /** Result of processing the phone's SERVER_INIT. */
    data class ServerInitResult(
        val serverPublicKey: PublicKey,
        /** HKDF(sha256(DH), "UKEY2 v1 next", info) — kept for the finish derivation. */
        val nextKey: ByteArray,
        val verificationCodeVersion: Int,
        val emoji: String,
        /**
         * Hex-encoded derivation inputs for diagnosing emoji mismatches
         * (initPayload, serverInit, sharedSecret, ukeyV1Auth, index, version).
         * Populated in processServerInit; logged by the UI layer (android.util.Log
         * throws Stub! in JVM unit tests, so logging lives outside this function).
         */
        val debugHex: Map<String, String>? = null,
    )

    /** Parsed GaiaPairingResponseContainer (finish/error path). */
    data class GaiaFinishResult(val finishErrorType: Long, val finishErrorCode: Long)

    /**
     * Step 1: SignInGaia. Requires Google session cookies (from the WebView
     * sign-in). Returns the token + device list with the primary phone picked.
     *
     * Request body layout verified against the reference implementation
     * 2026-10-18 (see [buildSignInGaiaBody]): the 400s were the old
     * best-effort `{1: deviceId, 2: base64key}` shape — the real request is
     * the full AuthMessage/Inner/GDitto structure.
     */
    suspend fun begin(cookies: String): BegunGaia = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) throw PairingException("Gaia pairing needs Google session cookies")
        val requestId = UUID.randomUUID().toString()
        // The session UUID in no-dash lowercase hex doubles as the deviceID
        // suffix AND the session id (upstream uses AuthData.SessionID for both).
        val sessionHex = requestId.replace("-", "").lowercase()
        val deviceId = "messages-web-$sessionHex"
        val ecKeys = RcsCrypto.generateEcdsaKeyPair()
        val keyDer = ecKeys.public.encoded // x509/PKIX DER (see encodeDataField3Binary)

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
        // Field-36 encoding: the binary form first; if the server 400s it,
        // fall back to the nested-JSON form. The log records which form the
        // server accepted, so the next round can drop the loser.
        val forms = listOf(true to "pblite_binary", false to "nested-json")
        var lastCode = -1
        var parsed: ParsedGaiaResponse? = null
        for ((asBinary, formName) in forms) {
            val body = buildSignInGaiaBody(requestId, sessionHex, keyDer, asBinary)
            // Header NAMES only — values (Cookie, SAPISIDHASH) are secrets and
            // are never logged.
            Log.d(TAG, "SignInGaia POST $url body=${body.size}B deviceId=$deviceId form=$formName " +
                    "headers=${headers.names()} cookies=[${cookieNames(cookies)}]")
            val res = postSignInGaiaOnce(client, url, headers, body)
            if (res.isOk()) {
                Log.d(TAG, "SignInGaia accepted field-36 form=$formName")
                parsed = parseSignInGaiaResponse(res.text)
                break
            }
            lastCode = res.code
            if (res.code != 400) {
                // Not a body-shape rejection — don't try the other form.
                throw PairingException(cleanHttpError("SignInGaia", res.code))
            }
            Log.w(TAG, "SignInGaia HTTP 400 with field-36 form=$formName; trying next form: " +
                    res.text.take(300))
        }
        val ok = parsed
            ?: throw PairingException("SignInGaia failed (HTTP $lastCode) with both field-36 forms")
        val tokenData = TokenData(ok.token, ok.ttlSeconds)
        val devices = ok.devices
        val chosen = devices.filter { it.isPrimary }.maxByOrNull { it.lastSeenMs }
            ?: devices.maxByOrNull { it.lastSeenMs }
            ?: throw PairingException("SignInGaia: no devices on the account")
        BegunGaia(
            sessionUuid = sessionHex,
            deviceId = deviceId,
            cookies = cookies,
            refreshPublicKey = keyDer,
            tokenData = tokenData,
            devices = devices,
            chosenDevice = chosen,
            mobileDevice = ok.mobileDevice,
        )
    }

    /**
     * SignInGaia with bounded retries: up to 3 attempts, exponential
     * backoff (1s, 2s). Returns the result for 2xx AND 4xx (the caller
     * decides what a 4xx means — e.g. trying the other field-36 form on a
     * 400). 5xx / network failures retry. The raw HTML error page goes to
     * logcat only; the exception carries a one-line message for the UI
     * (never raw HTML).
     */
    private suspend fun postSignInGaiaOnce(
        client: OkHttpClient,
        url: String,
        headers: Headers,
        body: ByteArray,
    ): RpcResult {
        var last: RpcResult? = null
        var backoffMs = 1000L
        repeat(3) { attempt ->
            val res = postRpcCancellable(client, url, headers, body)
            if (res.isOk() || res.code in 400..499) return res
            Log.w(TAG, "SignInGaia HTTP ${res.code} (attempt ${attempt + 1}/3), backing off ${backoffMs}ms: ${res.text.take(200)}")
            last = res
            if (attempt < 2) delay(backoffMs)
            backoffMs *= 2
        }
        return last!!
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
     * Step 2: prepare the UKEY2 payloads (reference PreparePayloads).
     *
     * Field numbers verified against ukey.proto 2026-10-18:
     * - Ukey2Message{message_type=1, message_data=2}; CLIENT_INIT=2, CLIENT_FINISH=4
     * - Ukey2ClientInit{version=1, random=2, cipher_commitments=3, next_protocol=4}
     * - CipherCommitment{handshake_cipher=1 (P256_SHA512=100), commitment=2}
     * - Ukey2ClientFinished{public_key=1}
     * - GenericPublicKey{type=1 (EC_P256=1), ec_p256_public_key=2}
     * - EcP256PublicKey{x=1, y=2} — coordinates as 0x00‖32-byte big-endian.
     *
     * The commitment is SHA-512 over the WRAPPED CLIENT_FINISH bytes
     * (Ukey2Message envelope included), not the raw inner payload.
     */
    fun prepareUkey2(): Ukey2Handshake {
        val kp = RcsCrypto.generateEcdsaKeyPair()
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pairingAttemptId = UUID.randomUUID().toString()

        // EcP256PublicKey coordinates: 0x00 prefix + 32-byte X / Y.
        val uncompressed = RcsCrypto.uncompressedPublicKey(kp.public) // 0x04‖X‖Y
        require(uncompressed.size == 65) { "unexpected P-256 key length ${uncompressed.size}" }
        val x = byteArrayOf(0) + uncompressed.copyOfRange(1, 33)
        val y = byteArrayOf(0) + uncompressed.copyOfRange(33, 65)

        // Ukey2ClientFinished{public_key: GenericPublicKey{type: EC_P256, ec_p256_public_key: {x, y}}}
        val finishInner = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Msg(
                    mapOf(
                        1 to FieldVal.Varint(PUBKEY_TYPE_EC_P256.toLong()),
                        2 to FieldVal.Msg(
                            mapOf(
                                1 to FieldVal.Bytes(x),
                                2 to FieldVal.Bytes(y),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // Wrap: Ukey2Message{message_type: CLIENT_FINISH, message_data: finishInner}
        val finishWrapped = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(UKEY_MSG_CLIENT_FINISH.toLong()),
                2 to FieldVal.Bytes(finishInner),
            ),
        )
        // Commitment covers the WRAPPED finish bytes.
        val commitment = MessageDigest.getInstance("SHA-512").digest(finishWrapped)

        // Ukey2ClientInit{version: 1, random, cipher_commitments: [{P256_SHA512, commitment}],
        //                next_protocol: "AES_256_CBC-HMAC_SHA256"}
        val clientInitInner = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(1L),
                2 to FieldVal.Bytes(random),
                3 to FieldVal.Rep(
                    listOf(
                        FieldVal.Msg(
                            mapOf(
                                1 to FieldVal.Varint(UKEY_CIPHER_P256_SHA512.toLong()),
                                2 to FieldVal.Bytes(commitment),
                            ),
                        ),
                    ),
                ),
                4 to FieldVal.Str("AES_256_CBC-HMAC_SHA256"),
            ),
        )
        // Wrap: Ukey2Message{message_type: CLIENT_INIT, message_data: clientInitInner}
        val initWrapped = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(UKEY_MSG_CLIENT_INIT.toLong()),
                2 to FieldVal.Bytes(clientInitInner),
            ),
        )
        return Ukey2Handshake(
            ecdhPrivate = kp.private,
            ecdhPublic = kp.public,
            clientRandom = random,
            initPayload = initWrapped,
            finishPayload = finishWrapped,
            pairingAttemptId = pairingAttemptId,
        )
    }

    /**
     * Step 3: process the phone's SERVER_INIT (reference ProcessServerInit).
     *
     * [containerBytes] is the raw GaiaPairingResponseContainer bytes from the
     * session. Validates the UKEY2 envelope (SERVER_INIT / v1 / P256_SHA512 /
     * 32-byte random), rebuilds the server P-256 key, then:
     *   sharedSecret = sha256(ECDH)
     *   info = initPayload ‖ serverInitMessageBytes
     *   ukeyV1Auth = HKDF-SHA256(sharedSecret, "UKEY2 v1 auth", info)
     *   nextKey    = HKDF-SHA256(sharedSecret, "UKEY2 v1 next", info)
     *   emoji = table[uint32be(ukeyV1Auth) % table.size]
     * The table follows the server's confirmed verification-code version.
     */
    fun processServerInit(
        handshake: Ukey2Handshake,
        containerBytes: ByteArray,
    ): ServerInitResult {
        val container = ProtoCodec.decode(containerBytes)
        val finishErrorType = container.firstVarint(1) ?: 0L
        if (finishErrorType != 0L) {
            throw PairingException(
                "phone reported pairing error (type=$finishErrorType code=${container.firstVarint(2) ?: 0})",
            )
        }
        val verificationCodeVersion = container.firstVarint(6)?.toInt() ?: 0
        val ukeyMessageBytes = container.firstBytes(5)
            ?: throw PairingException("SERVER_INIT: pairing response has no data")
        val ukeyMessage = ProtoCodec.decode(ukeyMessageBytes)
        val msgType = ukeyMessage.firstVarint(1)?.toInt()
            ?: throw PairingException("SERVER_INIT: missing message_type")
        if (msgType != UKEY_MSG_SERVER_INIT) {
            throw PairingException("SERVER_INIT: unexpected message_type=$msgType")
        }
        val serverInitBytes = ukeyMessage.firstBytes(2)
            ?: throw PairingException("SERVER_INIT: missing message_data")
        val serverInit = ProtoCodec.decode(serverInitBytes)
        if (serverInit.firstVarint(1)?.toInt() != 1) {
            throw PairingException("SERVER_INIT: unsupported version")
        }
        val srvRandom = serverInit.firstBytes(2) ?: byteArrayOf()
        if (srvRandom.size != 32) {
            throw PairingException("SERVER_INIT: bad random length ${srvRandom.size}")
        }
        if (serverInit.firstVarint(3)?.toInt() != UKEY_CIPHER_P256_SHA512) {
            throw PairingException("SERVER_INIT: unexpected handshake cipher")
        }
        val pubKeyBytes = serverInit.firstBytes(4)
            ?: throw PairingException("SERVER_INIT: missing public_key")
        val pubKeyMsg = ProtoCodec.decode(pubKeyBytes)
        val ecMsg = pubKeyMsg.firstMsg(2)
            ?: throw PairingException("SERVER_INIT: missing ec_p256_public_key")
        var x = ecMsg.firstBytes(1)
            ?: throw PairingException("SERVER_INIT: missing x coordinate")
        var y = ecMsg.firstBytes(2)
            ?: throw PairingException("SERVER_INIT: missing y coordinate")
        if (x.size == 33 && x[0] == 0.toByte()) x = x.copyOfRange(1, 33)
        if (y.size == 33 && y[0] == 0.toByte()) y = y.copyOfRange(1, 33)
        if (x.size != 32 || y.size != 32) {
            throw PairingException("SERVER_INIT: bad coordinate lengths ${x.size}/${y.size}")
        }
        val serverPublicKey = publicKeyFromXY(x, y)

        val sharedSecret = RcsCrypto.ecdhSha256(handshake.ecdhPrivate, serverPublicKey)
        val info = handshake.initPayload + ukeyMessageBytes
        val ukeyV1Auth = RcsCrypto.hkdfSha256(sharedSecret, "UKEY2 v1 auth".toByteArray(), info, 32)
        val nextKey = RcsCrypto.hkdfSha256(sharedSecret, "UKEY2 v1 next".toByteArray(), info, 32)
        val table = PairingEmojis.forVersion(verificationCodeVersion)
        val index = ((ukeyV1Auth[0].toInt() and 0xFF) shl 24) or
                ((ukeyV1Auth[1].toInt() and 0xFF) shl 16) or
                ((ukeyV1Auth[2].toInt() and 0xFF) shl 8) or
                (ukeyV1Auth[3].toInt() and 0xFF)
        val emoji = table[Math.floorMod(index, table.size)]
        // Note: no Log.d here — android.util.Log throws Stub! in JVM unit tests.
        // The UI layer logs debugHex instead.
        val debugHex = mapOf(
            "initPayload" to handshake.initPayload.toHex(),
            "serverInit" to ukeyMessageBytes.toHex(),
            "sharedSecret" to sharedSecret.toHex(),
            "ukeyV1Auth" to ukeyV1Auth.toHex(),
            "index" to index.toString(),
            "tableSize" to table.size.toString(),
            "version" to verificationCodeVersion.toString(),
        )
        return ServerInitResult(serverPublicKey, nextKey, verificationCodeVersion, emoji, debugHex)
    }

    /** Persists Gaia auth data + cookies. */
    fun persist(store: AuthStore, auth: AuthData, cookies: String) {
        store.save(auth)
        store.saveCookies(cookies)
    }

    companion object {
        private const val TAG = "GaiaPairing"

        // ---- ukey.proto (verified 2026-10-18) ----
        const val UKEY_MSG_CLIENT_INIT = 2
        const val UKEY_MSG_SERVER_INIT = 3
        const val UKEY_MSG_CLIENT_FINISH = 4
        const val UKEY_CIPHER_P256_SHA512 = 100
        const val PUBKEY_TYPE_EC_P256 = 1

        /**
         * Fixed 32-byte encryptionKeyInfo for the Ditto v1 derivation
         * (reference FinishGaiaPairing — NOT zeroes).
         */
        val ENCRYPTION_KEY_INFO = byteArrayOf(
            130.toByte(), 170.toByte(), 85.toByte(), 160.toByte(),
            211.toByte(), 151.toByte(), 248.toByte(), 131.toByte(),
            70.toByte(), 202.toByte(), 28.toByte(), 238.toByte(),
            141.toByte(), 57.toByte(), 9.toByte(), 185.toByte(),
            95.toByte(), 19.toByte(), 250.toByte(), 125.toByte(),
            235.toByte(), 29.toByte(), 74.toByte(), 179.toByte(),
            131.toByte(), 118.toByte(), 184.toByte(), 37.toByte(),
            109.toByte(), 168.toByte(), 85.toByte(), 16.toByte(),
        )

        /** Confirmation string the web client sends with CLIENT_FINISHED. */
        const val PRIVATE_API_CONFIRMATION =
            "This is an undocumented API. Use or access of undocumented Google APIs " +
                "without express authorization is prohibited per the Google API Terms " +
                "of Service (https://developers.google.com/terms)."

        /**
         * GaiaPairingRequestContainer for CLIENT_INIT:
         * {1: pairingAttemptID, 2: BrowserDetails{1: userAgent, 2: OTHER(1),
         *  3: "Android", 6: TABLET(2)}, 3: startTimestampMillis, 4: data,
         *  5: proposedVerificationCodeVersion=1, 6: proposedKeyDerivationVersion=1}
         */
        fun buildClientInitContainer(handshake: Ukey2Handshake, userAgent: String): ByteArray =
            ProtoCodec.encode(
                mapOf(
                    1 to FieldVal.Str(handshake.pairingAttemptId),
                    2 to FieldVal.Msg(browserDetailsFields(userAgent)),
                    3 to FieldVal.Varint(System.currentTimeMillis()),
                    4 to FieldVal.Bytes(handshake.initPayload),
                    5 to FieldVal.Varint(1L),
                    6 to FieldVal.Varint(1L),
                ),
            )

        /**
         * GaiaPairingRequestContainer for CLIENT_FINISHED:
         * {1: pairingAttemptID, 2: BrowserDetails, 3: startTimestampMillis,
         *  4: data, 8: privateAPIConfirmation} — the proposed versions are
         * NOT sent on finish.
         */
        fun buildFinishContainer(handshake: Ukey2Handshake, userAgent: String): ByteArray =
            ProtoCodec.encode(
                mapOf(
                    1 to FieldVal.Str(handshake.pairingAttemptId),
                    2 to FieldVal.Msg(browserDetailsFields(userAgent)),
                    3 to FieldVal.Varint(System.currentTimeMillis()),
                    4 to FieldVal.Bytes(handshake.finishPayload),
                    8 to FieldVal.Str(PRIVATE_API_CONFIRMATION),
                ),
            )

        private fun browserDetailsFields(userAgent: String): Map<Int, FieldVal> = mapOf(
            1 to FieldVal.Str(userAgent),
            2 to FieldVal.Varint(1L), // BrowserType.OTHER
            3 to FieldVal.Str("Android"),
            6 to FieldVal.Varint(2L), // DeviceType.TABLET
        )

        /** Parses a GaiaPairingResponseContainer (finish/error path). */
        fun parseFinishResponse(containerBytes: ByteArray): GaiaFinishResult {
            val m = ProtoCodec.decode(containerBytes)
            return GaiaFinishResult(
                finishErrorType = m.firstVarint(1) ?: 0L,
                finishErrorCode = m.firstVarint(2) ?: 0L,
            )
        }

        /**
         * Ditto v1 key derivation (reference FinishGaiaPairing,
         * confirmedKeyDerivationVersion == 1):
         *   clientKey = HKDF(nextKey, salt=encryptionKeyInfo, info="client")
         *   serverKey = HKDF(nextKey, salt=encryptionKeyInfo, info="server")
         *   sort the two by Java-style byte hash; concat = info ‖ sortedKeys
         *   h = sha256(concat)
         *   AES  = HKDF(h, salt="Ditto salt 1", info="Ditto info 1")
         *   HMAC = HKDF(h, salt="Ditto salt 2", info="Ditto info 2")
         */
        fun deriveDittoV1Keys(nextKey: ByteArray): Pair<ByteArray, ByteArray> {
            val clientKey = RcsCrypto.hkdfSha256(nextKey, ENCRYPTION_KEY_INFO, "client".toByteArray(), 32)
            val serverKey = RcsCrypto.hkdfSha256(nextKey, ENCRYPTION_KEY_INFO, "server".toByteArray(), 32)
            val (first, second) =
                if (byteHash(clientKey) < byteHash(serverKey)) clientKey to serverKey
                else serverKey to clientKey
            val concatHash = MessageDigest.getInstance("SHA-256")
                .digest(ENCRYPTION_KEY_INFO + first + second)
            val aes = RcsCrypto.hkdfSha256(concatHash, "Ditto salt 1".toByteArray(), "Ditto info 1".toByteArray(), 32)
            val hmac = RcsCrypto.hkdfSha256(concatHash, "Ditto salt 2".toByteArray(), "Ditto info 2".toByteArray(), 32)
            return aes to hmac
        }

        /** Java String.hashCode-style hash over bytes (signed). */
        internal fun byteHash(bytes: ByteArray): Int {
            var out = 1
            for (b in bytes) out = 31 * out + b.toInt()
            return out
        }

        /** Rebuilds a JCA P-256 public key from 32-byte big-endian x/y. */
        internal fun publicKeyFromXY(x: ByteArray, y: ByteArray): PublicKey {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec("secp256r1"))
            val ecParams = params.getParameterSpec(ECParameterSpec::class.java)
            val spec = ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), ecParams)
            return KeyFactory.getInstance("EC").generatePublic(spec)
        }

        /**
         * Exact SignInGaiaRequest layout, verified against the reference
         * implementation 2026-10-18 (protocol spec §1b):
         * - field 1 (AuthMessage): `{1: requestID (dashed UUID), 3: "GDitto",
         *   7: ConfigVersion{3: 2026, 4: 9, 5: 2, 7: 4, 9: 6}}` — field 6
         *   (tachyonAuthToken) is ABSENT on a fresh sign-in; the
         *   signInGaiaInitial variant is unused upstream, so UnknownInt3 is
         *   never sent.
         * - field 2 (Inner): `{1: {1: 3, 2: "messages-web-<32 lowercase hex,
         *   no dashes>"}, 36: pblite_binary}`.
         * - field 3: ABSENT. field 4: "GDitto".
         *
         * pblite_binary = base64(binary-protobuf(Data{3: keyBytes})); see
         * [encodeDataField3Binary]. When [field36AsBinary] is false, field 36
         * is sent as a nested JSON object `{3: base64(keyBytes)}` instead —
         * the fallback form if the server 400s the binary one.
         *
         * ConfigVersion values drift with the web client; re-check against
         * messages.google.com/web/config if pairing breaks in future.
         */
        internal fun buildSignInGaiaBody(
            requestIdDashed: String,
            deviceIdHex: String,
            keyDer: ByteArray,
            field36AsBinary: Boolean,
        ): ByteArray {
            val field36: PValue = if (field36AsBinary) {
                PValue.Str(encodeDataField3Binary(keyDer))
            } else {
                nested(mapOf(3 to PValue.Str(Pblite.encodeBase64(keyDer))))
            }
            return Pblite.encode(
                mapOf(
                    1 to nested(
                        mapOf(
                            1 to PValue.Str(requestIdDashed),
                            3 to PValue.Str("GDitto"),
                            7 to nested(
                                mapOf(
                                    3 to PValue.Num(2026),
                                    4 to PValue.Num(9),
                                    5 to PValue.Num(2),
                                    7 to PValue.Num(4),
                                    9 to PValue.Num(6),
                                ),
                            ),
                        ),
                    ),
                    2 to nested(
                        mapOf(
                            1 to nested(
                                mapOf(
                                    1 to PValue.Num(3),
                                    2 to PValue.Str("messages-web-$deviceIdHex"),
                                ),
                            ),
                            36 to field36,
                        ),
                    ),
                    4 to PValue.Str("GDitto"),
                ),
            ).toByteArray(Charsets.UTF_8)
        }

        /** Builds a nested pblite message node, trimming trailing nulls. */
        private fun nested(fields: Map<Int, PValue>): PValue.Arr {
            if (fields.isEmpty()) return PValue.Arr(emptyList())
            val size = fields.keys.max()
            val elems = (1..size)
                .map { fields[it] ?: PValue.PNull }
                .dropLastWhile { it is PValue.PNull }
            return PValue.Arr(elems)
        }

        /**
         * Hand-encodes binary protobuf `Data{3: keyBytes}` for the
         * pblite_binary option: tag byte 0x1A (field 3, wire type LEN),
         * varint length, then the key bytes — base64'd. The key bytes are the
         * ECDSA P-256 public key in x509/PKIX DER form, which is exactly what
         * Java `PublicKey.encoded` already is (pinned by unit test on the DER
         * prefix 0x30 0x59 0x30 0x13 …).
         */
        internal fun encodeDataField3Binary(keyBytes: ByteArray): String {
            val out = java.io.ByteArrayOutputStream()
            out.write(0x1A)
            var v = keyBytes.size.toLong()
            while (v > 0x7F) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
            out.write(keyBytes)
            return Pblite.encodeBase64(out.toByteArray())
        }

        internal data class ParsedGaiaResponse(
            val token: ByteArray,
            val ttlSeconds: Long,
            val devices: List<GaiaDevice>,
            val mobileDevice: DeviceInfo,
        )

        /**
         * Real SignInGaiaResponse layout, verified against the reference
         * implementation 2026-10-18 (protocol spec §1b):
         * - field 4 (TokenData): `{1: tachyon token bytes (base64 in pblite
         *   JSON), 2: TTL int64}`.
         * - field 3 (DeviceData): primary devices from sub-field 2
         *   (unknownItems2) — an item with sub-field 4 == 1 is primary,
         *   RegID = sub-field 1 (UUID string); lastSeen comes from sub-field 3
         *   (unknownItems3), matched by UUID sub-field 1 → sub-field 7 =
         *   timestamp in MICROSECONDS (converted to millis here).
         * - field 3 / sub-field 1 / sub-field 1: the phone's Device
         *   {1: userID, 2: sourceID, 3: network} — used as Mobile on uplink
         *   (sourceID lowercased, per the reference).
         * - field 1 (Header) and field 2 (maybeBrowserUUID, pblite_binary
         *   string) are parsed leniently and ignored.
         */
        /**
         * Decodes a pblite_binary registration UUID. The reference proto marks
         * `destOrSourceUUID` with `[(pblite.pblite_binary) = true]`, so the
         * server sends the UUID base64-encoded in the pblite JSON (and the
         * reference's pblite layer decodes it back to the plain dashed UUID
         * before use). Reading the raw JSON string without decoding produced
         * a DOUBLE-base64 destRegistrationID on our uplink, which the Tachyon
         * frontend answered with HTTP 404 "Requested entity was not found"
         * (TachyonError[29]) — diagnosed 2026-10-18 from a device 404.
         * Only the decoded form is accepted when it looks like a real
         * dashed UUID; otherwise the raw value is kept (never crash parsing).
         */
        internal fun decodeRegId(raw: String?): String? {
            if (raw == null) return null
            val decoded = runCatching {
                Pblite.decodeBase64(raw).toString(Charsets.UTF_8)
            }.getOrNull()
            return if (decoded != null && decoded.length == 36 &&
                decoded[8] == '-' && decoded[13] == '-' &&
                decoded[18] == '-' && decoded[23] == '-'
            ) {
                decoded
            } else {
                raw
            }
        }

        internal fun parseSignInGaiaResponse(pblite: String): ParsedGaiaResponse {
            try {
                val decoded = Pblite.decode(pblite)
                // Field 1 (Header) and field 2 (maybeBrowserUUID) are parsed
                // leniently — presence noted by callers via headers if needed.
                val tokenMsg = decoded.msg(4)
                    ?: throw PairingException("SignInGaia: response has no TokenData (field 4)")
                val token = tokenMsg.bytes(1) ?: byteArrayOf()
                val ttl = tokenMsg.num(2) ?: 0L
                val deviceData = decoded.msg(3)
                val lastSeenByRegId = mutableMapOf<String, Long>()
                if (deviceData != null) {
                    for (item in deviceData.rep(3)) {
                        val m = (item as? PValue.Arr)?.asMsg() ?: continue
                        val regId = decodeRegId(m.str(1)) ?: continue
                        val micros = m.num(7) ?: continue
                        lastSeenByRegId[regId] = micros
                    }
                }
                val devices = if (deviceData != null) {
                    deviceData.rep(2).mapNotNull { item ->
                        val m = (item as? PValue.Arr)?.asMsg() ?: return@mapNotNull null
                        val regId = decodeRegId(m.str(1)) ?: return@mapNotNull null
                        val isPrimary = (m.num(4) ?: 0L) == 1L
                        GaiaDevice(
                            deviceId = regId,
                            isPrimary = isPrimary,
                            lastSeenMs = (lastSeenByRegId[regId] ?: 0L) / 1000L,
                        )
                    }
                } else {
                    emptyList()
                }
                // The phone's Device proto for uplink Mobile.
                val deviceWrapper = deviceData?.msg(1)
                val device = deviceWrapper?.msg(1)
                val mobileDevice = if (device != null) {
                    DeviceInfo(
                        userId = device.num(1) ?: 0L,
                        sourceId = (device.str(2) ?: "").lowercase(),
                        network = device.str(3) ?: "",
                    )
                } else {
                    DeviceInfo()
                }
                return ParsedGaiaResponse(token, ttl, devices, mobileDevice)
            } catch (e: PairingException) {
                throw e
            } catch (e: Exception) {
                throw PairingException("SignInGaia: bad response: ${e.message}")
            }
        }
    }
}

/** Hex-encodes bytes for diagnostic logging (safe in unit tests, unlike android.util.Log). */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
