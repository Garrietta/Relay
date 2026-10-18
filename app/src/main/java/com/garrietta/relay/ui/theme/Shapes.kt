package com.garrietta.relay.ui.theme

import androidx.compose.foundation.shape.GenericShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Relay's signature organic shape — a soft wavy blob in the spirit of Koda's
 * gear/flower icon containers. A circle whose radius is gently modulated by a
 * sine wave, sampled densely enough to render smooth at avatar sizes.
 *
 * Used for contact avatars, empty-state emblems, and anywhere a bubbly
 * organic container beats a plain rounded rect. Stable-APIs only (no
 * material3 1.5.0 MaterialShapes dependency).
 */
val WavyBlobShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = min(size.width, size.height) / 2f
    val waves = 9
    val amplitude = 0.055f // ±5.5% radius wobble — organic, not spiky
    val steps = 144
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * (2f * PI.toFloat())
        val r = baseR * (1f + amplitude * sin(waves * a))
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A calmer 6-wave variant for larger emblem surfaces. */
val WavyBlobShapeCalm = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = min(size.width, size.height) / 2f
    val waves = 6
    val amplitude = 0.07f
    val steps = 120
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * (2f * PI.toFloat())
        val r = baseR * (1f + amplitude * sin(waves * a))
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
