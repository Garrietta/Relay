package com.garrietta.relay.data.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * JVM unit tests for the pixel-truth comparator (Garrett 2026-10-20).
 * The comparator is pure (ARGB pixel arrays in, fingerprint out), so the
 * whole calibration is testable here: honest renditions of one scene at
 * different resolutions/recompressions must AGREE; gray-block garble and
 * a different scene must DISAGREE; a center-cropped thumbnail must agree
 * with the full image (the fingerprint samples the center square).
 */
class MediaPixelVerificationTest {

    // ---------- synthetic scene ----------

    private fun argb(r: Double, g: Double, b: Double): Int {
        fun c(x: Double) = x.roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /**
     * A continuous "photo-like" scene sampled per pixel: vertical
     * gradient + three gaussian color blobs + fine deterministic
     * texture. Because the scene is a function of (u, v), any resolution
     * renders the same picture. Variant 1 is a DIFFERENT picture (flipped
     * gradient, blobs elsewhere).
     */
    private fun renderScene(w: Int, h: Int, variant: Int = 0): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val v = y.toDouble() / h
            for (x in 0 until w) {
                val u = x.toDouble() / w
                val vv = if (variant == 0) v else 1.0 - v
                var r = 20 + 30 * vv
                var g = 40 + 120 * vv
                var b = 90 + 60 * (1 - vv)
                fun blob(cx: Double, cy: Double, s: Double, cr: Double, cg: Double, cb: Double) {
                    val dx = u - cx
                    val dy = v - cy
                    val wgt = exp(-(dx * dx + dy * dy) / (s * s))
                    r += cr * wgt
                    g += cg * wgt
                    b += cb * wgt
                }
                if (variant == 0) {
                    blob(0.30, 0.35, 0.22, 200.0, 90.0, 30.0)
                    blob(0.72, 0.60, 0.26, -20.0, 140.0, 60.0)
                    blob(0.55, 0.20, 0.15, 120.0, -30.0, 160.0)
                } else {
                    blob(0.65, 0.30, 0.24, 30.0, 60.0, 210.0)
                    blob(0.25, 0.70, 0.20, 210.0, 160.0, -20.0)
                    blob(0.45, 0.55, 0.30, -40.0, -20.0, 90.0)
                }
                val tex = 6.0 * sin(u * 91.0 + v * 57.0)
                px[y * w + x] = argb(r + tex, g + tex, b + tex)
            }
        }
        return px
    }

    /** Exact box downscale — what a server thumbnail rendition is. */
    private fun boxDownscale(px: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
        require(w % tw == 0 && h % th == 0)
        val bw = w / tw
        val bh = h / th
        val out = IntArray(tw * th)
        for (ty in 0 until th) {
            for (tx in 0 until tw) {
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in ty * bh until (ty + 1) * bh) {
                    for (x in tx * bw until (tx + 1) * bw) {
                        val p = px[y * w + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val n = (bw * bh).toLong()
                out[ty * tw + tx] = (0xFF shl 24) or
                    ((r / n).toInt() shl 16) or
                    ((g / n).toInt() shl 8) or
                    (b / n).toInt()
            }
        }
        return out
    }

    /** Recompression-style degradation: quantize every channel. */
    private fun quantize(px: IntArray, step: Int): IntArray = IntArray(px.size) { i ->
        val p = px[i]
        fun q(c: Int) = ((c + step / 2) / step * step).coerceIn(0, 255)
        (0xFF shl 24) or
            (q((p shr 16) and 0xFF) shl 16) or
            (q((p shr 8) and 0xFF) shl 8) or
            q(p and 0xFF)
    }

    /**
     * The Papaw failure shape: most of the image replaced by flat
     * mid-gray blocks while a couple of early blocks (the "headers")
     * stay intact.
     */
    private fun garbleBlocks(px: IntArray, w: Int, h: Int, bw: Int, bh: Int): IntArray {
        val out = px.copyOf()
        val gray = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        var bi = 0
        var by = 0
        while (by < h) {
            var bx = 0
            while (bx < w) {
                if (bi >= 2 && bi % 4 != 3) {
                    for (y in by until minOf(by + bh, h)) {
                        for (x in bx until minOf(bx + bw, w)) {
                            out[y * w + x] = gray
                        }
                    }
                }
                bx += bw
                bi++
            }
            by += bh
        }
        return out
    }

    private fun fp(px: IntArray, w: Int, h: Int): FloatArray =
        imageFingerprint(px, w, h) ?: error("expected a fingerprint for ${w}x$h")

    // ---------- tests ----------

    @Test fun `fingerprint shape is grid squared times three`() {
        val f = fp(renderScene(64, 48), 64, 48)
        assertEquals(MEDIA_FP_GRID * MEDIA_FP_GRID * 3, f.size)
    }

    @Test fun `identical pixels agree exactly`() {
        val scene = renderScene(320, 240)
        val a = fp(scene, 320, 240)
        val b = fp(scene, 320, 240)
        assertEquals(0.0, fingerprintMeanDiff(a, b), 0.0)
        assertTrue(fingerprintsAgree(a, b))
    }

    @Test fun `same scene thumbnail rendition at another resolution agrees`() {
        val full = renderScene(320, 240)
        val thumb = quantize(boxDownscale(full, 320, 240, 80, 60), 5)
        val a = fp(full, 320, 240)
        val b = fp(thumb, 80, 60)
        val diff = fingerprintMeanDiff(a, b)
        // Honest renditions must clear the agree line with headroom —
        // this is the calibration the production threshold rests on.
        assertTrue("mean diff $diff should be well under $FP_MEAN_AGREE_MAX",
            diff < FP_MEAN_AGREE_MAX * 0.5)
        assertTrue(fingerprintsAgree(a, b))
    }

    @Test fun `same scene rendered small agrees`() {
        val a = fp(renderScene(320, 240), 320, 240)
        val b = fp(quantize(renderScene(96, 72), 5), 96, 72)
        assertTrue(fingerprintsAgree(a, b))
    }

    @Test fun `center cropped thumbnail agrees with the full image`() {
        val full = renderScene(320, 240)
        // Center square of the 320x240 scene is 240x240 at x offset 40 —
        // what a square server thumbnail of this image contains.
        val crop = IntArray(240 * 240)
        for (y in 0 until 240) {
            for (x in 0 until 240) {
                crop[y * 240 + x] = full[y * 320 + (x + 40)]
            }
        }
        val thumb = quantize(boxDownscale(crop, 240, 240, 60, 60), 5)
        assertTrue(fingerprintsAgree(fp(full, 320, 240), fp(thumb, 60, 60)))
    }

    @Test fun `gray block garble disagrees strongly`() {
        val full = renderScene(320, 240)
        val garbled = garbleBlocks(full, 320, 240, 40, 30)
        val a = fp(full, 320, 240)
        val b = fp(garbled, 320, 240)
        assertFalse(fingerprintsAgree(a, b))
        val diff = fingerprintMeanDiff(a, b)
        assertTrue("garble mean diff $diff should clear the agree line",
            diff > FP_MEAN_AGREE_MAX * 1.5)
    }

    @Test fun `different image disagrees`() {
        val a = fp(renderScene(320, 240, variant = 0), 320, 240)
        val b = fp(renderScene(320, 240, variant = 1), 320, 240)
        assertFalse(fingerprintsAgree(a, b))
        assertTrue(fingerprintMeanDiff(a, b) > FP_MEAN_AGREE_MAX)
    }

    @Test fun `garble of the thumbnail does not agree with the full image`() {
        // The symmetric case: ground truth intact, download garbled at
        // thumbnail scale too — either direction must be caught.
        val full = renderScene(320, 240)
        val thumbGarbled = garbleBlocks(boxDownscale(full, 320, 240, 80, 60), 80, 60, 10, 10)
        assertFalse(fingerprintsAgree(fp(full, 320, 240), fp(thumbGarbled, 80, 60)))
    }

    @Test fun `transparent pixels composite over white`() {
        val w = 64
        val h = 64
        val halfTransparent = IntArray(w * h) { i ->
            if (i % w < w / 2) 0x00000000 else 0xFFFFFFFF.toInt()
        }
        val allWhite = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val a = fp(halfTransparent, w, h)
        val b = fp(allWhite, w, h)
        assertEquals(0.0, fingerprintMeanDiff(a, b), 0.0)
        assertTrue(fingerprintsAgree(a, b))
    }

    @Test fun `too small to fingerprint returns null`() {
        assertNull(imageFingerprint(IntArray(8 * 8) { 0xFFFFFFFF.toInt() }, 8, 8))
        // Short side below the grid: a wide sliver cannot fingerprint.
        assertNull(imageFingerprint(IntArray(200 * 12) { 0xFFFFFFFF.toInt() }, 200, 12))
        // Undersized pixel array.
        assertNull(imageFingerprint(IntArray(10), 100, 100))
        assertNotNull(imageFingerprint(IntArray(16 * 16) { 0xFFFFFFFF.toInt() }, 16, 16))
    }
}
