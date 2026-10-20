package com.garrietta.relay.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.garrietta.relay.ui.theme.AvatarShapeAssigner
import com.garrietta.relay.ui.theme.ContactPalettes

/**
 * Notification avatars in the contact's expressive shape (Garrett
 * 2026-10-19 22:00: "if you can add the expressive shape of the contact
 * in question that would be great"). The contact photo is clipped to the
 * SAME per-contact M3E shape the in-app avatars use (via
 * [AvatarShapeAssigner]); when there's no photo, an initial-letter avatar
 * is drawn in the same shape — the notification never shows the generic
 * person icon anymore.
 */
object NotificationAvatar {

    /**
     * Builds a square bitmap of [sizePx] with the contact's avatar baked
     * into their expressive shape. Never throws — returns null only when
     * even the fallback can't be drawn.
     */
    fun shapedAvatar(
        context: Context,
        photoUri: String?,
        shapeKey: String,
        displayName: String,
        sizePx: Int = 256,
    ): Bitmap? = runCatching {
        val shape = AvatarShapeAssigner.shapeFor(shapeKey.ifBlank { displayName })
        val density = Density(context)
        val outline = shape.createOutline(
            Size(sizePx.toFloat(), sizePx.toFloat()),
            LayoutDirection.Ltr,
            density,
        )
        val clip = android.graphics.Path().apply {
            when (outline) {
                is Outline.Rounded -> {
                    val rr = outline.roundRect
                    addRoundRect(
                        android.graphics.RectF(rr.left, rr.top, rr.right, rr.bottom),
                        floatArrayOf(
                            rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y,
                            rr.topRightCornerRadius.x, rr.topRightCornerRadius.y,
                            rr.bottomRightCornerRadius.x, rr.bottomRightCornerRadius.y,
                            rr.bottomLeftCornerRadius.x, rr.bottomLeftCornerRadius.y,
                        ),
                        android.graphics.Path.Direction.CW,
                    )
                }
                is Outline.Rectangle -> addRect(
                    outline.rect.left, outline.rect.top,
                    outline.rect.right, outline.rect.bottom,
                    android.graphics.Path.Direction.CW,
                )
                is Outline.Generic -> addPath(outline.path.asAndroidPath())
            }
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.save()
        canvas.clipPath(clip)
        val photo = photoUri?.let { decodeCenterCropped(context, it, sizePx) }
        if (photo != null) {
            canvas.drawBitmap(photo, null, Rect(0, 0, sizePx, sizePx), null)
        } else {
            // Initial-letter fallback in the same shape, in the
            // contact's assigned expressive palette (Garrett
            // 2026-10-20) — the same colors their in-app photo-less
            // avatar uses, picked for the shade's night mode.
            val palette = ContactPalettes.paletteFor(shapeKey.ifBlank { displayName })
            val night = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            canvas.drawColor(palette.backgroundArgb(night))
            val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.onBackgroundArgb(night)
                textSize = sizePx * 0.42f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            canvas.drawText(
                initial,
                sizePx / 2f,
                sizePx / 2f - (fm.ascent + fm.descent) / 2f,
                paint,
            )
        }
        canvas.restore()
        out
    }.getOrNull()

    /** Downsamples + center-crops a content URI to a square bitmap. */
    private fun decodeCenterCropped(
        context: Context,
        uriString: String,
        sizePx: Int,
    ): Bitmap? = runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val sample = (longest / (sizePx * 2)).coerceAtLeast(1)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val w = decoded.width
        val h = decoded.height
        val side = minOf(w, h)
        Bitmap.createBitmap(decoded, (w - side) / 2, (h - side) / 2, side, side)
    }.getOrNull()
}
