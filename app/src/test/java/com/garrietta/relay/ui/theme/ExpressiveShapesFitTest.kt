package com.garrietta.relay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the vocabulary fit math (ExpressiveShapes.pathFit):
 * the UNIFORM fit is the avatar ratio lock (largest single scale that
 * fits both dimensions, centered), and the STRETCHED fit is the wide
 * variant the inbox selection highlight uses (Garrett 2026-10-20: the
 * selection shape spans the row's full width) — each axis scaled
 * independently so the shape's bounds land exactly on the target box.
 * Pure JVM: the math is floats only, no Android path types.
 */
class ExpressiveShapesFitTest {

    private val eps = 0.001f

    // Reference bounds of a vocabulary path: REF_R = 100 with the
    // "Puffy" wave reaching 220 across, centered on the origin.
    private val refLeft = -110f
    private val refTop = -110f
    private val refRight = 110f
    private val refBottom = 110f

    private fun PathFit.mapX(x: Float) = x * scaleX + translateX
    private fun PathFit.mapY(y: Float) = y * scaleY + translateY

    @Test
    fun `uniform fit into a wide box is governed by height and centered`() {
        // A conversation row on the Fold's inbox pane: far wider than tall.
        val fit = pathFit(refLeft, refTop, refRight, refBottom, 880f, 200f, uniform = true)!!
        // Scale is the height ratio (200/220); width ratio (880/220) loses.
        assertEquals(200f / 220f, fit.scaleX, eps)
        assertEquals(fit.scaleX, fit.scaleY, eps)
        // Vertically the bounds land exactly on the box...
        assertEquals(0f, fit.mapY(refTop), eps)
        assertEquals(200f, fit.mapY(refBottom), eps)
        // ...horizontally the painted shape is a centered square of the
        // box's height — the narrow blob the selection bug showed.
        assertEquals((880f - 200f) / 2f, fit.mapX(refLeft), eps)
        assertEquals((880f + 200f) / 2f, fit.mapX(refRight), eps)
    }

    @Test
    fun `stretched fit maps the bounds exactly onto a wide box`() {
        val fit = pathFit(refLeft, refTop, refRight, refBottom, 880f, 200f, uniform = false)!!
        assertEquals(880f / 220f, fit.scaleX, eps)
        assertEquals(200f / 220f, fit.scaleY, eps)
        // Every bound lands on the box's own edge: full row width, full
        // row height — the selection shape IS the row's box.
        assertEquals(0f, fit.mapX(refLeft), eps)
        assertEquals(880f, fit.mapX(refRight), eps)
        assertEquals(0f, fit.mapY(refTop), eps)
        assertEquals(200f, fit.mapY(refBottom), eps)
    }

    @Test
    fun `stretched fit on a square box matches the uniform fit`() {
        val stretched = pathFit(refLeft, refTop, refRight, refBottom, 300f, 300f, uniform = false)!!
        val uniform = pathFit(refLeft, refTop, refRight, refBottom, 300f, 300f, uniform = true)!!
        assertEquals(uniform.scaleX, stretched.scaleX, eps)
        assertEquals(uniform.scaleY, stretched.scaleY, eps)
        assertEquals(uniform.translateX, stretched.translateX, eps)
        assertEquals(uniform.translateY, stretched.translateY, eps)
    }

    @Test
    fun `off-center bounds stay centered under both fits`() {
        // Bounds not centered on the origin (e.g. drawn in a corner):
        // centering must come from the bounds' own center either way.
        val uniform = pathFit(10f, 20f, 110f, 70f, 400f, 400f, uniform = true)!!
        assertEquals(200f, uniform.mapX(60f), eps)
        assertEquals(200f, uniform.mapY(45f), eps)
        val stretched = pathFit(10f, 20f, 110f, 70f, 400f, 250f, uniform = false)!!
        assertEquals(0f, stretched.mapX(10f), eps)
        assertEquals(400f, stretched.mapX(110f), eps)
        assertEquals(0f, stretched.mapY(20f), eps)
        assertEquals(250f, stretched.mapY(70f), eps)
    }

    @Test
    fun `degenerate input yields no fit`() {
        assertNull(pathFit(refLeft, refTop, refRight, refBottom, 0f, 200f, uniform = false))
        assertNull(pathFit(refLeft, refTop, refRight, refBottom, 880f, 0f, uniform = true))
        assertNull(pathFit(5f, 5f, 5f, 50f, 100f, 100f, uniform = false))
        assertNull(pathFit(5f, 5f, 50f, 5f, 100f, 100f, uniform = true))
    }
}
