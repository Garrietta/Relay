package com.garrietta.relay.rcs.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.log2

/**
 * Round-trip test for the AES-GCM chunked media stream: encrypts with a
 * test-only encoder that mirrors the reference's EncryptData
 * ([0x00][log2(chunkSize)] then nonce(12)+ciphertext+tag chunks, 5-byte AAD
 * with last-chunk flag + BE uint32 index), then decrypts with
 * [aesGcmDecryptChunked] and asserts byte equality.
 *
 * This proves the decrypt is self-consistent with the protocol's chunk
 * format. A single-chunk and a multi-chunk vector are covered.
 */
class MediaCryptoTest {

    private val random = SecureRandom()

    /** Test-only chunked encrypt mirroring the reference EncryptData. */
    private fun encryptChunked(key: ByteArray, plain: ByteArray, log2ChunkSize: Int = 15): ByteArray {
        val chunkSize = 1 shl log2ChunkSize
        val rawChunk = chunkSize - 28 // nonce(12) + tag(16) overhead
        val out = mutableListOf<Byte>(0, log2ChunkSize.toByte())
        var index = 0
        var pos = 0
        while (pos < plain.size) {
            val end = minOf(pos + rawChunk, plain.size)
            val isLast = end == plain.size
            val aad = ByteArray(5)
            if (isLast) aad[0] = 1
            aad[1] = (index ushr 24).toByte()
            aad[2] = (index ushr 16).toByte()
            aad[3] = (index ushr 8).toByte()
            aad[4] = index.toByte()
            val nonce = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(aad)
            val ct = cipher.doFinal(plain, pos, end - pos)
            out.addAll(nonce.toList())
            out.addAll(ct.toList())
            pos = end
            index++
        }
        return out.toByteArray()
    }

    @Test
    fun `single chunk round trip`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val plain = ByteArray(1024).also { random.nextBytes(it) }
        val encrypted = encryptChunked(key, plain)
        // Sanity: header is [0x00][15], one chunk of 12 + 1024 + 16.
        assert(encrypted[0] == 0.toByte())
        assert(encrypted[1] == 15.toByte())
        assertArrayEquals(plain, aesGcmDecryptChunked(key, encrypted))
    }

    @Test
    fun `multi chunk round trip`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        // 100KB spans multiple 32KB-28 chunks.
        val plain = ByteArray(100 * 1024).also { random.nextBytes(it) }
        val encrypted = encryptChunked(key, plain)
        assertArrayEquals(plain, aesGcmDecryptChunked(key, encrypted))
    }

    @Test
    fun `jpeg magic survives round trip`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        // Fake JPEG: SOI marker + random scan data.
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(5000).also { random.nextBytes(it) }
        val encrypted = encryptChunked(key, plain)
        val decrypted = aesGcmDecryptChunked(key, encrypted)
        assertArrayEquals(plain, decrypted)
        assert(decrypted[0] == 0xFF.toByte() && decrypted[1] == 0xD8.toByte())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bad header rejected`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        aesGcmDecryptChunked(key, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
    }
}
