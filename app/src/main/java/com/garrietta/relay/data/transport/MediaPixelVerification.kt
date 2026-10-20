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
 *
 * FALSE-POSITIVE HARDENING (Garrett 2026-10-20, later): the aligned
 * center-square comparison alone terminally rejected a legitimate photo
 * Mom shared — the aligned grid assumes the thumbnail is the same
 * framing AND the same orientation as BitmapFactory's raw decode, and
 * both assumptions fail for real photos:
 *  - OFF-CENTER CROPS: a server thumbnail cropped from the top/bottom/
 *    edge of the frame (not the center) shifts every cell. The set
 *    therefore also fingerprints the start- and end-anchored squares
 *    ("windows"); agreement with ANY aligned window is still STRICT.
 *  - ORIENTATION: full images carry EXIF orientation that
 *    BitmapFactory does not apply, while server thumbnails are baked
 *    upright — an oriented photo fingerprints sideways against its own
 *    thumbnail. A whole-frame color DISTRIBUTION (per-channel
 *    histograms over the entire frame) is invariant to rotation, flips
 *    and crops; it is the second, weaker signal.
 * A candidate is STRICT when an aligned grid agrees, DISTRIBUTION when
 * only the distribution agrees, NONE when both disagree. Calibrated on
 * the synthetic suite: a 90°-rotated rendition sits at distribution
 * distance ~0.004, an off-center crop is rescued by the windows at
 * STRICT, gray-block garble sits at ~0.5 distribution distance with a
 * ~0.1 aligned mean, and a different image at ~0.25 / ~0.19 — the
 * distribution line at 0.20 keeps ~2.5x margin under garble. Heavily
 * PADDED renditions (letterbox bars) land just above the line (~0.21)
 * and stay rejected: no server behavior observed pads thumbnails, and
 * the margin under garble is worth more than that rescue.
 */

/** Fingerprint grid edge: 16x16 average-color cells. */
const val MEDIA_FP_GRID = 16

/** Mean per-channel fingerprint difference at or below which renditions agree. */
const val FP_MEAN_AGREE_MAX = 0.06

/** Per-cell mean difference above which one cell counts as divergent. */
const val FP_CELL_DIVERGENT_MIN = 0.20

/** Divergent-cell fraction above which renditions disagree regardless of mean. */
const val FP_DIVERGENT_FRACTION_MAX = 0.30

/** Bins per channel in the whole-frame distribution fingerprint. */
const val FP_DIST_BINS = 8

/**
 * Whole-frame distribution distance (mean per-channel half-L1 between
 * histograms, 0..1) at or below which two renditions corroborate each
 * other even when no aligned grid agrees. Calibrated in the file header:
 * rotated/cropped honest renditions land at ~0.0–0.16, garble at ~0.5,
 * a different image at ~0.25.
 */
const val FP_DIST_AGREE_MAX = 0.20

/**
 * Computes the fingerprint of an ARGB pixel array ([pixels] row-major,
 * [width] x [height]) over its CENTER SQUARE. Returns null when the
 * image is too small to fingerprint (center-square side smaller than
 * the grid) or the pixel array is undersized. Pure-Kotlin, JVM-testable.
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
    return imageFingerprintWindow(
        pixels, width, height,
        originX = (width - side) / 2.0,
        originY = (height - side) / 2.0,
        side = side.toDouble(),
        grid = grid,
    )
}

/**
 * Computes the fingerprint of the square window whose top-left corner is
 * ([originX], [originY]) and whose side is [side] (all in source-pixel
 * units, fractional origins allowed — the sampling is area-weighted).
 * This is the engine under [imageFingerprint]; the anchored windows in
 * [PixelFingerprintSet] exist so an off-center-cropped thumbnail can be
 * matched against the square of the full image it was actually cropped
 * from. Same null contract as [imageFingerprint].
 */
fun imageFingerprintWindow(
    pixels: IntArray,
    width: Int,
    height: Int,
    originX: Double,
    originY: Double,
    side: Double,
    grid: Int = MEDIA_FP_GRID,
): FloatArray? {
    if (width < 1 || height < 1 || grid < 1) return null
    if (pixels.size < width * height) return null
    if (side < grid || side <= 0.0) return null
    val ox = originX
    val oy = originY
    val cellSize = side / grid
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
 * Whole-frame color distribution of an ARGB pixel array: per-channel
 * (Y, Cb, Cr) histograms with [bins] bins each, every channel normalized
 * to sum 1, laid out as [Y bins | Cb bins | Cr bins]. Computed over the
 * ENTIRE frame with the same white compositing as the grid fingerprint.
 * A distribution has no geometry — it cannot see arrangement — which is
 * exactly what makes it invariant to the rotations, flips and crops
 * that defeat the aligned grid, and exactly why it is only ever the
 * SECOND signal (see [pixelAgreement]). Pure-Kotlin, JVM-testable.
 */
fun imageDistribution(
    pixels: IntArray,
    width: Int,
    height: Int,
    bins: Int = FP_DIST_BINS,
): FloatArray? {
    if (width < 1 || height < 1 || bins < 1) return null
    if (pixels.size < width * height) return null
    val counts = DoubleArray(3 * bins)
    val total = width * height
    for (i in 0 until total) {
        val argb = pixels[i]
        val a = (argb ushr 24) and 0xFF
        // Composite over white, normalized to 0..1 (same math as the
        // grid fingerprint: channel*a + 255*(255-a), over 255*255).
        val rN = (((argb shr 16) and 0xFF) * a + 255 * (255 - a)) / 65025.0
        val gN = (((argb shr 8) and 0xFF) * a + 255 * (255 - a)) / 65025.0
        val bN = ((argb and 0xFF) * a + 255 * (255 - a)) / 65025.0
        val y = 0.299 * rN + 0.587 * gN + 0.114 * bN
        val cb = (0.5 + (bN - y) * 0.564).coerceIn(0.0, 1.0)
        val cr = (0.5 + (rN - y) * 0.713).coerceIn(0.0, 1.0)
        counts[(y * bins).toInt().coerceIn(0, bins - 1)] += 1.0
        counts[bins + (cb * bins).toInt().coerceIn(0, bins - 1)] += 1.0
        counts[2 * bins + (cr * bins).toInt().coerceIn(0, bins - 1)] += 1.0
    }
    return FloatArray(3 * bins) { (counts[it] / total).toFloat() }
}

/**
 * Distance between two distributions: the mean over the three channels
 * of the half-L1 histogram distance, 0..1 (0 = identical palettes,
 * 1 = disjoint palettes).
 */
fun distributionDistance(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size && a.isNotEmpty() && a.size % 3 == 0) {
        "distribution size mismatch"
    }
    val bins = a.size / 3
    var total = 0.0
    for (ch in 0 until 3) {
        var l1 = 0.0
        for (i in 0 until bins) l1 += abs(a[ch * bins + i] - b[ch * bins + i])
        total += 0.5 * l1
    }
    return total / 3.0
}

/** True when two distributions are close enough to be the same palette. */
fun distributionsAgree(a: FloatArray, b: FloatArray): Boolean =
    a.size == b.size && a.isNotEmpty() &&
        distributionDistance(a, b) <= FP_DIST_AGREE_MAX

/**
 * Everything the pixel-truth layer knows about one rendition: the
 * center-square grid fingerprint, the grid fingerprints of the
 * start/end-anchored squares (empty for a square image — its only
 * square is the center one), and the whole-frame distribution.
 */
class PixelFingerprintSet(
    val center: FloatArray,
    val windows: List<FloatArray>,
    val distribution: FloatArray,
)

/**
 * Builds the [PixelFingerprintSet] of an ARGB pixel array, or null when
 * the image is too small to fingerprint (same contract as
 * [imageFingerprint]).
 */
fun imagePixelFingerprints(
    pixels: IntArray,
    width: Int,
    height: Int,
): PixelFingerprintSet? {
    val center = imageFingerprint(pixels, width, height) ?: return null
    val distribution = imageDistribution(pixels, width, height) ?: return null
    val side = minOf(width, height).toDouble()
    val windows = mutableListOf<FloatArray>()
    if (width > height) {
        // Squares anchored at the left and right edges (a landscape
        // image's top/bottom are fully spanned by every square).
        imageFingerprintWindow(pixels, width, height, 0.0, 0.0, side)
            ?.let { windows.add(it) }
        imageFingerprintWindow(pixels, width, height, width - side, 0.0, side)
            ?.let { windows.add(it) }
    } else if (height > width) {
        // Squares anchored at the top and bottom edges.
        imageFingerprintWindow(pixels, width, height, 0.0, 0.0, side)
            ?.let { windows.add(it) }
        imageFingerprintWindow(pixels, width, height, 0.0, height - side, side)
            ?.let { windows.add(it) }
    }
    return PixelFingerprintSet(center, windows, distribution)
}

/** How strongly a candidate rendition matches the ground truth. */
enum class PixelAgreement {
    /** An aligned grid agrees — pixel-verified, the strong signal. */
    STRICT,

    /** No grid agrees, but the whole-frame palette does — corroborated. */
    DISTRIBUTION,

    /** Both signals disagree — the candidate is not the thumbnail's image. */
    NONE,
}

/**
 * The two-signal verdict: STRICT when the center grid agrees with the
 * truth's center grid OR any candidate window agrees with it (the
 * window case is an off-center crop of the SAME framing — still an
 * aligned, cell-by-cell match); otherwise DISTRIBUTION when the
 * whole-frame palettes agree; otherwise NONE.
 */
fun pixelAgreement(
    candidate: PixelFingerprintSet,
    truth: PixelFingerprintSet,
): PixelAgreement {
    if (fingerprintsAgree(candidate.center, truth.center)) {
        return PixelAgreement.STRICT
    }
    if (candidate.windows.any { fingerprintsAgree(it, truth.center) }) {
        return PixelAgreement.STRICT
    }
    if (distributionsAgree(candidate.distribution, truth.distribution)) {
        return PixelAgreement.DISTRIBUTION
    }
    return PixelAgreement.NONE
}

/** What the download loop should do with one attempt's pixel verdict. */
enum class PixelOutcome {
    /** Accept and count as pixel-verified. */
    ACCEPT_VERIFIED,

    /** Accept, but count separately: the palette carried it, not a grid. */
    ACCEPT_DISTRIBUTION,

    /** Reject the attempt (retryable; terminal when the budget ends). */
    REJECT,
}

/**
 * The pure attempt decision, factored out of the download loop so the
 * whole mapping is unit-testable: a STRICT verdict accepts as verified;
 * a DISTRIBUTION verdict accepts as corroborated; a NONE verdict still
 * accepts as verified when an independent byte-different refetch has
 * already produced pixels that agree with this attempt (the renditions
 * vote together against the thumbnail); anything else rejects. Terminal
 * rejection therefore requires BOTH signals to disagree with the
 * thumbnail — fingerprint disagreement alone never kills a photo.
 */
fun pixelOutcome(
    agreement: PixelAgreement,
    corroboratedByRefetch: Boolean,
): PixelOutcome = when {
    agreement == PixelAgreement.STRICT || corroboratedByRefetch ->
        PixelOutcome.ACCEPT_VERIFIED
    agreement == PixelAgreement.DISTRIBUTION ->
        PixelOutcome.ACCEPT_DISTRIBUTION
    else -> PixelOutcome.REJECT
}

/**
 * Bounded decode shared by the fingerprint entry points: the long edge
 * lands at ~160–320 px via inSampleSize — the fingerprints only need
 * cell averages and palette mass, never full resolution. Returns
 * (pixels, width, height), or null when the bytes do not decode.
 */
private fun decodeBoundedPixels(bytes: ByteArray): Triple<IntArray, Int, Int>? {
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
            Triple(px, w, h)
        } finally {
            bmp.recycle()
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Decodes [bytes] (bounded, see [decodeBoundedPixels]) and returns its
 * center-square fingerprint, or null when the bytes do not decode or
 * are too small. Runs wherever the media pipeline runs (Dispatchers.IO),
 * never the UI thread. A garbled-but-decodable image still fingerprints
 * — that is the point: its fingerprint is what the ground-truth
 * comparison rejects.
 */
fun imageFingerprintOfBytes(bytes: ByteArray): FloatArray? {
    val (px, w, h) = decodeBoundedPixels(bytes) ?: return null
    return imageFingerprint(px, w, h)
}

/** Decodes [bytes] (bounded) and returns its full [PixelFingerprintSet]. */
fun imagePixelFingerprintsOfBytes(bytes: ByteArray): PixelFingerprintSet? {
    val (px, w, h) = decodeBoundedPixels(bytes) ?: return null
    return imagePixelFingerprints(px, w, h)
}
