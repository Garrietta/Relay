package com.garrietta.relay.ui.theme

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The M3 Expressive shape vocabulary as a per-avatar rotation set.
 *
 * Garrett's directive: every contact avatar takes a DIFFERENT expressive
 * shape, running through this set (the official M3E sheet he sent), reshuffled
 * on each app startup for fresh variety. Built on
 * androidx.graphics:graphics-shapes (stable) — RoundedPolygon gives us the
 * polygon/star vocabulary; per-vertex rounding and matrix transforms give us
 * the ovals, pills, fans and cookies. True MaterialShapes need the SDK 37 /
 * AGP 9.x toolchain; these are faithful hand-rolled approximations.
 *
 * UNIFORM HEIGHT: every shape is fitted so its visual height always equals
 * the layout height, with its ORIGINAL proportions kept (uniform scale —
 * a skinny shape stays skinny, just at the common height). [aspectRatio]
 * reports the natural width/height so avatar containers size themselves as
 * height=H, width=H*aspect. Reference paths are built lazily so unit tests
 * (no Android framework) can load the vocabulary.
 *
 * Shapes are sized to their layout at draw time (GenericShape), so the same
 * definition works from a 26dp message avatar to the group-header cluster.
 */
data class NamedShape(
    val name: String,
    val shape: Shape,
    private val pathBuilder: () -> android.graphics.Path,
) {
    /** The reference path; built on first use (draw time on device). */
    val referencePath: android.graphics.Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        pathBuilder()
    }

    /**
     * Natural width / height measured from the reference path. Avatars use
     * this to keep every shape at the same visual height.
     */
    val aspectRatio: Float by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val b = android.graphics.RectF()
        referencePath.computeBounds(b, true)
        if (b.height() <= 0f) 1f else b.width() / b.height()
    }
}

/** Reference radius for all vocabulary paths; fitted to layout at draw time. */
private const val REF_R = 100f

/**
 * Builds one vocabulary shape's reference path: rounded polygon (or star),
 * rotated / non-uniformly scaled per the sheet, centered on the origin.
 * The path is fitted to the layout at draw time (uniform height fit).
 */
private fun m3eReferencePath(
    numVertices: Int,
    roundingFrac: Float,
    rotationDeg: Float = 0f,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    starInnerFrac: Float? = null,
): android.graphics.Path {
    val poly = if (starInnerFrac != null) {
        RoundedPolygon.star(
            numVerticesPerRadius = numVertices,
            radius = REF_R,
            innerRadius = REF_R * starInnerFrac,
            rounding = CornerRounding(REF_R * roundingFrac),
            centerX = 0f,
            centerY = 0f,
        )
    } else {
        RoundedPolygon(
            numVertices = numVertices,
            radius = REF_R,
            centerX = 0f,
            centerY = 0f,
            rounding = CornerRounding(REF_R * roundingFrac),
        )
    }
    val m = android.graphics.Matrix()
    if (rotationDeg != 0f) m.postRotate(rotationDeg)
    if (scaleX != 1f || scaleY != 1f) m.postScale(scaleX, scaleY)
    return android.graphics.Path().also { poly.transformed(m).toPath(it) }
}

/**
 * Organic modulated blob reference path (sine-wave radius) — the quatrefoil
 * / puffy bottom row of the sheet. Kept in the same spirit as [WavyBlobShape].
 */
private fun modulatedBlobReferencePath(waves: Int, amplitude: Float): android.graphics.Path {
    val path = android.graphics.Path()
    val steps = 120
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * (2f * PI.toFloat())
        val rr = REF_R * (1f + amplitude * cos(waves * a))
        val x = rr * cos(a)
        val y = rr * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * Fits [ref] into [size] with a UNIFORM scale so the shape's visual height
 * always equals the layout height while its original proportions are kept.
 * The reference path is never mutated (a copy is transformed).
 */
private fun androidx.compose.ui.graphics.Path.addFittedByHeight(
    ref: android.graphics.Path,
    size: Size,
) {
    if (size.height <= 0f) return
    val src = android.graphics.Path(ref)
    val bounds = android.graphics.RectF()
    src.computeBounds(bounds, true)
    if (bounds.height() <= 0f) return
    val s = size.height / bounds.height()
    val fit = android.graphics.Matrix()
    fit.setScale(s, s)
    fit.postTranslate(
        size.width / 2f - bounds.centerX() * s,
        size.height / 2f - bounds.centerY() * s,
    )
    src.transform(fit)
    addPath(src.asComposePath())
}

private fun namedPolygon(
    name: String,
    numVertices: Int,
    roundingFrac: Float,
    rotationDeg: Float = 0f,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    starInnerFrac: Float? = null,
): NamedShape {
    val builder: () -> android.graphics.Path =
        { m3eReferencePath(numVertices, roundingFrac, rotationDeg, scaleX, scaleY, starInnerFrac) }
    // Capture the builder for the lazy reference path; the Shape lambda
    // resolves it at draw time (never at vocabulary construction, so unit
    // tests stay Android-free).
    var ref: android.graphics.Path? = null
    val shape: Shape = GenericShape { size, _ ->
        val r = ref ?: builder().also { ref = it }
        addFittedByHeight(r, size)
    }
    return NamedShape(name, shape, builder)
}

private fun namedBlob(name: String, waves: Int, amplitude: Float): NamedShape {
    val builder: () -> android.graphics.Path = { modulatedBlobReferencePath(waves, amplitude) }
    var ref: android.graphics.Path? = null
    val shape: Shape = GenericShape { size, _ ->
        val r = ref ?: builder().also { ref = it }
        addFittedByHeight(r, size)
    }
    return NamedShape(name, shape, builder)
}

/** The full rotation set, in sheet order. */
val ExpressiveAvatarShapes: List<NamedShape> = listOf(
    namedPolygon("Oval", 16, 0f, scaleX = 0.80f, scaleY = 1.12f),
    namedPolygon("Pill", 16, 0f, scaleX = 1.45f),
    namedPolygon("Triangle", 3, 0.24f, rotationDeg = -90f),
    namedPolygon("Arrow", 3, 0.30f, rotationDeg = 30f),
    namedPolygon("Fan", 3, 0.42f, rotationDeg = 45f),
    namedPolygon("Diamond", 4, 0.16f, rotationDeg = 45f),
    namedPolygon("Clamshell", 6, 0.30f, scaleX = 1.35f, scaleY = 0.92f),
    namedPolygon("Pentagon", 5, 0.22f, rotationDeg = -90f),
    namedPolygon("Gem", 4, 0.30f, starInnerFrac = 0.70f),
    namedPolygon("VerySunny", 12, 0.32f, starInnerFrac = 0.76f),
    namedPolygon("Sunny", 8, 0.34f, starInnerFrac = 0.70f),
    namedPolygon("Cookie4", 4, 0.44f),
    namedPolygon("Cookie6", 6, 0.46f),
    namedPolygon("Cookie7", 7, 0.46f),
    namedPolygon("Cookie9", 9, 0.46f),
    namedPolygon("Scallop", 24, 0.55f),
    namedBlob("Quatrefoil", waves = 4, amplitude = 0.15f),
    namedBlob("Puffy", waves = 6, amplitude = 0.10f),
    namedPolygon("Burst", 16, 0.08f, starInnerFrac = 0.84f),
    namedPolygon("Pop", 14, 0.34f, starInnerFrac = 0.80f),
)

/** Looks up a vocabulary shape by name; falls back to the wavy blob. */
fun expressiveShape(name: String): Shape =
    ExpressiveAvatarShapes.firstOrNull { it.name == name }?.shape ?: WavyBlobShape

/**
 * Mostly-rectangle bar with SHORT pointed sides — the toned-down
 * New-conversation bar. Straight top and bottom edges; each end carries a
 * small pointed flourish extending only ~5% of the width, never sweeping
 * toward the middle. Expressive character, none of the drama.
 */
val RibbonBarShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@GenericShape
    val tipX = w * 0.055f   // short point extent on each side
    val topY = h * 0.14f
    val botY = h * 0.86f
    val r = h * 0.10f       // small corner rounding into the straight edges
    moveTo(0f, h / 2f)                       // left tip
    lineTo(tipX, topY + r)
    quadraticTo(tipX, topY, tipX + r, topY)  // round into the top edge
    lineTo(w - tipX - r, topY)               // straight top edge
    quadraticTo(w - tipX, topY, w - tipX, topY + r)
    lineTo(w, h / 2f)                        // right tip
    lineTo(w - tipX, botY - r)
    quadraticTo(w - tipX, botY, w - tipX - r, botY)
    lineTo(tipX + r, botY)                   // straight bottom edge
    quadraticTo(tipX, botY, tipX, botY - r)
    close()
}

/**
 * A horizontally-stretched version of a vocabulary shape: the reference path
 * is mapped onto the FULL layout rect (non-uniform scale), so the shape's
 * points, corners and curves span the whole width with its character intact.
 * Used for the full-width New-conversation bar (stretched Clamshell).
 */
fun stretchedExpressiveShape(name: String): Shape {
    val named = ExpressiveAvatarShapes.firstOrNull { it.name == name } ?: return WavyBlobShape
    return GenericShape { size, _ ->
        if (size.width <= 0f || size.height <= 0f) return@GenericShape
        val src = android.graphics.Path(named.referencePath)
        val bounds = android.graphics.RectF()
        src.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return@GenericShape
        val fit = android.graphics.Matrix()
        fit.setRectToRect(
            android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
            android.graphics.RectF(0f, 0f, size.width, size.height),
            android.graphics.Matrix.ScaleToFit.FILL,
        )
        src.transform(fit)
        addPath(src.asComposePath())
    }
}
