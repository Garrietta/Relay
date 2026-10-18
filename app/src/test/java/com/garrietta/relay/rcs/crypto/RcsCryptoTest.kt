package com.garrietta.relay.rcs.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto tests: NIST/RFC known-answer vectors for the primitives plus
 * envelope round-trip and tamper detection. The one thing these cannot prove
 * is the HMAC input order against Google's servers (see RcsCrypto docs).
 */
class RcsCryptoTest {

    @Test fun `aes-ctr nist vector`() {
        // AES-128-CTR, NIST SP 800-38A F.5.1 inputs. Expected ciphertext
        // verified against OpenSSL (independent implementation).
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val pt = hex("6bc1bee22e409f96e93d7e117393172a")
        val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        assertArrayEquals(hex("874d6191b620e3261bef6864990db6ce"), cipher.doFinal(pt))
    }

    @Test fun `hmac-sha256 rfc4231 vector`() {
        // RFC 4231 test case 1: key = 20x 0x0b, data = "Hi There".
        // Expected digest verified against Python's hmac module.
        val key = ByteArray(20) { 0x0b.toByte() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        assertArrayEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            mac.doFinal("Hi There".toByteArray()),
        )
    }

    @Test fun `hkdf-sha256 rfc5869 vector`() {
        // RFC 5869 Appendix A.1, test case 1 (SHA-256). Verified against the
        // RFC text at rfc-editor.org.
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = RcsCrypto.hkdfSha256(ikm, salt, info, 42)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test fun `ecdh p-256 both sides agree`() {
        val a = RcsCrypto.generateEcdsaKeyPair()
        val b = RcsCrypto.generateEcdsaKeyPair()
        val secretA = RcsCrypto.ecdhSha256(a.private, b.public)
        val secretB = RcsCrypto.ecdhSha256(b.private, a.public)
        assertArrayEquals(secretA, secretB)
        assertTrue(secretA.size == 32) // sha256(DH)
    }

    @Test fun `uncompressed public key is 65 bytes`() {
        val kp = RcsCrypto.generateEcdsaKeyPair()
        val raw = RcsCrypto.uncompressedPublicKey(kp.public)
        assertTrue(raw.size == 65)
        assertTrue(raw[0] == 0x04.toByte())
    }

    @Test fun `envelope round-trips`() {
        val aes = RcsCrypto.generateAesKey()
        val hmac = RcsCrypto.generateHmacKey()
        val pt = "hello rcs".toByteArray()
        val sealed = RcsCrypto.seal(aes, hmac, pt)
        assertTrue(sealed.size == pt.size + 48)
        assertArrayEquals(pt, RcsCrypto.open(aes, hmac, sealed))
    }

    @Test fun `envelope detects tampering`() {
        val aes = RcsCrypto.generateAesKey()
        val hmac = RcsCrypto.generateHmacKey()
        val sealed = RcsCrypto.seal(aes, hmac, "secret".toByteArray())
        sealed[0] = (sealed[0].toInt() xor 0xFF).toByte()
        try {
            RcsCrypto.open(aes, hmac, sealed)
            error("should have thrown")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test fun `envelope rejects wrong key`() {
        val sealed = RcsCrypto.seal(RcsCrypto.generateAesKey(), RcsCrypto.generateHmacKey(), "x".toByteArray())
        try {
            RcsCrypto.open(RcsCrypto.generateAesKey(), RcsCrypto.generateHmacKey(), sealed)
            error("should have thrown")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test fun `ecdsa sign and verify round-trip`() {
        val kp = RcsCrypto.generateEcdsaKeyPair()
        val data = "requestID:1234567890".toByteArray()
        val sig = RcsCrypto.ecdsaSign(kp.private, data)
        assertTrue(RcsCrypto.ecdsaVerify(kp.public, data, sig))
        assertTrue(!RcsCrypto.ecdsaVerify(kp.public, "tampered".toByteArray(), sig))
    }

    @Test fun `ecdsa keys serialize through pkcs8 and x509`() {
        val kp = RcsCrypto.generateEcdsaKeyPair()
        val priv = RcsCrypto.privateKeyFromPkcs8(kp.private.encoded)
        val pub = RcsCrypto.publicKeyFromX509(kp.public.encoded)
        val sig = RcsCrypto.ecdsaSign(priv, "abc".toByteArray())
        assertTrue(RcsCrypto.ecdsaVerify(pub, "abc".toByteArray(), sig))
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
