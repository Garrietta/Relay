package com.garrietta.relay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the vocabulary fit math (ExpressiveShapes.pathFit):
 * the UNIFORM fit is the avatar ratio lock (largest single scale that
 * fits both dimensions, centered), so a vocabulary shape always
 * renders at its EXACT designed aspect ratio in any box. Pure JVM:
 * the math is floats only, no Android path types.
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
        val fit = pathFit(refLeft, refTop, refRight, refBottom, 880f, 200f)!!
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
    fun `off-center bounds stay centered`() {
        // Bounds not centered on the origin (e.g. drawn in a corner):
        // centering must come from the bounds' own center.
        val fit = pathFit(10f, 20f, 110f, 70f, 400f, 400f)!!
        assertEquals(200f, fit.mapX(60f), eps)
        assertEquals(200f, fit.mapY(45f), eps)
    }

    @Test
    fun `degenerate input yields no fit`() {
        assertNull(pathFit(refLeft, refTop, refRight, refBottom, 0f, 200f))
        assertNull(pathFit(refLeft, refTop, refRight, refBottom, 880f, 0f))
        assertNull(pathFit(5f, 5f, 5f, 50f, 100f, 100f))
        assertNull(pathFit(5f, 5f, 50f, 5f, 100f, 100f))
    }
}
