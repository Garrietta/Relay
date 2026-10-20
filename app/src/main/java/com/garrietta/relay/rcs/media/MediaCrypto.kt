package com.garrietta.relay.rcs.media

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM chunked media ENCRYPTION — the exact inverse of
 * [aesGcmDecryptChunked] (Garrett 2026-10-19: the attachment-send path).
 * The stream layout is identical: `[0x00][log2(chunkSize)]` then chunks of
 * `nonce(12) + ciphertext+tag`, AAD = 5 bytes with aad[0] = 1 on the LAST
 * chunk and aad[1:5] = big-endian uint32 chunk index. Chunk size is fixed
 * at 64 KiB (log2 = 16). Round-trips byte-for-byte through the decryptor.
 */
fun aesGcmEncryptChunked(key: ByteArray, plaintext: ByteArray): ByteArray {
    require(key.size == 32) { "media encryption key must be 32 bytes (got ${key.size})" }
    val log2 = 16
    val chunkSize = 1 shl log2
    val rng = SecureRandom()
    val out = ByteArrayOutputStream()
    out.write(0x00)
    out.write(log2)
    var pos = 0
    var index = 0
    while (pos < plaintext.size) {
        val end = minOf(pos + chunkSize, plaintext.size)
        val isLast = end == plaintext.size
        val aad = ByteArray(5)
        if (isLast) aad[0] = 1
        aad[1] = (index ushr 24).toByte()
        aad[2] = (index ushr 16).toByte()
        aad[3] = (index ushr 8).toByte()
        aad[4] = index.toByte()
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(aad)
        out.write(nonce)
        out.write(cipher.doFinal(plaintext, pos, end - pos))
        pos = end
        index++
    }
    return out.toByteArray()
}

/**
 * AES-GCM chunked media decryption — clean-room from the protocol's media
 * flow (media.go DecryptData). The encrypted stream is:
 *
 *   [0x00][log2(chunkSize)] then chunks of nonce(12) + ciphertext+tag
 *
 * Each chunk's AAD is 5 bytes: aad[0] = 1 on the LAST chunk (0 otherwise),
 * aad[1:5] = big-endian uint32 chunk index. JCA AES/GCM/NoPadding with a
 * 128-bit tag matches Go's cipher.NewGCM layout (12-byte nonce, 16-byte
 * tag appended).
 */
fun aesGcmDecryptChunked(key: ByteArray, encrypted: ByteArray): ByteArray {
    require(key.size == 32) { "media decryption key must be 32 bytes (got ${key.size})" }
    require(encrypted.size >= 2 && encrypted[0] == 0.toByte()) {
        "bad media stream header"
    }
    // Byte is signed in Kotlin; the header byte is an unsigned log2 size.
    val chunkSize = 1 shl (encrypted[1].toInt() and 0xFF)
    require(chunkSize > 0) { "bad media chunk size" }
    val out = ByteArrayOutputStream()
    var pos = 2
    var index = 0
    while (pos < encrypted.size) {
        val end = minOf(pos + chunkSize, encrypted.size)
        val isLast = end == encrypted.size
        val aad = ByteArray(5)
        if (isLast) aad[0] = 1
        aad[1] = (index ushr 24).toByte()
        aad[2] = (index ushr 16).toByte()
        aad[3] = (index ushr 8).toByte()
        aad[4] = index.toByte()
        val chunk = encrypted.copyOfRange(pos, end)
        require(chunk.size >= 12) { "truncated media chunk #$index" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, chunk, 0, 12),
        )
        cipher.updateAAD(aad)
        out.write(cipher.doFinal(chunk, 12, chunk.size - 12))
        pos = end
        index++
    }
    return out.toByteArray()
}
