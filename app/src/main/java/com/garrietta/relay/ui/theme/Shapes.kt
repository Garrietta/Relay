package com.garrietta.relay.ui.theme

import androidx.compose.foundation.shape.GenericShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
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

/**
 * Pressed-state blob: tighter waves and a whisper of squaring, so avatars
 * visibly morph (not just scale) while pressed. Paired with a bouncy press
 * scale for the full shape-as-state moment.
 */
val WavyBlobShapePressed = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = min(size.width, size.height) / 2f
    val waves = 9
    val amplitude = 0.028f
    val steps = 144
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * (2f * PI.toFloat())
        // Slight pull toward a squircle while pressed.
        val squash = 0.06f * (cos(4 * a).toFloat() * 0.5f + 0.5f)
        val r = baseR * (1f + amplitude * sin(waves * a) - squash * 0.5f)
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * True squircle (superellipse, n=4) — sits between a circle and a rounded
 * rect. Used for attachment buttons and other chunky expressive controls.
 */
val SquircleShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val a = size.width / 2f
    val b = size.height / 2f
    val n = 4f
    val steps = 96
    fun sgnPow(v: Float, p: Float): Float {
        val s = if (v < 0) -1f else 1f
        return s * kotlin.math.abs(v).pow(p)
    }
    for (i in 0..steps) {
        val t = (i / steps.toFloat()) * (2f * PI.toFloat())
        val x = cx + a * sgnPow(cos(t), 2f / n)
        val y = cy + b * sgnPow(sin(t), 2f / n)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Four-lobe clover — a playful organic chip shape for small labels like the
 * transport tag. Stretched horizontally so text chips read naturally.
 */
val CloverShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = min(size.width, size.height) / 2f
    val steps = 96
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * (2f * PI.toFloat())
        val r = baseR * (1f + 0.14f * cos(4 * a))
        val x = cx + r * cos(a) * 1.9f
        val y = cy + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
