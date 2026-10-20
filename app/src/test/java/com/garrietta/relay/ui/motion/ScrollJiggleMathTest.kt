package com.garrietta.relay.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the scroll-jiggle math (Garrett 2026-10-20: a
 * velocity-based jiggle on messages and contact pictures — "nothing
 * crazy", nothing out of place). The contract under test: zero
 * velocity is EXACT identity for every item; the hard caps (~2
 * degrees, ~2dp) hold at ANY input magnitude, in both directions,
 * spring overshoot included (paramsFor clamps its input); the
 * per-item phase is bounded and actually staggers; and the mapping
 * is sign-symmetric — reversing the scroll reverses the jiggle.
 * Pure JVM. Run on CI via testDebugUnitTest.
 */
class ScrollJiggleMathTest {

    private val indices = listOf(-1000, -7, -1, 0, 1, 2, 3, 5, 12, 42, 999, 100_000)

    @Test
    fun `zero velocity is exact identity for every item`() {
        assertEquals(0f, ScrollJiggleMath.strengthForTravel(0f), 0f)
        for (index in indices) {
            assertEquals(
                "identity at index $index",
                ScrollJiggleMath.Params.IDENTITY,
                ScrollJiggleMath.paramsFor(0f, index),
            )
        }
    }

    @Test
    fun `strength is proportional below saturation and monotonic`() {
        assertEquals(
            0.5f,
            ScrollJiggleMath.strengthForTravel(ScrollJiggleMath.FULL_STRENGTH_TRAVEL_PX / 2f),
            1e-4f,
        )
        assertEquals(
            1f,
            ScrollJiggleMath.strengthForTravel(ScrollJiggleMath.FULL_STRENGTH_TRAVEL_PX),
            1e-4f,
        )
        var previous = -1f
        var travel = -200f
        while (travel <= 200f) {
            val s = ScrollJiggleMath.strengthForTravel(travel)
            assertTrue("monotonic at travel $travel", s >= previous)
            previous = s
            travel += 10f
        }
    }

    @Test
    fun `caps hold at extreme velocity in both directions`() {
        for (travel in listOf(1e3f, 1e6f, Float.MAX_VALUE)) {
            assertEquals(1f, ScrollJiggleMath.strengthForTravel(travel), 0f)
            assertEquals(-1f, ScrollJiggleMath.strengthForTravel(-travel), 0f)
        }
        for (index in indices) {
            for (strength in listOf(1f, -1f, 2f, -2f, 1e6f, -1e6f)) {
                val params = ScrollJiggleMath.paramsFor(strength, index)
                assertTrue(
                    "rotation cap at strength $strength index $index: ${params.rotationDegrees}",
                    abs(params.rotationDegrees) <= ScrollJiggleMath.MAX_ROTATION_DEGREES,
                )
                assertTrue(
                    "lag cap at strength $strength index $index: ${params.lagDp}",
                    abs(params.lagDp) <= ScrollJiggleMath.MAX_LAG_DP,
                )
                assertTrue(
                    "brief ceilings at strength $strength index $index",
                    abs(params.rotationDegrees) <= 2f && abs(params.lagDp) <= 2f,
                )
            }
        }
    }

    @Test
    fun `sign symmetry - reversing strength negates the transform`() {
        for (index in indices) {
            for (strength in listOf(0.01f, 0.25f, 0.7f, 1f, 3f)) {
                val forward = ScrollJiggleMath.paramsFor(strength, index)
                val backward = ScrollJiggleMath.paramsFor(-strength, index)
                assertEquals(
                    "rotation symmetry at strength $strength index $index",
                    -forward.rotationDegrees, backward.rotationDegrees, 1e-6f,
                )
                assertEquals(
                    "lag symmetry at strength $strength index $index",
                    -forward.lagDp, backward.lagDp, 1e-6f,
                )
            }
            assertEquals(
                "travel symmetry at index $index",
                -ScrollJiggleMath.strengthForTravel(37f),
                ScrollJiggleMath.strengthForTravel(-37f),
                1e-6f,
            )
        }
    }

    @Test
    fun `phase is bounded and staggers across neighbours`() {
        for (index in -2000..2000) {
            assertTrue(
                "phase bounded at $index",
                abs(ScrollJiggleMath.phaseFor(index)) <= 1f,
            )
            val lagFactor = ScrollJiggleMath.lagFactorFor(index)
            assertTrue(
                "lag factor in [0.5, 1] at $index: $lagFactor",
                lagFactor in 0.5f..1f,
            )
        }
        // The stagger is real: across a screenful of items the phase
        // takes both signs and a wide spread of values, so the list
        // ripples instead of tilting as one rigid slab.
        val phases = (0..24).map { ScrollJiggleMath.phaseFor(it) }
        assertTrue("phases take both signs", phases.any { it > 0.3f } && phases.any { it < -0.3f })
        assertTrue(
            "phase spread is wide",
            ((phases.maxOrNull() ?: 0f) - (phases.minOrNull() ?: 0f)) > 1.2f,
        )
        val lagFactors = (0..24).map { ScrollJiggleMath.lagFactorFor(it) }
        assertTrue(
            "lag amounts vary per item",
            ((lagFactors.maxOrNull() ?: 0f) - (lagFactors.minOrNull() ?: 0f)) > 0.2f,
        )
    }

    @Test
    fun `lag always follows the scroll direction, never against it`() {
        // Whatever the item, the lag's sign is the strength's sign:
        // items trail the motion together (amounts vary, direction
        // never does), so neighbours can't shear apart.
        for (index in indices) {
            val forward = ScrollJiggleMath.paramsFor(0.8f, index)
            assertTrue("lag with the motion at $index", forward.lagDp > 0f)
            val backward = ScrollJiggleMath.paramsFor(-0.8f, index)
            assertTrue("lag with the motion at $index", backward.lagDp < 0f)
        }
    }
}
