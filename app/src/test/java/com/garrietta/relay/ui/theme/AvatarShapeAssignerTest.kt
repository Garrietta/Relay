package com.garrietta.relay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarShapeAssignerTest {

    @Test
    fun `shape set matches the M3E sheet vocabulary`() {
        val names = ExpressiveAvatarShapes.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf(
            "Oval", "Pill", "Triangle", "Arrow", "Fan",
            "Diamond", "Clamshell", "Pentagon", "Gem", "VerySunny",
            "Sunny", "Cookie4", "Cookie6", "Cookie7", "Cookie9",
        )))
        // No duplicate names; every entry resolves to a real shape.
        assertEquals(ExpressiveAvatarShapes.size, names.size)
        assertTrue(ExpressiveAvatarShapes.all { it.shape != null })
    }

    @Test
    fun `same key resolves to the same shape within a session`() {
        AvatarShapeAssigner.reshuffle()
        assertSame(
            AvatarShapeAssigner.shapeFor("alice@example"),
            AvatarShapeAssigner.shapeFor("alice@example"),
        )
    }

    @Test
    fun `first N distinct keys get N distinct shapes`() {
        AvatarShapeAssigner.reshuffle()
        val n = ExpressiveAvatarShapes.size
        val shapes = (0 until n).map { AvatarShapeAssigner.shapeFor("contact-$it") }.toSet()
        assertEquals(n, shapes.size)
    }

    @Test
    fun `expressiveShape looks up by name with blob fallback`() {
        assertNotNull(expressiveShape("Sunny"))
        // Unknown names fall back instead of crashing.
        assertNotNull(expressiveShape("Not A Real Shape"))
    }
}
