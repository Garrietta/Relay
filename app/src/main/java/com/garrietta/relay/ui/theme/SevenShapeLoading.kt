package com.garrietta.relay.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed

/**
 * M3 Expressive loading indicator: a single shape that morphs through seven
 * distinct forms in a continuous loop. Hand-rolled on stable APIs via
 * androidx.graphics:graphics-shapes — [Morph] maps the curves between shapes
 * with different vertex counts itself, so the seven silhouettes can be
 * anything.
 *
 * Replaces indeterminate circular spinners for waits under ~5s: message
 * sending, sync states, contact-photo loading.
 */
@Composable
fun SevenShapeLoadingIndicator(
    modifier: Modifier = Modifier,
    diameter: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    morphMillis: Int = 380,
) {
    val transition = rememberInfiniteTransition(label = "sevenShape")
    val loop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = morphMillis * 7, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sevenShapeLoop",
    )

    Box(
        modifier
            .size(diameter)
            .drawWithCache {
                // `size` here is CacheDrawScope.size (pixels) — the layout
                // is square by construction, so center the shapes on it.
                val r = size.minDimension / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Seven distinct silhouettes. RoundedPolygon's default
                // rounding is per-shape below; each is centered on the
                // layout so every pairwise morph aligns.
                val shapes = listOf(
                    RoundedPolygon.circle(numVertices = 16, radius = r, centerX = cx, centerY = cy),
                    RoundedPolygon(
                        numVertices = 4, radius = r, centerX = cx, centerY = cy,
                        rounding = CornerRounding(r * 0.32f),
                    ),
                    RoundedPolygon(
                        numVertices = 3, radius = r, centerX = cx, centerY = cy,
                        rounding = CornerRounding(r * 0.22f),
                    ),
                    RoundedPolygon.star(
                        numVerticesPerRadius = 5, radius = r, innerRadius = r * 0.58f,
                        rounding = CornerRounding(r * 0.18f), centerX = cx, centerY = cy,
                    ),
                    RoundedPolygon(
                        numVertices = 5, radius = r, centerX = cx, centerY = cy,
                        rounding = CornerRounding(r * 0.26f),
                    ),
                    RoundedPolygon.star(
                        numVerticesPerRadius = 8, radius = r, innerRadius = r * 0.82f,
                        rounding = CornerRounding(r * 0.36f), centerX = cx, centerY = cy,
                    ),
                    // Pill: a circle stretched horizontally.
                    RoundedPolygon.circle(numVertices = 16, radius = r, centerX = cx, centerY = cy)
                        .transformed(
                            android.graphics.Matrix().apply { setScale(1.45f, 1f, cx, cy) },
                        ),
                )
                val morphs = shapes.indices.map { i ->
                    Morph(shapes[i], shapes[(i + 1) % shapes.size])
                }
                val androidPath = android.graphics.Path()
                onDrawBehind {
                    val t = loop
                    val seg = ((t.toInt() % 7) + 7) % 7
                    val local = (t - t.toInt()).coerceIn(0f, 1f)
                    androidPath.rewind()
                    morphs[seg].toPath(local, androidPath)
                    drawPath(androidPath.asComposePath(), color)
                }
            },
    )
}
