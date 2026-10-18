package com.garrietta.relay.rcs.proto

/**
 * Pairing-protocol message models — clean-room Kotlin data classes with
 * hand-written binary protobuf mappers.
 *
 * Field numbers are protocol facts (authentication.proto in the reference
 * implementation). All mapping code here is original.
 */

// authentication.Device{1:userID, 2:sourceID, 3:network}
data class DeviceInfo(val userId: Long = 0L, val sourceId: String = "", val network: String = "")

fun encodeDevice(d: DeviceInfo): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Varint(d.userId),
        2 to FieldVal.Str(d.sourceId),
        3 to FieldVal.Str(d.network),
    ),
)

fun decodeDevice(bytes: ByteArray): DeviceInfo {
    val m = ProtoCodec.decode(bytes)
    return DeviceInfo(
        userId = m.firstVarint(1) ?: 0L,
        sourceId = m.firstStr(2).orEmpty(),
        network = m.firstStr(3).orEmpty(),
    )
}

// authentication.ConfigVersion{3:Year, 4:Month, 5:Day, 7:V1, 9:V2}
data class ConfigVersion(val year: Int, val month: Int, val day: Int, val v1: Int, val v2: Int) {
    companion object {
        /** Last-known web client config version (spec). TODO: fetch /web/config live. */
        val CURRENT = ConfigVersion(2026, 9, 2, 4, 6)
    }
}

fun encodeConfigVersion(c: ConfigVersion): ByteArray = ProtoCodec.encode(
    mapOf(
        3 to FieldVal.Varint(c.year.toLong()),
        4 to FieldVal.Varint(c.month.toLong()),
        5 to FieldVal.Varint(c.day.toLong()),
        7 to FieldVal.Varint(c.v1.toLong()),
        9 to FieldVal.Varint(c.v2.toLong()),
    ),
)

// authentication.AuthMessage{1:requestID, 3:network, 6:tachyonAuthToken, 7:configVersion}
data class AuthMessage(
    val requestId: String,
    val network: String = "",
    val tachyonToken: ByteArray = byteArrayOf(),
    val configVersion: ConfigVersion = ConfigVersion.CURRENT,
)

fun encodeAuthMessage(a: AuthMessage): ByteArray {
    val fields = mutableMapOf<Int, FieldVal>(
        1 to FieldVal.Str(a.requestId),
        7 to FieldVal.Msg(encodeConfigVersionFields(a.configVersion)),
    )
    if (a.network.isNotEmpty()) fields[3] = FieldVal.Str(a.network)
    if (a.tachyonToken.isNotEmpty()) fields[6] = FieldVal.Bytes(a.tachyonToken)
    return ProtoCodec.encode(fields)
}

private fun encodeConfigVersionFields(c: ConfigVersion): Map<Int, FieldVal> = mapOf(
    3 to FieldVal.Varint(c.year.toLong()),
    4 to FieldVal.Varint(c.month.toLong()),
    5 to FieldVal.Varint(c.day.toLong()),
    7 to FieldVal.Varint(c.v1.toLong()),
    9 to FieldVal.Varint(c.v2.toLong()),
)

// authentication.BrowserDetails{1:userAgent, 2:browserType, 3:OS, 6:deviceType}
data class BrowserDetails(
    val userAgent: String,
    val browserType: Int = 2, // CHROME
    val os: String = "Android",
    val deviceType: Int = 1, // WEB
)

fun encodeBrowserDetails(b: BrowserDetails): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Str(b.userAgent),
        2 to FieldVal.Varint(b.browserType.toLong()),
        3 to FieldVal.Str(b.os),
        6 to FieldVal.Varint(b.deviceType.toLong()),
    ),
)

// authentication.ECDSAKeys{1:field1, 2:encryptedKeys}
fun encodeEcdsaKeys(encryptedKeysX509: ByteArray): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Varint(2),
        2 to FieldVal.Bytes(encryptedKeysX509),
    ),
)

// authentication.KeyData{1:mobile, 6:ecdsaKeys, 2:webAuthKeyData, 3:browser}
// (pre-pairing: only ecdsaKeys is set)
fun encodeKeyData(ecdsaKeysProto: ByteArray): ByteArray = ProtoCodec.encode(
    mapOf(6 to FieldVal.Bytes(ecdsaKeysProto)),
)

// authentication.AuthenticationContainer{1:authMessage, 3:browserDetails, oneof{4:keyData}}
fun encodeAuthenticationContainer(
    authMessageProto: ByteArray,
    browserDetailsProto: ByteArray,
    keyDataProto: ByteArray,
): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Bytes(authMessageProto),
        3 to FieldVal.Bytes(browserDetailsProto),
        4 to FieldVal.Bytes(keyDataProto),
    ),
)

// authentication.URLData{1:pairingKey, 2:AESKey, 3:HMACKey} — QR payload
fun encodeUrlData(pairingKey: ByteArray, aesKey: ByteArray, hmacKey: ByteArray): ByteArray =
    ProtoCodec.encode(
        mapOf(
            1 to FieldVal.Bytes(pairingKey),
            2 to FieldVal.Bytes(aesKey),
            3 to FieldVal.Bytes(hmacKey),
        ),
    )

// authentication.TokenData{1:tachyonAuthToken, 2:TTL}
data class TokenData(val token: ByteArray, val ttlSeconds: Long)

fun decodeTokenData(bytes: ByteArray): TokenData {
    val m = ProtoCodec.decode(bytes)
    return TokenData(
        token = m.firstBytes(1) ?: byteArrayOf(),
        ttlSeconds = m.firstVarint(2) ?: 0L,
    )
}

// authentication.RegisterPhoneRelayResponse{2:browser, 3:pairingKey, 5:authKeyData}
data class RegisterPhoneRelayResult(
    val browser: DeviceInfo,
    val pairingKey: ByteArray,
    val tokenData: TokenData,
)

fun decodeRegisterPhoneRelayResponse(bytes: ByteArray): RegisterPhoneRelayResult {
    val m = ProtoCodec.decode(bytes)
    return RegisterPhoneRelayResult(
        browser = m.firstBytes(2)?.let(::decodeDevice) ?: DeviceInfo(),
        pairingKey = m.firstBytes(3) ?: byteArrayOf(),
        tokenData = m.firstBytes(5)?.let(::decodeTokenData) ?: TokenData(byteArrayOf(), 0),
    )
}

// authentication.PairedData{1:mobile, 2:tokenData, 3:browser}
data class PairedData(val mobile: DeviceInfo, val tokenData: TokenData, val browser: DeviceInfo)

fun decodePairedData(bytes: ByteArray): PairedData {
    val m = ProtoCodec.decode(bytes)
    return PairedData(
        mobile = m.firstBytes(1)?.let(::decodeDevice) ?: DeviceInfo(),
        tokenData = m.firstBytes(2)?.let(::decodeTokenData) ?: TokenData(byteArrayOf(), 0),
        browser = m.firstBytes(3)?.let(::decodeDevice) ?: DeviceInfo(),
    )
}

/**
 * events.RPCPairData — oneof{4:paired, 5:revoked}. Returns the paired data,
 * or null if this is a revocation/unpair event.
 */
fun decodeRpcPairData(bytes: ByteArray): PairedData? {
    val m = ProtoCodec.decode(bytes)
    // oneof: exactly one of paired(4)/revoked(5) is set
    return m.firstBytes(4)?.let(::decodePairedData)
}
