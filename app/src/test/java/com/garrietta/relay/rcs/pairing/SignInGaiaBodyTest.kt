package com.garrietta.relay.rcs.pairing

import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.bytes
import com.garrietta.relay.rcs.pblite.msg
import com.garrietta.relay.rcs.pblite.num
import com.garrietta.relay.rcs.pblite.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-10-18 device HTTP 400: the old best-effort
 * SignInGaia body (`{1: deviceId, 2: base64key}`) was rejected once auth
 * passed. The real layout (verified against the reference implementation,
 * protocol spec §1b) is pinned here field by field.
 */
class SignInGaiaBodyTest {

    private val requestId = "123e4567-e89b-12d3-a456-426614174000"
    private val deviceHex = "123e4567e89b12d3a456426614174000"

    @Test
    fun body_hasExactFieldLayout_binaryForm() {
        val key = ByteArray(91) { it.toByte() }
        val body = GaiaPairing.buildSignInGaiaBody(requestId, deviceHex, key, field36AsBinary = true)
        val top = Pblite.decode(body.toString(Charsets.UTF_8))

        // field 1: AuthMessage {1: dashed UUID, 3: "GDitto", 7: ConfigVersion}
        val auth = top.msg(1)
        assertNotNull(auth)
        assertEquals(requestId, auth!!.str(1))
        assertEquals("GDitto", auth.str(3))
        assertNull("tachyonAuthToken (field 6) must be absent on fresh sign-in", auth.str(6))
        val config = auth.msg(7)!!
        assertEquals(2026L, config.num(3))
        assertEquals(9L, config.num(4))
        assertEquals(2L, config.num(5))
        assertEquals(4L, config.num(7))
        assertEquals(6L, config.num(9))

        // field 2: Inner {1: {1: 3, 2: deviceID}, 36: pblite_binary}
        val inner = top.msg(2)!!
        val dev = inner.msg(1)!!
        assertEquals(3L, dev.num(1))
        assertEquals("messages-web-$deviceHex", dev.str(2))
        val f36 = inner.str(36)
        assertNotNull("field 36 must be present", f36)
        // pblite_binary: base64(binary-protobuf(Data{3: key})) → starts with tag 0x1A
        val raw = Pblite.decodeBase64(f36!!)
        assertEquals(0x1A, raw[0].toInt() and 0xFF)
        assertEquals(91, raw[1].toInt() and 0xFF) // varint length, single byte
        assertTrue(raw.copyOfRange(2, raw.size).contentEquals(key))

        // field 3 absent, field 4 = "GDitto"
        assertNull(top[3])
        assertEquals("GDitto", top.str(4))
    }

    @Test
    fun body_nestedJsonFallbackForm_field36IsObject() {
        val key = ByteArray(91) { it.toByte() }
        val body = GaiaPairing.buildSignInGaiaBody(requestId, deviceHex, key, field36AsBinary = false)
        val top = Pblite.decode(body.toString(Charsets.UTF_8))
        val inner = top.msg(2)!!
        // Fallback: field 36 as nested JSON {3: base64(key)}
        val f36 = inner.msg(36)
        assertNotNull(f36)
        assertTrue(Pblite.decodeBase64(f36!!.str(3)!!).contentEquals(key))
    }

    @Test
    fun pbliteBinary_hasKnownVector() {
        // Hand-computed: tag 0x1A, varint(3)=0x03, bytes {9,8,7}
        val got = GaiaPairing.encodeDataField3Binary(byteArrayOf(9, 8, 7))
        val want = Pblite.encodeBase64(byteArrayOf(0x1A, 0x03, 9, 8, 7))
        assertEquals(want, got)
    }

    @Test
    fun publicKey_isX509DerForm() {
        // Java EC P-256 PublicKey.encoded must already be x509/PKIX DER —
        // the pblite_binary field carries it verbatim. P-256 SPKI prefix:
        // 30 59 30 13 06 07 2A 86 48 CE 3D 02 01 06 08 2A 86 48 CE 3D 03 01 07
        val der = RcsCrypto.generateEcdsaKeyPair().public.encoded
        val prefix = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A.toByte(), 0x86.toByte(),
            0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
        )
        assertTrue(
            "expected x509/PKIX DER prefix, got ${der.take(13).joinToString(" ") { "%02x".format(it) }}",
            der.copyOfRange(0, 13).contentEquals(prefix),
        )
        assertEquals(91, der.size) // 26-byte prefix + 65-byte uncompressed point
    }

    @Test
    fun parseResponse_readsTokenAndDevices() {
        // Fabricated pblite response with the verified layout:
        // field 4 = TokenData{1: token bytes, 2: TTL}; field 3 = DeviceData
        // {2: unknownItems2, 3: unknownItems3}; fields 1/2 lenient.
        val json = """
            [
              ["hdr"],
              "YnJvd3Nlci11dWlk",
              [null,
               [["reg-1",null,null,1,null,null,42],["reg-2",null,null,0,null,null,7]],
               [["reg-1",null,null,null,null,null,1700000000000000],["reg-2",null,null,null,null,null,1600000000000000]]
              ],
              ["dG9rZW4=",86400]
            ]
        """.trimIndent()
        val parsed = GaiaPairing.parseSignInGaiaResponse(json)
        assertEquals("token", parsed.token.toString(Charsets.UTF_8))
        assertEquals(86400L, parsed.ttlSeconds)
        assertEquals(2, parsed.devices.size)
        val primary = parsed.devices.first { it.deviceId == "reg-1" }
        assertTrue(primary.isPrimary)
        assertEquals(1700000000000L, primary.lastSeenMs) // micros → millis
        val other = parsed.devices.first { it.deviceId == "reg-2" }
        assertTrue(!other.isPrimary)
        assertEquals(1600000000000L, other.lastSeenMs)
    }

    @Test(expected = PairingException::class)
    fun parseResponse_missingTokenData_throws() {
        GaiaPairing.parseSignInGaiaResponse("""[["hdr"],null,[]]""")
    }

    @Test
    fun parseResponse_decodesPbliteBinaryRegIds() {
        // Regression test for the 2026-10-18 device HTTP 404
        // (TachyonError[29] "Requested entity was not found"): the reference
        // proto marks destOrSourceUUID with [(pblite.pblite_binary) = true],
        // so the server sends it base64-encoded and the reference decodes it
        // to the plain dashed UUID. Reading the raw JSON string produced a
        // double-base64 destRegistrationID on our uplink.
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val b64 = Pblite.encodeBase64(uuid.toByteArray(Charsets.UTF_8))
        val json = """
            [
              ["hdr"],
              "YnJvd3Nlci11dWlk",
              [null,
               [["$b64",null,null,1,null,null,42]],
               [["$b64",null,null,null,null,null,1700000000000000]]
              ],
              ["dG9rZW4=",86400]
            ]
        """.trimIndent()
        val parsed = GaiaPairing.parseSignInGaiaResponse(json)
        assertEquals(1, parsed.devices.size)
        // The device ID must be the PLAIN dashed UUID — this exact string
        // goes out (single-base64) as destRegistrationIDs on the uplink.
        assertEquals(uuid, parsed.devices[0].deviceId)
        assertTrue(parsed.devices[0].isPrimary)
        assertEquals(1700000000000L, parsed.devices[0].lastSeenMs)
    }

    @Test
    fun decodeRegId_keepsPlainUuidAsIs() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(uuid, GaiaPairing.decodeRegId(uuid))
        assertNull(GaiaPairing.decodeRegId(null))
    }
}
