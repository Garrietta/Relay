package com.garrietta.relay.ui.theme

import androidx.compose.foundation.shape.GenericShape
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
 * Shapes are sized to their layout at draw time (GenericShape), so the same
 * definition works from a 26dp message avatar to the group-header cluster.
 */
data class NamedShape(val name: String, val shape: Shape)

/**
 * Builds one vocabulary shape from a rounded polygon: optional star inner
 * radius, rotation, and non-uniform scale (ovals, pills, clamshells).
 */
private fun m3ePolygon(
    numVertices: Int,
    roundingFrac: Float,
    rotationDeg: Float = 0f,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    starInnerFrac: Float? = null,
): Shape = GenericShape { size, _ ->
    val r = size.minDimension / 2f
    if (r <= 0f) return@GenericShape
    val cx = size.width / 2f
    val cy = size.height / 2f
    val poly = if (starInnerFrac != null) {
        RoundedPolygon.star(
            numVerticesPerRadius = numVertices,
            radius = r,
            innerRadius = r * starInnerFrac,
            rounding = CornerRounding(r * roundingFrac),
            centerX = cx,
            centerY = cy,
        )
    } else {
        RoundedPolygon(
            numVertices = numVertices,
            radius = r,
            centerX = cx,
            centerY = cy,
            rounding = CornerRounding(r * roundingFrac),
        )
    }
    val m = android.graphics.Matrix()
    if (rotationDeg != 0f) m.postRotate(rotationDeg, cx, cy)
    if (scaleX != 1f || scaleY != 1f) m.postScale(scaleX, scaleY, cx, cy)
    val path = android.graphics.Path()
    poly.transformed(m).toPath(path)
    addPath(path.asComposePath())
}

/**
 * Organic modulated blob (sine-wave radius) — the quatrefoil / puffy bottom
 * row of the sheet. Kept in the same spirit as [WavyBlobShape].
 */
private fun modulatedBlob(waves: Int, amplitude: Float): Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = size.minDimension / 2f
    if (baseR <= 0f) return@GenericShape
    val steps = 120
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * (2f * PI.toFloat())
        val rr = baseR * (1f + amplitude * cos(waves * a))
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The full rotation set, in sheet order. */
val ExpressiveAvatarShapes: List<NamedShape> = listOf(
    NamedShape("Oval", m3ePolygon(16, 0f, scaleX = 0.80f, scaleY = 1.12f)),
    NamedShape("Pill", m3ePolygon(16, 0f, scaleX = 1.45f)),
    NamedShape("Triangle", m3ePolygon(3, 0.24f, rotationDeg = -90f)),
    NamedShape("Arrow", m3ePolygon(3, 0.30f, rotationDeg = 30f)),
    NamedShape("Fan", m3ePolygon(3, 0.42f, rotationDeg = 45f)),
    NamedShape("Diamond", m3ePolygon(4, 0.16f, rotationDeg = 45f)),
    NamedShape("Clamshell", m3ePolygon(6, 0.30f, scaleX = 1.35f, scaleY = 0.92f)),
    NamedShape("Pentagon", m3ePolygon(5, 0.22f, rotationDeg = -90f)),
    NamedShape("Gem", m3ePolygon(4, 0.30f, starInnerFrac = 0.70f)),
    NamedShape("VerySunny", m3ePolygon(12, 0.32f, starInnerFrac = 0.76f)),
    NamedShape("Sunny", m3ePolygon(8, 0.34f, starInnerFrac = 0.70f)),
    NamedShape("Cookie4", m3ePolygon(4, 0.44f)),
    NamedShape("Cookie6", m3ePolygon(6, 0.46f)),
    NamedShape("Cookie7", m3ePolygon(7, 0.46f)),
    NamedShape("Cookie9", m3ePolygon(9, 0.46f)),
    NamedShape("Scallop", m3ePolygon(24, 0.55f)),
    NamedShape("Quatrefoil", modulatedBlob(waves = 4, amplitude = 0.15f)),
    NamedShape("Puffy", modulatedBlob(waves = 6, amplitude = 0.10f)),
    NamedShape("Burst", m3ePolygon(16, 0.08f, starInnerFrac = 0.84f)),
    NamedShape("Pop", m3ePolygon(14, 0.34f, starInnerFrac = 0.80f)),
)

/** Looks up a vocabulary shape by name; falls back to the wavy blob. */
fun expressiveShape(name: String): Shape =
    ExpressiveAvatarShapes.firstOrNull { it.name == name }?.shape ?: WavyBlobShape
