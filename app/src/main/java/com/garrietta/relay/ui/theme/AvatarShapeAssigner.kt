package com.garrietta.relay.ui.theme

import androidx.compose.ui.graphics.Shape
import kotlin.random.Random

/**
 * Per-session avatar shape rotation.
 *
 * Garrett: "The profile images ... should each take a different expressive
 * shape, running through a set list, maybe even changing shape on each
 * startup." So: on every app startup [reshuffle] deals a fresh random order of
 * [ExpressiveAvatarShapes]; [shapeFor] then hands shapes out sequentially —
 * the first N distinct contacts get N distinct shapes (while N <= set size),
 * and the same key always resolves to the same shape WITHIN the session.
 *
 * Call [reshuffle] from Application.onCreate so a cold start always feels
 * fresh. State is guarded for concurrent composition reads.
 */
object AvatarShapeAssigner {
    private val lock = Any()
    private var order: List<Shape> = ExpressiveAvatarShapes.map { it.shape }
    private val assigned = LinkedHashMap<String, Shape>()

    fun reshuffle() {
        synchronized(lock) {
            order = ExpressiveAvatarShapes.map { it.shape }
                .shuffled(Random(System.currentTimeMillis()))
            assigned.clear()
        }
    }

    fun shapeFor(key: String): Shape = synchronized(lock) {
        assigned.getOrPut(key) { order[assigned.size % order.size] }
    }
}
