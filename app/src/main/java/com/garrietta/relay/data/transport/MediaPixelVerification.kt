package com.garrietta.relay.data.transport

import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * PIXEL-TRUTH VERIFICATION (Garrett 2026-10-20): structural validation
 * cannot see an authentically garbled image — the Papaw Ambrose
 * ultrasound passed every gate in the pipeline (valid JPEG structure,
 * intact trailer, exact wire-size accounting, full decode) while its
 * scan data rendered as coarse gray blocks. Headers can be perfect and
 * pixels wrong; the only ground truth for "what should this image look
 * like" is an INDEPENDENT rendition of the same image, and every RCS
 * media message carries one: the server thumbnail (MediaContent inline
 * field 7 / thumbnailMediaID field 9).
 *
 * A fingerprint is a 16x16 grid of average colors over the image's
 * CENTER SQUARE (server thumbnails are often center-cropped squares;
 * fingerprinting both sides over the center square makes a scaled
 * rendition and a center-cropped one comparable). Each cell stores
 * (Y, Cb, Cr) in 0..1, computed from area-weighted, white-composited
 * pixel averages — white compositing puts transparent sticker PNGs and
 * server-flattened thumbnails on equal terms. The pure functions below
 * operate on raw ARGB pixel arrays and are JVM-unit-tested; the only
 * Android touchpoint is [imageFingerprintOfBytes].
 *
 * Agreement thresholds, calibrated against the synthetic cases in
 * MediaPixelVerificationTest: two renditions of the same image (different
 * resolutions, recompression quantization) land at a mean difference of
 * ~0.005–0.02; gray-block garble lands at ~0.15+ mean with most cells
 * divergent; a different image lands similarly high. The agree line sits
 * ~3x above honest-rendition noise and ~2.5x below garble.
 */

/** Fingerprint grid edge: 16x16 average-color cells. */
const val MEDIA_FP_GRID = 16

/** Mean per-channel fingerprint difference at or below which renditions agree. */
const val FP_MEAN_AGREE_MAX = 0.06

/** Per-cell mean difference above which one cell counts as divergent. */
const val FP_CELL_DIVERGENT_MIN = 0.20

/** Divergent-cell fraction above which renditions disagree regardless of mean. */
const val FP_DIVERGENT_FRACTION_MAX = 0.30

/**
 * Computes the fingerprint of an ARGB pixel array ([pixels] row-major,
 * [width] x [height]). Returns null when the image is too small to
 * fingerprint (center-square side smaller than the grid) or the pixel
 * array is undersized. Pure-Kotlin, JVM-testable.
 */
fun imageFingerprint(
    pixels: IntArray,
    width: Int,
    height: Int,
    grid: Int = MEDIA_FP_GRID,
): FloatArray? {
    if (width < 1 || height < 1 || grid < 1) return null
    if (pixels.size < width * height) return null
    val side = minOf(width, height)
    if (side < grid) return null
    val ox = (width - side) / 2.0
    val oy = (height - side) / 2.0
    val cellSize = side.toDouble() / grid
    val out = FloatArray(grid * grid * 3)
    var idx = 0
    for (gy in 0 until grid) {
        val y0 = oy + gy * cellSize
        val y1 = y0 + cellSize
        val py0 = floor(y0).toInt().coerceIn(0, height - 1)
        val py1 = ceil(y1).toInt().coerceIn(py0 + 1, height)
        for (gx in 0 until grid) {
            val x0 = ox + gx * cellSize
            val x1 = x0 + cellSize
            val px0 = floor(x0).toInt().coerceIn(0, width - 1)
            val px1 = ceil(x1).toInt().coerceIn(px0 + 1, width)
            var rSum = 0.0
            var gSum = 0.0
            var bSum = 0.0
            var wSum = 0.0
            for (py in py0 until py1) {
                val wy = minOf(y1, py + 1.0) - maxOf(y0, py.toDouble())
                if (wy <= 0.0) continue
                val rowBase = py * width
                for (px in px0 until px1) {
                    val wx = minOf(x1, px + 1.0) - maxOf(x0, px.toDouble())
                    if (wx <= 0.0) continue
                    val w = wx * wy
                    val argb = pixels[rowBase + px]
                    val a = (argb ushr 24) and 0xFF
                    // Composite over white (a=255 keeps the channel exact).
                    val r = ((argb shr 16) and 0xFF) * a + 255 * (255 - a)
                    val g = ((argb shr 8) and 0xFF) * a + 255 * (255 - a)
                    val b = (argb and 0xFF) * a + 255 * (255 - a)
                    rSum += r / 255.0 * w
                    gSum += g / 255.0 * w
                    bSum += b / 255.0 * w
                    wSum += w
                }
            }
            if (wSum <= 0.0) return null
            val rN = (rSum / wSum) / 255.0
            val gN = (gSum / wSum) / 255.0
            val bN = (bSum / wSum) / 255.0
            val y = 0.299 * rN + 0.587 * gN + 0.114 * bN
            val cb = (0.5 + (bN - y) * 0.564).coerceIn(0.0, 1.0)
            val cr = (0.5 + (rN - y) * 0.713).coerceIn(0.0, 1.0)
            out[idx++] = y.toFloat()
            out[idx++] = cb.toFloat()
            out[idx++] = cr.toFloat()
        }
    }
    return out
}

/** Mean absolute difference across all cells and channels, 0..1. */
fun fingerprintMeanDiff(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size && a.isNotEmpty()) { "fingerprint size mismatch" }
    var sum = 0.0
    for (i in a.indices) sum += abs(a[i] - b[i])
    return sum / a.size
}

/** Fraction of cells whose mean channel difference exceeds [FP_CELL_DIVERGENT_MIN]. */
fun fingerprintDivergentFraction(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size && a.size % 3 == 0 && a.isNotEmpty()) {
        "fingerprint size mismatch"
    }
    val cells = a.size / 3
    var divergent = 0
    for (c in 0 until cells) {
        val d = (abs(a[c * 3] - b[c * 3]) +
            abs(a[c * 3 + 1] - b[c * 3 + 1]) +
            abs(a[c * 3 + 2] - b[c * 3 + 2])) / 3.0
        if (d > FP_CELL_DIVERGENT_MIN) divergent++
    }
    return divergent.toDouble() / cells
}

/**
 * True when two fingerprints describe the SAME image: close on average
 * AND with no large divergent region. Both criteria matter — a mostly
 * matching image with one garbled band keeps a low mean but a high
 * divergent fraction, and a uniformly shifted image does the reverse.
 */
fun fingerprintsAgree(a: FloatArray, b: FloatArray): Boolean =
    a.size == b.size && a.isNotEmpty() &&
        fingerprintMeanDiff(a, b) <= FP_MEAN_AGREE_MAX &&
        fingerprintDivergentFraction(a, b) <= FP_DIVERGENT_FRACTION_MAX

/**
 * Decodes [bytes] (bounded: the long edge lands at ~160–320 px via
 * inSampleSize — the fingerprint only needs cell averages, never full
 * resolution) and returns its fingerprint, or null when the bytes do
 * not decode or are too small. Runs wherever the media pipeline runs
 * (Dispatchers.IO), never the UI thread. A garbled-but-decodable image
 * still fingerprints — that is the point: its fingerprint is what the
 * ground-truth comparison rejects.
 */
fun imageFingerprintOfBytes(bytes: ByteArray): FloatArray? {
    if (bytes.size < 12) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 160 &&
            bounds.outHeight / (sample * 2) >= 160
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            imageFingerprint(px, w, h)
        } finally {
            bmp.recycle()
        }
    } catch (_: Exception) {
        null
    }
}
