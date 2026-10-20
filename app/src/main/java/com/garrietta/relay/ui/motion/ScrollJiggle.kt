package com.garrietta.relay.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.sin

/**
 * Scroll-velocity jiggle (Garrett 2026-10-20): while a list scrolls,
 * its message bubbles and contact picture icons get a subtle
 * velocity-proportional micro-transform, so the app feels faintly
 * alive under the finger — "nothing crazy", nothing ever out of
 * place. At rest the transform is exactly identity.
 *
 * One [ScrollJiggle] signal per list: the raw scroll travel is
 * normalized and clamped by [ScrollJiggleMath], then spring-smoothed
 * through an [Animatable] on the house spring ([RelaySprings.bouncy]),
 * so both the onset and the settle are soft and the stop is a gentle
 * spring-back to neutral — never a snap, never a residual offset.
 * Items read the smoothed value inside a draw-phase `graphicsLayer`
 * lambda ([Modifier.scrollJiggle]): no per-item animation state, no
 * recomposition per frame, and — because graphicsLayer never affects
 * layout — item sizes, spacing, scroll offsets and hit layout are
 * bit-identical to a list without the effect.
 *
 * HARD CAPS (in [ScrollJiggleMath], enforced on the final values,
 * spring overshoot included): rotation ~1.6 degrees (brief's ceiling
 * ~2), vertical lag 2dp. A violent fling clamps to the same caps as a
 * brisk scroll — only a real fling earns the full amplitude.
 *
 * MORPH NEUTRALITY: in the inbox the jiggle layer sits INSIDE the
 * avatar's shared-element modifier chain (after `motionSharedAvatar`,
 * beside the press-scale layer). Shared-element morph geometry is
 * computed from LAYOUT bounds; a graphicsLayer is a draw-phase-only
 * transform that cannot change any layout bound, so the morph's start
 * and end bounds, its BoundsTransform path and its overlay clip are
 * exactly what they were without the jiggle. During transitions the
 * lists are not scrolling anyway, so the smoothed value sits at ~0
 * and the overlay copy draws unrotated.
 *
 * REDUCED MOTION: when [LocalReducedMotion] is active the handle is
 * disabled, the driver effect never runs, and the modifier is a
 * no-op — the effect simply does not exist.
 */
object ScrollJiggleMath {

    /** Rotation ceiling in degrees (hard cap; the brief's ~2 degrees). */
    const val MAX_ROTATION_DEGREES = 1.6f

    /** Vertical lag ceiling in dp (hard cap; the brief's ~2dp). */
    const val MAX_LAG_DP = 2f

    /**
     * Scroll travel (px per scroll-state emission, ~one frame of
     * motion) that maps to full strength. Deliberately brisk: a slow
     * drag moves a few px per frame and earns only a whisper; a real
     * fling opens at tens of px per frame and saturates.
     */
    const val FULL_STRENGTH_TRAVEL_PX = 90f

    // Golden angle (radians): successive indices land on unrelated
    // phases, so neighbours never tilt in lockstep — the movement
    // ripples through the list instead of tilting it rigidly.
    private const val ROTATION_PHASE_STEP = 2.3999631
    private const val LAG_PHASE_STEP = 1.3247180
    private const val LAG_PHASE_SHIFT = 1.7

    /** The transform for one item: a rotation (degrees) and a vertical lag (dp). */
    data class Params(val rotationDegrees: Float, val lagDp: Float) {
        companion object {
            val IDENTITY = Params(0f, 0f)
        }
    }

    /**
     * Per-item rotation phase in [-1, 1], deterministic from the
     * item's index in the list. Adjacent items get near-unrelated
     * phases (golden-angle stepping); the phase is bounded by
     * construction (a sine).
     */
    fun phaseFor(index: Int): Float =
        sin(index * ROTATION_PHASE_STEP).toFloat()

    /**
     * Per-item lag factor in [0.5, 1]: every item lags in the SAME
     * direction (with the scroll's inertia), only the amount varies,
     * so the list breathes instead of shearing apart.
     */
    fun lagFactorFor(index: Int): Float =
        (0.75 + 0.25 * cos(index * LAG_PHASE_STEP + LAG_PHASE_SHIFT)).toFloat()

    /**
     * Normalized strength in [-1, 1] from one emission's scroll
     * travel in px. Sign follows the travel direction, so reversing
     * the scroll reverses the jiggle. Any magnitude of fling clamps
     * to ±1 — the caps can never be exceeded through the input.
     */
    fun strengthForTravel(travelPx: Float): Float =
        (travelPx / FULL_STRENGTH_TRAVEL_PX).coerceIn(-1f, 1f)

    /**
     * The transform for the item at [index] under the smoothed
     * [strength]. The strength is clamped HERE as well, so even a
     * spring overshoot past ±1 in the driver can never push a
     * rendered value past the caps. Zero strength is exact identity
     * for every index.
     */
    fun paramsFor(strength: Float, index: Int): Params {
        val s = strength.coerceIn(-1f, 1f)
        if (s == 0f) return Params.IDENTITY
        return Params(
            rotationDegrees = s * phaseFor(index) * MAX_ROTATION_DEGREES,
            lagDp = s * lagFactorFor(index) * MAX_LAG_DP,
        )
    }
}

/**
 * One list's jiggle signal: wraps the spring-smoothed strength.
 * [strength] is snapshot state — read it inside a graphicsLayer
 * lambda (draw phase); reading it in composition would recompose
 * per frame and is not how this is meant to be consumed.
 */
class ScrollJiggle internal constructor(
    private val animatable: Animatable<Float, AnimationVector1D>,
    val enabled: Boolean,
) {
    /** Smoothed strength in [-1, 1]; 0 at rest and when disabled. */
    val strength: Float
        get() = if (enabled) animatable.value else 0f
}

/**
 * Creates and drives the [ScrollJiggle] signal for [listState].
 *
 * The driver watches the same (firstVisibleItemIndex,
 * firstVisibleItemScrollOffset) pair the inbox's other scroll
 * effects use, plus `isScrollInProgress`: each emission's travel
 * (offset delta, plus index delta x the average visible item size
 * when the first item changes) becomes the spring's target via
 * [ScrollJiggleMath.strengthForTravel]; `collectLatest` retargets
 * the spring on every emission, so the value tracks the fling
 * instead of chasing stale targets. When scrolling stops, the
 * emission carries target 0 and the spring settles everything back
 * to neutral. A programmatic jump (scroll restore, tail glide)
 * feeds the same signal for a frame or two; a single-step jump
 * self-cancels before the spring can travel, and a sustained glide
 * jiggles exactly like the user scroll it mimics.
 */
@Composable
fun rememberScrollJiggle(listState: LazyListState): ScrollJiggle {
    val reducedMotion = rememberReducedMotion()
    val animatable = remember { Animatable(0f) }
    val jiggle = remember(animatable, reducedMotion) {
        ScrollJiggle(animatable, enabled = !reducedMotion)
    }
    LaunchedEffect(listState, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collectLatest { (index, offset, scrolling) ->
            // Average visible item size turns an index change into
            // approximate px so travel stays continuous when the
            // first visible item rolls over mid-fling. (Estimation
            // noise is harmless: the input is clamped and spring-
            // smoothed before anything renders it.)
            val avgItemPx = listState.layoutInfo.visibleItemsInfo
                .takeIf { it.isNotEmpty() }
                ?.map { it.size }?.average()?.toFloat() ?: 0f
            val travel = (offset - prevOffset) + (index - prevIndex) * avgItemPx
            prevIndex = index
            prevOffset = offset
            animatable.animateTo(
                targetValue = if (scrolling) {
                    ScrollJiggleMath.strengthForTravel(travel)
                } else {
                    0f
                },
                animationSpec = RelaySprings.bouncy(),
            )
        }
    }
    return jiggle
}

/**
 * Applies this item's jiggle transform (rotation + vertical lag,
 * per [ScrollJiggleMath.paramsFor]) in a draw-phase graphicsLayer.
 * A null or disabled [jiggle] returns the modifier unchanged — no
 * layer, no cost. The lambda reads [ScrollJiggle.strength] inside
 * the layer, so scroll frames redraw without recomposing.
 */
fun Modifier.scrollJiggle(jiggle: ScrollJiggle?, index: Int): Modifier {
    if (jiggle == null || !jiggle.enabled) return this
    return graphicsLayer {
        val params = ScrollJiggleMath.paramsFor(jiggle.strength, index)
        rotationZ = params.rotationDegrees
        translationY = params.lagDp * density
    }
}
