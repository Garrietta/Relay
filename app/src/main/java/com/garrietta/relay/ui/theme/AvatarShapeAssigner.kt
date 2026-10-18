package com.garrietta.relay.ui.theme

import androidx.compose.ui.graphics.Shape
import kotlin.random.Random

/**
 * Per-session avatar shape rotation.
 *
 * Garrett: "The profile images ... should each take a different expressive
 * shape, running through a set list, maybe even changing shape on each
 * startup." So: on every app startup [reshuffle] deals a fresh random order of
 * [ExpressiveAvatarShapes]; [namedShapeFor] then hands shapes out sequentially —
 * the first N distinct contacts get N distinct shapes (while N <= set size),
 * and the same key always resolves to the same shape WITHIN the session.
 *
 * [namedShapeFor] returns the full [NamedShape] (including [NamedShape.aspectRatio],
 * which avatar containers use to keep every shape at the same visual height);
 * [shapeFor] is the shape-only convenience.
 *
 * Call [reshuffle] from Application.onCreate so a cold start always feels
 * fresh. State is guarded for concurrent composition reads.
 */
object AvatarShapeAssigner {
    private val lock = Any()
    private var order: List<NamedShape> = ExpressiveAvatarShapes
    private val assigned = LinkedHashMap<String, NamedShape>()

    fun reshuffle() {
        synchronized(lock) {
            order = ExpressiveAvatarShapes.shuffled(Random(System.currentTimeMillis()))
            assigned.clear()
        }
    }

    fun namedShapeFor(key: String): NamedShape = synchronized(lock) {
        assigned.getOrPut(key) { order[assigned.size % order.size] }
    }

    fun shapeFor(key: String): Shape = namedShapeFor(key).shape
}
