package com.garrietta.relay.ui.theme

import androidx.compose.ui.graphics.Shape
import kotlin.random.Random

/**
 * Per-session avatar shape rotation.
 *
 * Garrett: "The profile images ... should each take a different expressive
 * shape, running through a set list, maybe even changing shape on each
 * startup." So: on every app startup [reshuffle] deals a fresh random order of
 * [ExpressiveAvatarShapes]; [namedShapeFor] then picks from that order
 * DETERMINISTICALLY BY KEY — the same contact always resolves to the same
 * shape within the session, no matter which screen asks first (the old
 * sequential hand-out made the inbox and the chat header disagree).
 *
 * Keys are normalized to digits-only when they contain digits, so
 * "+1 (765) 341-9588" and "7653419588" map to the same shape; pure name
 * keys ("Grace") are used as-is.
 *
 * [namedShapeFor] returns the full [NamedShape] (including
 * [NamedShape.aspectRatio]); [shapeFor] is the shape-only convenience.
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

    /** Normalizes a shape key so formatting variants of one contact agree. */
    fun normalizeKey(key: String): String {
        val digits = key.filter { it.isDigit() }
        return digits.ifEmpty { key }
    }

    fun namedShapeFor(key: String): NamedShape = synchronized(lock) {
        val norm = normalizeKey(key)
        assigned.getOrPut(norm) {
            order[(norm.hashCode() and Int.MAX_VALUE) % order.size]
        }
    }

    fun shapeFor(key: String): Shape = namedShapeFor(key).shape
}
