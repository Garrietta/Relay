package com.garrietta.relay.data.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the media deep-dive validation (2026-10-19).
 * Only pure-Kotlin helpers are tested here — anything touching
 * BitmapFactory throws on the JVM (Android stubs), so decode gates are
 * covered on-device.
 */
class MediaValidationTest {

    // ---------- wireExpectedPlaintext ----------

    /** Builds a synthetic encrypted stream: [0x00][log2] + [nChunks] wire chunks. */
    private fun fakeEncrypted(log2Chunk: Int, chunks: Int, lastChunkBytes: Int = -1): ByteArray {
        val c = 1 shl log2Chunk
        val out = mutableListOf(0.toByte(), log2Chunk.toByte())
        repeat(chunks) { out.addAll(ByteArray(c) { 0x41.toByte() }.toList()) }
        if (lastChunkBytes > 0) out.addAll(ByteArray(lastChunkBytes) { 0x41.toByte() }.toList())
        return out.toByteArray()
    }

    @Test fun `wire plaintext strips 28 bytes per chunk`() {
        // 3 full 4096B wire chunks -> 3 * (4096 - 28) plaintext.
        val enc = fakeEncrypted(12, 3)
        assertEquals(3L * (4096 - 28), wireExpectedPlaintext(enc))
    }

    @Test fun `wire plaintext accounts for a partial last chunk`() {
        // 2 full chunks + 1000 trailing bytes = 3 chunks on the wire.
        val enc = fakeEncrypted(12, 2, lastChunkBytes = 1000)
        val total = 2 * 4096 + 1000
        assertEquals((total - 3 * 28).toLong(), wireExpectedPlaintext(enc))
    }

    @Test fun `maria case overhead math is exact`() {
        // Garrett's Maria screenshot: 2,032,110 declared vs 1,279,386
        // plaintext. The gap is exactly 26,883 chunks x 28B overhead —
        // i.e. the declared size is the ENCRYPTED wire size, so comparing
        // it against plaintext misfires deterministically.
        val gap = 2_032_110L - 1_279_386L
        assertEquals(0L, gap % 28L)
        assertEquals(26_883L, gap / 28L)
    }

    @Test fun `wire plaintext rejects a bad header`() {
        try {
            wireExpectedPlaintext(byteArrayOf(0x01))
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- hasIntactImageTrailer ----------

    private fun jpeg(complete: Boolean): ByteArray {
        val b = mutableListOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        repeat(20) { b.add(0x00.toByte()) }
        if (complete) { b.add(0xFF.toByte()); b.add(0xD9.toByte()) }
        return b.toByteArray()
    }

    private fun png(complete: Boolean): ByteArray {
        val b = mutableListOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        repeat(20) { b.add(0x00.toByte()) }
        if (complete) {
            // IEND chunk: length 0 + "IEND" + CRC AE426082.
            val iend = listOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)
            b.addAll(iend.map { it.toByte() })
        }
        return b.toByteArray()
    }

    private fun gif(complete: Boolean): ByteArray {
        val b = "GIF89a".map { it.code.toByte() }.toMutableList()
        repeat(20) { b.add(0x00.toByte()) }
        if (complete) b.add(0x3B.toByte())
        return b.toByteArray()
    }

    private fun webp(declaredSize: Int, actualPad: Int): ByteArray {
        val b = mutableListOf<Byte>()
        b.addAll("RIFF".map { it.code.toByte() })
        // LE32 chunk size.
        b.add((declaredSize and 0xFF).toByte())
        b.add(((declaredSize shr 8) and 0xFF).toByte())
        b.add(((declaredSize shr 16) and 0xFF).toByte())
        b.add(((declaredSize shr 24) and 0xFF).toByte())
        b.addAll("WEBPVP8 ".map { it.code.toByte() })
        repeat(actualPad) { b.add(0x00.toByte()) }
        return b.toByteArray()
    }

    private fun bmp(declaredSize: Int, actualSize: Int): ByteArray {
        val b = mutableListOf<Byte>(0x42.toByte(), 0x4D.toByte())
        b.add((declaredSize and 0xFF).toByte())
        b.add(((declaredSize shr 8) and 0xFF).toByte())
        b.add(((declaredSize shr 16) and 0xFF).toByte())
        b.add(((declaredSize shr 24) and 0xFF).toByte())
        repeat(actualSize - 6) { b.add(0x00.toByte()) }
        return b.toByteArray()
    }

    @Test fun `jpeg trailer requires EOI`() {
        assertTrue(hasIntactImageTrailer(jpeg(true)))
        assertFalse(hasIntactImageTrailer(jpeg(false)))
    }

    @Test fun `png trailer requires IEND`() {
        assertTrue(hasIntactImageTrailer(png(true)))
        assertFalse(hasIntactImageTrailer(png(false)))
    }

    @Test fun `gif trailer requires 0x3B`() {
        assertTrue(hasIntactImageTrailer(gif(true)))
        assertFalse(hasIntactImageTrailer(gif(false)))
    }

    @Test fun `webp trailer checks RIFF size`() {
        val good = webp(declaredSize = 100, actualPad = 92) // 16 header + 92 = 108; 100 + 8 = 108
        assertTrue(hasIntactImageTrailer(good))
        val bad = webp(declaredSize = 90, actualPad = 92)
        assertFalse(hasIntactImageTrailer(bad))
    }

    @Test fun `bmp trailer checks bfSize`() {
        assertTrue(hasIntactImageTrailer(bmp(declaredSize = 26, actualSize = 26)))
        assertFalse(hasIntactImageTrailer(bmp(declaredSize = 20, actualSize = 26)))
    }

    @Test fun `heic has no trailer check`() {
        val b = ByteArray(12)
        b[4] = 0x66.toByte(); b[5] = 0x74.toByte(); b[6] = 0x79.toByte(); b[7] = 0x70.toByte()
        assertTrue(hasIntactImageTrailer(b))
    }

    @Test fun `unknown bytes fail every gate`() {
        val b = ByteArray(32)
        assertFalse(isValidImageBytes(b))
        assertFalse(hasIntactImageTrailer(b))
    }

    @Test fun `magic sniff still recognizes all formats`() {
        assertTrue(isValidImageBytes(jpeg(true)))
        assertTrue(isValidImageBytes(png(true)))
        assertTrue(isValidImageBytes(gif(true)))
        assertTrue(isValidImageBytes(webp(100, 92)))
        assertTrue(isValidImageBytes(bmp(26, 26)))
        val heic = ByteArray(12)
        heic[4] = 0x66.toByte(); heic[5] = 0x74.toByte(); heic[6] = 0x79.toByte(); heic[7] = 0x70.toByte()
        assertTrue(isValidImageBytes(heic))
        assertFalse(isValidImageBytes(ByteArray(11)))
    }
}
