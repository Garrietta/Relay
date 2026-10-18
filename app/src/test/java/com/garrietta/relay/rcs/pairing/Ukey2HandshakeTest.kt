package com.garrietta.relay.rcs.pairing

import com.garrietta.relay.rcs.crypto.RcsCrypto
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.bytes
import com.garrietta.relay.rcs.pblite.msg
import com.garrietta.relay.rcs.pblite.num
import com.garrietta.relay.rcs.pblite.rep
import com.garrietta.relay.rcs.pblite.str
import com.garrietta.relay.rcs.proto.FieldVal
import com.garrietta.relay.rcs.proto.ProtoCodec
import com.garrietta.relay.rcs.proto.buildGaiaOutgoingPblite
import com.garrietta.relay.rcs.proto.DeviceInfo
import com.garrietta.relay.rcs.proto.encodeOutgoingRpcDataUnencrypted
import com.garrietta.relay.rcs.proto.firstBytes
import com.garrietta.relay.rcs.proto.firstMsg
import com.garrietta.relay.rcs.proto.firstStr
import com.garrietta.relay.rcs.proto.firstVarint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Verifies the UKEY2 handshake shapes against the ukey.proto field numbers
 * (verified 2026-10-18) and the reference PreparePayloads/ProcessServerInit
 * flow: field numbers, the SHA-512-over-wrapped-finish commitment, the
 * container layouts, a simulated server round-trip (emoji agreement), the
 * Ditto v1 key derivation, and the Gaia uplink envelope.
 */
class Ukey2HandshakeTest {

    private fun newHandshake() = GaiaPairing().prepareUkey2()

    @Test fun `client init has verified ukey field numbers`() {
        val h = newHandshake()
        val msg = ProtoCodec.decode(h.initPayload)
        assertEquals(2L, msg.firstVarint(1)) // CLIENT_INIT
        val inner = ProtoCodec.decode(msg.firstBytes(2)!!)
        assertEquals(1L, inner.firstVarint(1)) // version
        assertEquals(32, inner.firstBytes(2)!!.size) // random
        val commitments = inner[3]!!
        assertEquals(1, commitments.size)
        val commitment = ProtoCodec.decode((commitments[0] as FieldVal.Bytes).v)
        assertEquals(100L, commitment.firstVarint(1)) // P256_SHA512
        assertEquals(64, commitment.firstBytes(2)!!.size) // SHA-512
        assertEquals("AES_256_CBC-HMAC_SHA256", inner.firstStr(4))
    }

    @Test fun `commitment is sha512 over the wrapped finish bytes`() {
        val h = newHandshake()
        // The finish payload must be the WRAPPED Ukey2Message(CLIENT_FINISH).
        val finishMsg = ProtoCodec.decode(h.finishPayload)
        assertEquals(4L, finishMsg.firstVarint(1)) // CLIENT_FINISH
        val finishInner = ProtoCodec.decode(finishMsg.firstBytes(2)!!)
        val pubKey = finishInner.firstMsg(1)!!
        assertEquals(1L, pubKey.firstVarint(1)) // EC_P256
        val ec = pubKey.firstMsg(2)!!
        val x = ec.firstBytes(1)!!
        val y = ec.firstBytes(2)!!
        assertEquals(33, x.size)
        assertEquals(33, y.size)
        assertEquals(0.toByte(), x[0])
        assertEquals(0.toByte(), y[0])

        val expected = MessageDigest.getInstance("SHA-512").digest(h.finishPayload)
        val initInner = ProtoCodec.decode(ProtoCodec.decode(h.initPayload).firstBytes(2)!!)
        val commitment = ProtoCodec.decode((initInner[3]!![0] as FieldVal.Bytes).v)
        assertArrayEquals(expected, commitment.firstBytes(2)!!)
    }

    @Test fun `client init container has expected layout`() {
        val h = newHandshake()
        val c = ProtoCodec.decode(GaiaPairing.buildClientInitContainer(h, "TestUA"))
        assertEquals(h.pairingAttemptId, c.firstStr(1))
        val browser = c.firstMsg(2)!!
        assertEquals("TestUA", browser.firstStr(1))
        assertEquals(1L, browser.firstVarint(2)) // OTHER
        assertEquals("Android", browser.firstStr(3))
        assertEquals(2L, browser.firstVarint(6)) // TABLET
        val ts = c.firstVarint(3)!!
        assertTrue(ts in System.currentTimeMillis() - 60_000..System.currentTimeMillis() + 60_000)
        assertArrayEquals(h.initPayload, c.firstBytes(4))
        assertEquals(1L, c.firstVarint(5)) // proposedVerificationCodeVersion
        assertEquals(1L, c.firstVarint(6)) // proposedKeyDerivationVersion
    }

    @Test fun `finish container has expected layout`() {
        val h = newHandshake()
        val c = ProtoCodec.decode(GaiaPairing.buildFinishContainer(h, "TestUA"))
        assertEquals(h.pairingAttemptId, c.firstStr(1))
        assertArrayEquals(h.finishPayload, c.firstBytes(4))
        assertTrue(c.firstStr(8)!!.contains("undocumented API"))
        // Proposed versions are NOT sent on finish.
        assertEquals(null, c.firstVarint(5))
        assertEquals(null, c.firstVarint(6))
    }

    @Test fun `server init round trip agrees on emoji`() {
        val gaia = GaiaPairing()
        val h = gaia.prepareUkey2()

        // Simulated phone: rebuild our public key from the finish payload,
        // generate its own keypair, build SERVER_INIT.
        val finishInner = ProtoCodec.decode(ProtoCodec.decode(h.finishPayload).firstBytes(2)!!)
        val ec = finishInner.firstMsg(1)!!.firstMsg(2)!!
        val clientX = ec.firstBytes(1)!!.copyOfRange(1, 33)
        val clientY = ec.firstBytes(2)!!.copyOfRange(1, 33)
        val clientPub = GaiaPairing.publicKeyFromXY(clientX, clientY)

        val serverKp = RcsCrypto.generateEcdsaKeyPair()
        val sharedSecret = RcsCrypto.ecdhSha256(serverKp.private, clientPub)
        val serverUncompressed = RcsCrypto.uncompressedPublicKey(serverKp.public)
        val sx = byteArrayOf(0) + serverUncompressed.copyOfRange(1, 33)
        val sy = byteArrayOf(0) + serverUncompressed.copyOfRange(33, 65)
        val srvRandom = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val serverInitInner = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(1L),
                2 to FieldVal.Bytes(srvRandom),
                3 to FieldVal.Varint(100L),
                4 to FieldVal.Msg(
                    mapOf(
                        1 to FieldVal.Varint(1L),
                        2 to FieldVal.Msg(
                            mapOf(1 to FieldVal.Bytes(sx), 2 to FieldVal.Bytes(sy)),
                        ),
                    ),
                ),
            ),
        )
        val serverInitMsg = ProtoCodec.encode(
            mapOf(1 to FieldVal.Varint(3L), 2 to FieldVal.Bytes(serverInitInner)),
        )
        val container = ProtoCodec.encode(
            mapOf(5 to FieldVal.Bytes(serverInitMsg), 6 to FieldVal.Varint(1L)),
        )

        val result = gaia.processServerInit(h, container)

        // Independent recomputation of the expected emoji.
        val info = h.initPayload + serverInitMsg
        val auth = RcsCrypto.hkdfSha256(sharedSecret, "UKEY2 v1 auth".toByteArray(), info, 32)
        val expectedNext = RcsCrypto.hkdfSha256(sharedSecret, "UKEY2 v1 next".toByteArray(), info, 32)
        val index = ((auth[0].toInt() and 0xFF) shl 24) or
                ((auth[1].toInt() and 0xFF) shl 16) or
                ((auth[2].toInt() and 0xFF) shl 8) or
                (auth[3].toInt() and 0xFF)
        val expected = PairingEmojis.forVersion(1)[Math.floorMod(index, PairingEmojis.V1.size)]
        assertEquals(expected, result.emoji)
        assertEquals(1, result.verificationCodeVersion)
        assertArrayEquals(expectedNext, result.nextKey)
    }

    @Test fun `ditto v1 key derivation is deterministic with distinct keys`() {
        val nextKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (aes1, hmac1) = GaiaPairing.deriveDittoV1Keys(nextKey)
        val (aes2, hmac2) = GaiaPairing.deriveDittoV1Keys(nextKey)
        assertEquals(32, aes1.size)
        assertEquals(32, hmac1.size)
        assertArrayEquals(aes1, aes2)
        assertArrayEquals(hmac1, hmac2)
        assertTrue(!aes1.contentEquals(hmac1))
        // Different nextKey -> different output.
        val (aes3, _) = GaiaPairing.deriveDittoV1Keys(ByteArray(32).also { SecureRandom().nextBytes(it) })
        assertTrue(!aes1.contentEquals(aes3))
    }

    @Test fun `byte hash matches reference signed semantics`() {
        // Reference: out=1; out=31*out+int8(b). NOT Java's String.hashCode
        // (which starts at 0) — the implementation follows the reference.
        var expected = 1
        for (b in "test".toByteArray()) expected = 31 * expected + b.toInt()
        assertEquals(expected, GaiaPairing.byteHash("test".toByteArray()))
        // Signed bytes: 0xFF is -1, not 255.
        var expected2 = 1
        for (b in byteArrayOf(-1, -128, 127)) expected2 = 31 * expected2 + b.toInt()
        assertEquals(expected2, GaiaPairing.byteHash(byteArrayOf(-1, -128, 127)))
    }

    @Test fun `emoji tables match the reference`() {
        // V0 is the reference list verbatim: 305 entries including the
        // reference's own 🍞 duplicate (304 unique).
        assertEquals(305, PairingEmojis.V0.size)
        assertEquals(304, PairingEmojis.V0.toSet().size)
        assertEquals(2, PairingEmojis.V0.count { it == "🍞" })
        // V1 = dedup(V0) + 14 added - 10 removed = 308, all unique.
        assertEquals(308, PairingEmojis.V1.size)
        assertEquals(308, PairingEmojis.V1.toSet().size)
        val v0Set = PairingEmojis.V0.toSet()
        val v1Set = PairingEmojis.V1.toSet()
        val added = v1Set - v0Set
        val removed = v0Set - v1Set
        assertEquals(
            setOf("🍋‍🟩", "🐦‍🔥", "🐲", "🪅", "🦜", "🏺", "🗿", "🫐", "⛽", "🍱", "🥡", "🧋", "🍼", "📐"),
            added,
        )
        assertEquals(
            setOf("💻", "🤗", "💬", "👋", "😁", "😎", "😇", "🥰", "🤓", "🤩"),
            removed,
        )
        assertEquals(PairingEmojis.V0, PairingEmojis.forVersion(0))
        assertEquals(PairingEmojis.V1, PairingEmojis.forVersion(1))
        assertEquals(PairingEmojis.V1, PairingEmojis.forVersion(7))
    }

    /**
     * Known-answer test for the emoji derivation, generated by an independent
     * Python implementation of the reference ProcessServerInit (verified
     * against mautrix/gmessages pair_google.go and Google's official UKEY2
     * Java implementation). Fixed inputs must produce 🚥 (V1 index 168).
     *
     * This proves AGREEMENT with the reference, not just self-consistency.
     * If the phone disagrees with our emoji, the derivation inputs (logged
     * via debugHex) differ — not the derivation itself.
     */
    @Test fun `emoji derivation matches known answer`() {
        // Fixed P-256 private key (PKCS8 DER, deterministic). Generated from scalar
        // 0x123456789ABCDEF0... (valid, < P-256 order).
        val pkcs8Hex = "3059020100301306072a8648ce3d020106082a8648ce3d030107042f302d0201010420123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0a00a06082a8648ce3d030107"
        val pkcs8 = pkcs8Hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(pkcs8),
        )

        // Fixed CLIENT_INIT (wrapped Ukey2Message) bytes from the Python generator.
        val initHex = "0802127308011220000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f1a4408641240483f6e6ca6974e8667b18ba1c35131288ccbeb9823660f8ad4df9570a4da03ad1e77072b5c20bb04489b12990dc47abf701c51459c50272dc5a77835b80b8a5d220754616368796f6e"
        val initPayload = initHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // Fixed SERVER_INIT container (field 5 = wrapped SERVER_INIT, field 6 = version 1).
        val containerHex = "2a760803127208011220202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f1864224a080112460a21006895c1abd3923dfa3019676a3b683fa2d29626cd353fa71936afc3b4504f30b5122100cce29f35b07402a4de6a0ca3de2a850191a7963201d1d3d28b72ba21e880f53e3001"
        val container = containerHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val handshake = GaiaPairing.Ukey2Handshake(
            ecdhPrivate = privateKey,
            ecdhPublic = RcsCrypto.generateEcdsaKeyPair().public, // unused by processServerInit
            clientRandom = ByteArray(32), // unused by processServerInit
            initPayload = initPayload,
            finishPayload = ByteArray(0), // unused by processServerInit
            pairingAttemptId = "test",
        )

        val result = GaiaPairing().processServerInit(handshake, container)

        // Expected: V1[168] = 🚥 (computed by independent Python implementation).
        assertEquals("🚥", result.emoji)
        assertEquals(1, result.verificationCodeVersion)
        // The debugHex must contain the derivation inputs.
        val dbg = result.debugHex!!
        assertEquals(initHex, dbg["initPayload"])
        assertEquals(64, dbg["sharedSecret"]!!.length / 2) // 32 bytes hex
        assertEquals("2312484188", dbg["index"])
    }

    @Test fun `gaia outgoing envelope has expected layout`() {        val destId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
        val rpcData = encodeOutgoingRpcDataUnencrypted("req-1", 44, byteArrayOf(1, 2, 3), "sess-1")
        val body = buildGaiaOutgoingPblite(
            mobile = DeviceInfo(userId = 123L, sourceId = "src", network = "net"),
            requestId = "req-1",
            rpcDataBinary = rpcData,
            tachyonToken = byteArrayOf(9, 9),
            sessionId = "sess-1",
            ttlMicros = 300_000_000L,
            messageType = 20,
            destRegIds = listOf(destId),
        )
        val m = Pblite.decode(body)
        assertEquals(300_000_000L, m.num(5))
        val dests = m.rep(9)
        assertEquals(1, dests.size)
        val decoded = java.util.Base64.getDecoder()
            .decode((dests[0] as com.garrietta.relay.rcs.pblite.PValue.Str).v)
            .toString(Charsets.UTF_8)
        assertEquals(destId, decoded)
        val data = m.msg(2)!!
        assertEquals(20L, data.msg(23)!!.num(2))
        val rpcDecoded = ProtoCodec.decode(data.bytes(12)!!)
        assertEquals("req-1", rpcDecoded.firstStr(1))
        assertEquals(44L, rpcDecoded.firstVarint(2))
        assertArrayEquals(byteArrayOf(1, 2, 3), rpcDecoded.firstBytes(3))
        assertEquals("sess-1", rpcDecoded.firstStr(6))
    }
}
