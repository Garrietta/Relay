package com.garrietta.relay.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garrietta.relay.rcs.proto.RcsMediaAttachment
import com.garrietta.relay.ui.theme.ExpressiveAvatarShapes
import com.garrietta.relay.ui.theme.NamedShape
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Stickers (Garrett 2026-10-20, feature queue 5/7).
 *
 * Relay has NO native sticker wire type in the paired protocol, so a
 * sticker rides the proven attachment pipeline (#131) as a transparent
 * PNG image: recipients on other clients see a clean image, and
 * Relay-to-Relay renders it bubble-less at sticker size. The starter
 * pack is drawn from Relay's own design language — the 20 expressive
 * shapes as die-cut stickers, plus the KitKat-smiley mascot — rendered
 * entirely in code (no bundled assets, no downloads): the same
 * [drawStickerArt] paints the composer's panel previews and the PNG
 * bytes that go on the wire.
 *
 * SENDING tags the upload's file name "relay-sticker-<id>.png"; the
 * send encoder already writes that name into MediaContent field 4
 * (mediaName), and the receive decoder now reads it back — that is
 * the exact Relay-to-Relay detection rule. The fallback for stickers
 * from anywhere else (and in case a server ever strips the name): any
 * image/png or image/webp whose decoded pixels actually contain
 * transparency and whose dimensions are at most [STICKER_MAX_DIM_PX]
 * renders as a sticker too. False positives of the fallback are
 * presentational only: a small transparent logo/photo loses its
 * bubble background and caps at sticker size; taps, captions and the
 * fullscreen pager behave exactly as for any image.
 */

/** MediaContent field-4 name prefix on Relay's own sticker sends. */
const val STICKER_NAME_PREFIX = "relay-sticker-"

/** Largest width/height (px) a transparent image may have and still
 * render bubble-less as a sticker under the generic fallback rule. */
const val STICKER_MAX_DIM_PX = 512

/** On-screen cap for a rendered sticker's long edge. */
private val STICKER_MAX_DP = 168.dp

/** The file name a sticker send carries on the wire. */
fun stickerFileName(def: StickerDef): String = "$STICKER_NAME_PREFIX${def.id}.png"

enum class StickerFace { NONE, SMILEY }

/**
 * One starter-pack sticker: an expressive shape, a fill color, and an
 * optional mascot face. [color] is ARGB packed in a Long (Compose-style
 * hex literal) so definitions stay readable.
 */
data class StickerDef(
    val id: String,
    val shapeName: String,
    val color: Long,
    val face: StickerFace = StickerFace.NONE,
)

/** The starter pack: mascots first, then the full shape vocabulary. */
object StickerPack {
    val all: List<StickerDef> = listOf(
        // The mascot: KitKat-smiley face on friendly shapes.
        StickerDef("mascot-relay", "Oval", 0xFF01AD41, StickerFace.SMILEY),
        StickerDef("mascot-sunny", "Sunny", 0xFFFFB300, StickerFace.SMILEY),
        StickerDef("mascot-berry", "Cookie4", 0xFFEC407A, StickerFace.SMILEY),
        StickerDef("mascot-ocean", "Gem", 0xFF29B6F6, StickerFace.SMILEY),
        // The 20 expressive shapes as die-cut stickers.
        StickerDef("oval-pop", "Oval", 0xFFFFB74D),
        StickerDef("pill-teal", "Pill", 0xFF4DB6AC),
        StickerDef("triangle-amber", "Triangle", 0xFFFFD54F),
        StickerDef("arrow-lime", "Arrow", 0xFFAED581),
        StickerDef("fan-pink", "Fan", 0xFFF06292),
        StickerDef("diamond-blue", "Diamond", 0xFF64B5F6),
        StickerDef("clamshell-purple", "Clamshell", 0xFFBA68C8),
        StickerDef("pentagon-coral", "Pentagon", 0xFFFF8A65),
        StickerDef("gem-cyan", "Gem", 0xFF4DD0E1),
        StickerDef("very-sunny", "VerySunny", 0xFFFFEE58),
        StickerDef("sunny-amber", "Sunny", 0xFFFFCA28),
        StickerDef("cookie-4", "Cookie4", 0xFFBCAAA4),
        StickerDef("cookie-6", "Cookie6", 0xFF8D6E63),
        StickerDef("cookie-7", "Cookie7", 0xFF6D4C41),
        StickerDef("cookie-9", "Cookie9", 0xFFA1887F),
        StickerDef("scallop-rose", "Scallop", 0xFFF48FB1),
        StickerDef("quatrefoil-green", "Quatrefoil", 0xFF81C784),
        StickerDef("puffy-sky", "Puffy", 0xFF90CAF9),
        StickerDef("burst-orange", "Burst", 0xFFFF7043),
        StickerDef("pop-violet", "Pop", 0xFFAB47BC),
    )

    private val byId = all.associateBy { it.id }
    fun byId(id: String): StickerDef? = byId[id]
}

/** The vocabulary shape behind a sticker definition. */
private fun lookupShape(shapeName: String): NamedShape =
    ExpressiveAvatarShapes.firstOrNull { it.name == shapeName }
        ?: ExpressiveAvatarShapes.first()

/**
 * Paints one sticker into an Android canvas, centered in a square of
 * [sizePx]: the shape fitted near-full-bleed in WHITE (the die-cut
 * border every sticker carries, over a soft shadow), the same shape
 * shrunk about its center in the sticker's color (so a uniform white
 * rim shows), and — for mascot stickers — the smiley face at the
 * shape's optical anchor. Used for both the wire PNG ([StickerRenderer])
 * and the panel previews ([StickerArt]), so what he taps is byte-for-
 * shape what gets sent.
 */
fun drawStickerArt(canvas: AndroidCanvas, def: StickerDef, sizePx: Float) {
    val named = lookupShape(def.shapeName)
    val ref = named.referencePath
    val bounds = RectF()
    ref.computeBounds(bounds, true)
    if (bounds.width() <= 0f || bounds.height() <= 0f) return

    val margin = sizePx * 0.04f
    val avail = sizePx - 2 * margin
    val fit = minOf(avail / bounds.width(), avail / bounds.height())
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    fun fittedPath(scaleAboutCenter: Float): Path {
        val s = fit * scaleAboutCenter
        val m = android.graphics.Matrix()
        m.setScale(s, s)
        m.postTranslate(cx - bounds.centerX() * s, cy - bounds.centerY() * s)
        return Path(ref).also { it.transform(m) }
    }

    // Die-cut white border + soft shadow.
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.WHITE
        setShadowLayer(sizePx * 0.018f, 0f, sizePx * 0.010f, 0x55000000)
    }
    canvas.drawPath(fittedPath(1f), borderPaint)

    // Colored body inside the rim.
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = def.color.toInt()
    }
    canvas.drawPath(fittedPath(BODY_SCALE), bodyPaint)

    if (def.face == StickerFace.SMILEY) {
        // The face anchors where the shape's visual mass sits (its
        // measured optical anchor), mapped through the same transform
        // as the body path.
        val s = fit * BODY_SCALE
        val anchorX = cx + (bounds.left + named.opticalAnchor.first * bounds.width() -
            bounds.centerX()) * s
        val anchorY = cy + (bounds.top + named.opticalAnchor.second * bounds.height() -
            bounds.centerY()) * s
        val faceR = minOf(bounds.width(), bounds.height()) * s * 0.27f
        drawSmileyFace(canvas, anchorX, anchorY, faceR, def.color.toInt())
    }
}

private const val BODY_SCALE = 0.86f

/**
 * The KitKat-style smiley: a white dome head (flat bottom, semicircular
 * top) with two antennae, eyes and smile knocked out in the sticker's
 * body color — the mascot language from Relay's own app icon.
 */
private fun drawSmileyFace(
    canvas: AndroidCanvas,
    cx: Float,
    cy: Float,
    r: Float,
    featureColor: Int,
) {
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.WHITE
    }
    val feature = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = featureColor
    }
    val w = r * 2.0f
    val domeH = w / 2f
    val headTop = cy - r * 0.72f
    val headBottom = headTop + domeH + r * 0.42f
    val left = cx - w / 2f
    val right = cx + w / 2f

    // Antennae first so their bases tuck under the head.
    val antennaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        strokeWidth = r * 0.13f
        strokeCap = Paint.Cap.ROUND
    }
    val springY = headTop + domeH * 0.55f
    canvas.drawLine(cx - w * 0.24f, springY, cx - w * 0.40f, headTop - r * 0.34f, antennaPaint)
    canvas.drawLine(cx + w * 0.24f, springY, cx + w * 0.40f, headTop - r * 0.34f, antennaPaint)
    canvas.drawCircle(cx - w * 0.40f, headTop - r * 0.38f, r * 0.10f, white)
    canvas.drawCircle(cx + w * 0.40f, headTop - r * 0.38f, r * 0.10f, white)

    // Dome head.
    val head = Path()
    head.moveTo(left, headBottom)
    head.lineTo(left, headTop + domeH)
    head.arcTo(RectF(left, headTop, right, headTop + w), 180f, 180f)
    head.lineTo(right, headBottom)
    head.close()
    canvas.drawPath(head, white)

    // Eyes + smile in the body color.
    val eyeY = headTop + domeH + r * 0.02f
    canvas.drawCircle(cx - w * 0.21f, eyeY, r * 0.105f, feature)
    canvas.drawCircle(cx + w * 0.21f, eyeY, r * 0.105f, feature)
    val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = featureColor
        strokeWidth = r * 0.10f
        strokeCap = Paint.Cap.ROUND
    }
    val smileRect = RectF(
        cx - w * 0.25f, eyeY - r * 0.05f,
        cx + w * 0.25f, eyeY + r * 0.62f,
    )
    canvas.drawArc(smileRect, 25f, 130f, false, smilePaint)
}

/** Rasterizes stickers to wire PNGs; bytes are cached per sticker id
 * (the artwork is deterministic — rendered once per app process). */
object StickerRenderer {
    private const val RENDER_PX = 512
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun pngBytes(def: StickerDef): ByteArray = cache.getOrPut(def.id) {
        val bmp = Bitmap.createBitmap(RENDER_PX, RENDER_PX, Bitmap.Config.ARGB_8888)
        try {
            drawStickerArt(AndroidCanvas(bmp), def, RENDER_PX.toFloat())
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }
}

/**
 * Sticker detection for rendering (see the file header for the full
 * rule): Relay's own sends are recognized by their MediaContent name;
 * anything else must be a PNG/WebP no larger than [STICKER_MAX_DIM_PX]
 * whose decoded pixels actually contain transparency.
 */
fun looksLikeSticker(
    media: RcsMediaAttachment,
    decodedBounds: Pair<Int, Int>?,
    bytes: ByteArray,
): Boolean {
    if (media.name.startsWith(STICKER_NAME_PREFIX)) return true
    val mime = media.mimeType.lowercase()
    if (mime != "image/png" && mime != "image/webp") return false
    val bounds = decodedBounds ?: return false
    if (bounds.first > STICKER_MAX_DIM_PX || bounds.second > STICKER_MAX_DIM_PX) {
        return false
    }
    return hasTransparentPixels(bytes)
}

/**
 * True when the decoded image has real transparency: samples the four
 * corners (sticker art never paints them — every pack shape is
 * inscribed in its box — so a full-bleed photo fails here even when it
 * carries an alpha channel) plus a coarse interior grid.
 */
private fun hasTransparentPixels(bytes: ByteArray): Boolean {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
    try {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val points = mutableListOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)
        for (fx in listOf(0.25f, 0.5f, 0.75f)) {
            for (fy in listOf(0.25f, 0.5f, 0.75f)) {
                points += (w * fx).toInt().coerceIn(0, w - 1) to
                    (h * fy).toInt().coerceIn(0, h - 1)
            }
        }
        return points.any { (x, y) -> AndroidColor.alpha(bmp.getPixel(x, y)) < 250 }
    } catch (e: Exception) {
        return false
    } finally {
        bmp.recycle()
    }
}

/** On-screen sticker size: natural size, long edge capped. */
fun stickerDisplaySize(naturalW: Dp, naturalH: Dp): Pair<Dp, Dp> {
    val w = minOf(naturalW, STICKER_MAX_DP)
    val h = w * (naturalH.value / naturalW.value)
    return if (h > STICKER_MAX_DP) {
        STICKER_MAX_DP * (naturalW.value / naturalH.value) to STICKER_MAX_DP
    } else {
        w to h
    }
}

/** One sticker as drawn artwork — the exact [drawStickerArt] painting,
 * so the panel preview is the sent PNG's twin. */
@Composable
fun StickerArt(def: StickerDef, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val side = size.minDimension
            canvas.nativeCanvas.translate((size.width - side) / 2f, (size.height - side) / 2f)
            drawStickerArt(canvas.nativeCanvas, def, side)
        }
    }
}

@Composable
private fun StickerCell(
    def: StickerDef,
    onPick: (StickerDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onPick(def) }
            .padding(5.dp),
    ) {
        StickerArt(def, Modifier.fillMaxSize())
    }
}

/**
 * The sticker panel that replaces the keyboard under the composer:
 * recents first (when any), then the full starter pack in a scrollable
 * grid — two rows visible at keyboard height, the rest a flick away.
 * Tapping a sticker sends it immediately; the panel stays open for
 * rapid follow-ups.
 */
@Composable
fun StickerPanel(
    recents: List<StickerDef>,
    onPick: (StickerDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (recents.isNotEmpty()) {
            Text(
                text = "Recent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(recents, key = { it.id }) { def ->
                    StickerCell(def, onPick, Modifier.size(64.dp))
                }
            }
        }
        Text(
            text = "Relay stickers",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(StickerPack.all, key = { it.id }) { def ->
                StickerCell(def, onPick, Modifier.aspectRatio(1f))
            }
        }
    }
}
