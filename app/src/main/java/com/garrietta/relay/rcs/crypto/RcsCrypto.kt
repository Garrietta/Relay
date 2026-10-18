package com.garrietta.relay.rcs.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for Relay's RCS transport — clean-room
 * implementation using JCA only (no native code).
 *
 * Message envelope (per the Messages-for-web protocol spec):
 *   sealed = AES-CTR(aesKey, iv, plaintext) ‖ iv(16B) ‖ HMAC-SHA256(hmacKey, …)
 *
 * DOCUMENTED ASSUMPTION (unverified against Google's servers): the HMAC is
 * computed over (ciphertext ‖ iv), matching the wire order. If the first live
 * pairing fails MAC verification, the (iv ‖ ciphertext) order is suspect #1.
 */
object RcsCrypto {
    private val random = SecureRandom()

    fun generateAesKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun generateHmacKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun generateIv(): ByteArray = ByteArray(16).also(random::nextBytes)

    /** Seals plaintext → ciphertext ‖ iv ‖ mac. */
    fun seal(aesKey: ByteArray, hmacKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(aesKey.size == 32 && hmacKey.size == 32)
        val iv = generateIv()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(ciphertext)
        mac.update(iv)
        val tag = mac.doFinal()
        return ciphertext + iv + tag
    }

    /** Opens sealed bytes; throws [SecurityException] if the MAC does not verify. */
    fun open(aesKey: ByteArray, hmacKey: ByteArray, sealed: ByteArray): ByteArray {
        require(aesKey.size == 32 && hmacKey.size == 32)
        require(sealed.size >= 48) { "sealed payload too short" }
        val ciphertext = sealed.copyOfRange(0, sealed.size - 48)
        val iv = sealed.copyOfRange(sealed.size - 48, sealed.size - 32)
        val tag = sealed.copyOfRange(sealed.size - 32, sealed.size)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(ciphertext)
        mac.update(iv)
        val expected = mac.doFinal()
        if (!constantTimeEquals(tag, expected)) throw SecurityException("RCS envelope MAC mismatch")
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    // ---- ECDSA P-256 (Tachyon refresh key) ----

    fun generateEcdsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        return kpg.generateKeyPair()
    }

    fun ecdsaSign(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey, random)
        sig.update(data)
        return sig.sign()
    }

    fun ecdsaVerify(publicKey: java.security.PublicKey, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        }.getOrDefault(false)

    fun privateKeyFromPkcs8(bytes: ByteArray): java.security.PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun publicKeyFromX509(bytes: ByteArray): java.security.PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
}
