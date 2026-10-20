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

    // ---------- parseContentRange + resume verdicts (splice round) ----------

    @Test fun `content range parses start end and total`() {
        val r = parseContentRange("bytes 1024-2047/4096")
        assertEquals(1024L, r?.start)
        assertEquals(2047L, r?.end)
        assertEquals(4096L, r?.total)
    }

    @Test fun `content range star total parses as null total`() {
        val r = parseContentRange("bytes 1024-2047/*")
        assertEquals(1024L, r?.start)
        assertEquals(null, r?.total)
    }

    @Test fun `content range rejects absent and malformed values`() {
        assertEquals(null, parseContentRange(null))
        assertEquals(null, parseContentRange(""))
        assertEquals(null, parseContentRange("bytes */4096")) // 416 form: no range
        assertEquals(null, parseContentRange("items 0-1/2"))
        assertEquals(null, parseContentRange("bytes abc-def/ghi"))
    }

    @Test fun `resume offset must start exactly at the partial size`() {
        // The 200-instead-of-206 class of bug has its 206 twin: a range
        // that starts anywhere else splices two streams together.
        assertTrue(resumeOffsetAcceptable(ContentRange(500, 999, 1000), 500))
        assertFalse(resumeOffsetAcceptable(ContentRange(0, 999, 1000), 500))
        assertFalse(resumeOffsetAcceptable(ContentRange(501, 999, 1000), 500))
        // Header absent: unverifiable here, tolerated — totals still apply.
        assertTrue(resumeOffsetAcceptable(null, 500))
    }

    @Test fun `resume total must equal the assembled size when declared`() {
        assertTrue(resumeTotalAcceptable(ContentRange(500, 999, 1000), 1000))
        assertFalse(resumeTotalAcceptable(ContentRange(500, 999, 1000), 999))
        assertFalse(resumeTotalAcceptable(ContentRange(500, 999, 1000), 1001))
        assertTrue(resumeTotalAcceptable(ContentRange(500, 999, null), 12345))
        assertTrue(resumeTotalAcceptable(null, 1000))
    }

    @Test fun `resume 200 fresh body must cover the partial`() {
        assertTrue(resumeFreshBodyAcceptable(1000, 500))
        assertTrue(resumeFreshBodyAcceptable(500, 500))
        assertFalse(resumeFreshBodyAcceptable(499, 500))
    }

    @Test fun `resumed wire must equal declared size when one is known`() {
        assertTrue(resumedWireSizeAcceptable(usedResume = true, 4096, 4096))
        assertFalse(resumedWireSizeAcceptable(usedResume = true, 614_494, 3_132_125))
        // Single-body downloads keep the #130 smaller-rendition acceptance.
        assertTrue(resumedWireSizeAcceptable(usedResume = false, 614_494, 3_132_125))
        // Thumbnail path: no declared size for the rendition — never fires.
        assertTrue(resumedWireSizeAcceptable(usedResume = true, 1234, 0))
    }

    // ---------- isSingleCompleteImage (splice round) ----------

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        val len = data.size
        out.add(((len shr 24) and 0xFF).toByte())
        out.add(((len shr 16) and 0xFF).toByte())
        out.add(((len shr 8) and 0xFF).toByte())
        out.add((len and 0xFF).toByte())
        type.forEach { out.add(it.code.toByte()) }
        data.forEach { out.add(it) }
        repeat(4) { out.add(0.toByte()) } // CRC — the walk does not verify it
        return out.toByteArray()
    }

    private fun pngFile(idatSize: Int = 4): ByteArray {
        val sig = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        return sig + pngChunk("IHDR", ByteArray(13)) +
            pngChunk("IDAT", ByteArray(idatSize)) + pngChunk("IEND", ByteArray(0))
    }

    @Test fun `single png consumes its whole stream`() {
        assertTrue(isSingleCompleteImage(pngFile()))
    }

    @Test fun `appended second png is rejected`() {
        assertFalse(isSingleCompleteImage(pngFile() + pngFile()))
    }

    @Test fun `mid-stream png splice desyncs the chunk walk`() {
        // Prefix of rendition A + tail of rendition B (different IDAT
        // length): the chunk chain can no longer parse to a clean IEND.
        val a = pngFile(idatSize = 4)
        val b = pngFile(idatSize = 20)
        val spliced = a.copyOf(45) + b.copyOfRange(45, b.size)
        assertFalse(isSingleCompleteImage(spliced))
    }

    private fun jpegSegment(marker: Int, data: ByteArray): ByteArray {
        val len = data.size + 2
        return byteArrayOf(
            0xFF.toByte(), marker.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        ) + data
    }

    private fun jpegFile(entropy: ByteArray): ByteArray {
        var out = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // SOI
        out += jpegSegment(0xE0, ByteArray(14)) // APP0
        // APP1 with an EXIF-style embedded thumbnail (SOI..EOI) — legal,
        // it lives BEFORE SOS and the marker walk skips it by length.
        out += jpegSegment(
            0xE1,
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), 1, 2),
        )
        out += jpegSegment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) // SOS
        out += entropy
        out += byteArrayOf(0xFF.toByte(), 0xD9.toByte()) // EOI
        return out
    }

    @Test fun `jpeg with exif thumbnail and stuffed entropy passes`() {
        val entropy = byteArrayOf(
            0x00, 0x11, 0xFF.toByte(), 0x00, // stuffed FF in scan data
            0x22, 0xFF.toByte(), 0xD0.toByte(), // restart marker RST0
            0x33,
        )
        assertTrue(isSingleCompleteImage(jpegFile(entropy)))
    }

    @Test fun `jpeg with a second SOI after SOS is a splice`() {
        val entropy = byteArrayOf(
            0x00, 0x11,
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), // 2nd image header
            0x22,
        )
        assertFalse(isSingleCompleteImage(jpegFile(entropy)))
    }

    private fun gifFile(): ByteArray {
        val out = mutableListOf<Byte>()
        "GIF89a".forEach { out.add(it.code.toByte()) }
        // LSD: 1x1, packed 0 (no global color table), bg 0, aspect 0.
        out.addAll(listOf(1, 0, 1, 0, 0, 0, 0).map { it.toByte() })
        out.add(0x2C) // image descriptor introducer
        repeat(8) { out.add(0) } // left/top/width/height
        out.add(0) // descriptor packed byte (no local color table)
        out.add(2) // LZW minimum code size
        out.add(2); out.add(0xAA.toByte()); out.add(0xBB.toByte()) // data sub-block
        out.add(0) // sub-block terminator
        out.add(0x3B) // trailer
        return out.toByteArray()
    }

    @Test fun `single gif consumes its whole stream`() {
        assertTrue(isSingleCompleteImage(gifFile()))
    }

    @Test fun `appended second gif is rejected`() {
        assertFalse(isSingleCompleteImage(gifFile() + gifFile()))
    }

    private fun heicBox(type: String, data: ByteArray): ByteArray {
        val size = data.size + 8
        val out = mutableListOf<Byte>()
        out.add(((size shr 24) and 0xFF).toByte())
        out.add(((size shr 16) and 0xFF).toByte())
        out.add(((size shr 8) and 0xFF).toByte())
        out.add((size and 0xFF).toByte())
        type.forEach { out.add(it.code.toByte()) }
        data.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun heicFile(): ByteArray {
        val ftyp = heicBox("ftyp", "isom".map { it.code.toByte() }.toByteArray() + ByteArray(4))
        return ftyp + heicBox("mdat", ByteArray(20))
    }

    @Test fun `heic box chain ending at the file end passes`() {
        assertTrue(isSingleCompleteImage(heicFile()))
    }

    @Test fun `heic with trailing garbage is rejected`() {
        assertFalse(isSingleCompleteImage(heicFile() + byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun `webp and bmp exact-size files pass the consumption gate`() {
        assertTrue(isSingleCompleteImage(webp(declaredSize = 100, actualPad = 92)))
        assertTrue(isSingleCompleteImage(bmp(declaredSize = 26, actualSize = 26)))
        assertFalse(isSingleCompleteImage(webp(declaredSize = 90, actualPad = 92)))
    }

    @Test fun `unknown bytes are not a single complete image`() {
        assertFalse(isSingleCompleteImage(ByteArray(32)))
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
