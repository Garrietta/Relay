package com.garrietta.relay.ui.motion

/*
 * Motion policy adapted from greykaizen/m3x-motion-recipes (Apache-2.0).
 * Original: https://github.com/greykaizen/m3x-motion-recipes
 * Changes: package renamed; the Navigation3 contact point
 * (LocalNavAnimatedContentScope) replaced with a local
 * LocalAnimatedVisibilityScope so it works with Relay's manual backstack.
 * Everything else — easing curves, duration tokens,
 * container transform, predictive back, reduced-motion fallback — kept whole.
 */

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset

/**
 * Centralized motion policy: feature code never hand-writes springs/tweens/slides.
 * It asks for a [MotionKind] and gets back a ready, spec-correct [ContentTransform].
 */
enum class MotionKind {
    /** Top-level destination change — crossfade in place, no horizontal motion. */
    TOP_LEVEL,

    /** Forward — shared-axis hierarchical navigation. */
    FORWARD,

    /** Backward — shared-axis hierarchical navigation (geometric mirror of FORWARD). */
    BACKWARD,

    /** Container transform — shared-element morph via [motionSharedBounds]. */
    CONTAINER,
}

/** Official Material 3 easing curves. Copy these; do not invent new ones. */
object M3Easing {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
}

/** Duration tokens (ms) — tune here once, nowhere else. */
object M3Duration {
    const val FADE_OUT = 100
    const val FADE_IN = 250
    const val TOP_LEVEL_IN = 250
    const val PREDICTIVE_PROGRESS_TOTAL = 100
    const val PREDICTIVE_SWAP_THRESHOLD = 35
    // Expressive duration tokens (M3 spec values).
    const val Short2 = 200
    const val Short4 = 400
    const val Medium1 = 250
    const val Medium2 = 300
    const val Long2 = 500
}

/**
 * Relay's unified bouncy motion language. Every animated transition in the
 * app uses these — no hand-rolled tweens at call sites. Springs on meaningful
 * transitions (nav, morphs, presses, entrances); everything eased, nothing
 * linear.
 */
object RelaySprings {
    const val STIFFNESS = 380f
    const val DAMPING = 0.62f

    /** The default: bouncy spring for presses, morphs, slides, entrances. */
    fun <T> bouncy(): FiniteAnimationSpec<T> =
        spring(stiffness = STIFFNESS, dampingRatio = DAMPING)

    /** Calmer spring for large, slow container motion. */
    fun <T> gentle(): FiniteAnimationSpec<T> =
        spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
}

/** Shared-axis travel is a fraction of the container width, not a fixed dp value. */
const val SHARED_AXIS_TRAVEL_DIVISOR = 4

/**
 * Shared-axis travel math (pure, unit-tested): the horizontal offsets
 * the FORWARD and BACKWARD pane transforms start and end at.
 * BACKWARD is the EXACT geometric mirror of FORWARD (Garrett
 * 2026-10-20) — every offset negated, the same divisor and specs — so
 * a pop retraces the push path precisely. Extracted from
 * [buildTransforms] so the mirror property is proven by tests
 * (SharedAxisTravelTest), not eyeballed at the call site.
 */
object SharedAxisTravel {

    /** Where an ENTERING destination starts (it animates to 0). */
    fun enterOffset(kind: MotionKind, fullWidth: Int): Int = when (kind) {
        MotionKind.FORWARD -> fullWidth / SHARED_AXIS_TRAVEL_DIVISOR
        MotionKind.BACKWARD -> -fullWidth / SHARED_AXIS_TRAVEL_DIVISOR
        else -> 0
    }

    /** Where an EXITING destination ends (it animates from 0). */
    fun exitOffset(kind: MotionKind, fullWidth: Int): Int = when (kind) {
        MotionKind.FORWARD -> -fullWidth / SHARED_AXIS_TRAVEL_DIVISOR
        MotionKind.BACKWARD -> fullWidth / SHARED_AXIS_TRAVEL_DIVISOR
        else -> 0
    }
}

/**
 * Concrete motion specs. On material3 1.4.0 (stable) there is no MotionScheme,
 * so these use the official M3 expressive token values directly.
 */
@Immutable
class MotionTokens internal constructor(
    /** defaultSpatial — used for FORWARD/BACKWARD travel. */
    val move: FiniteAnimationSpec<IntOffset>,
    /** defaultEffects — used for fades. */
    val fade: FiniteAnimationSpec<Float>,
) {
    companion object {
        @Composable
        fun resolve(): MotionTokens {
            return remember {
                MotionTokens(
                    move = tween(
                        durationMillis = M3Duration.Long2,
                        easing = M3Easing.EmphasizedDecelerate,
                    ),
                    fade = tween(
                        durationMillis = M3Duration.Short4,
                        easing = M3Easing.EmphasizedDecelerate,
                    ),
                )
            }
        }
    }
}

/**
 * Enabled by the user's reduced-motion preference OR the system animator scale being 0.
 * Provide it once at the app root; motion surfaces read it via [rememberReducedMotion]
 * and degrade to short fades / instant snaps.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current

/**
 * Pre-built [ContentTransform] per pattern. Resolved once per theme change.
 */
@Immutable
class MotionTransforms internal constructor(
    private val transforms: Map<MotionKind, ContentTransform>,
) {
    operator fun get(kind: MotionKind): ContentTransform = transforms.getValue(kind)
}

@Composable
fun rememberMotionTransforms(): MotionTransforms {
    val reducedMotion = LocalReducedMotion.current
    return remember(reducedMotion) { MotionTransforms(buildTransforms(reducedMotion)) }
}

private fun buildTransforms(reducedMotion: Boolean): Map<MotionKind, ContentTransform> = buildMap {
    put(MotionKind.TOP_LEVEL, crossfade())

    if (reducedMotion) {
        put(MotionKind.FORWARD, crossfade())
        put(MotionKind.BACKWARD, crossfade())
    } else {
        put(
            MotionKind.FORWARD,
            // Fluid bouncy travel: the spring overshoots slightly and
            // settles — no linear slides anywhere.
            (slideInHorizontally(RelaySprings.bouncy()) { full ->
                SharedAxisTravel.enterOffset(MotionKind.FORWARD, full)
            } +
                fadeIn(
                    tween(
                        M3Duration.FADE_IN,
                        delayMillis = M3Duration.FADE_OUT,
                        easing = M3Easing.EmphasizedDecelerate,
                    ),
                )) togetherWith
                (slideOutHorizontally(RelaySprings.bouncy()) { full ->
                    SharedAxisTravel.exitOffset(MotionKind.FORWARD, full)
                } +
                    fadeOut(tween(M3Duration.FADE_OUT, easing = M3Easing.Standard))),
        )

        put(
            MotionKind.BACKWARD,
            // Pop = the EXACT geometric mirror of FORWARD (Garrett
            // 2026-10-20): [SharedAxisTravel] negates every offset and
            // the very same specs are reused, so each screen retraces
            // the path it took in — the returning inbox slides in from
            // the -travel side it exited to, and the chat slides out to
            // the +travel side it entered from, on the identical bouncy
            // spring and fade tokens. No scale settle, no different
            // animation: the exit IS the entrance played in reverse.
            // The shared-element morphs (container bounds + avatar)
            // likewise run in both directions; see [motionSharedBounds]
            // for why their registration must never be direction-gated.
            (slideInHorizontally(RelaySprings.bouncy()) { full ->
                SharedAxisTravel.enterOffset(MotionKind.BACKWARD, full)
            } +
                fadeIn(
                    tween(
                        M3Duration.FADE_IN,
                        delayMillis = M3Duration.FADE_OUT,
                        easing = M3Easing.EmphasizedDecelerate,
                    ),
                )) togetherWith
                (slideOutHorizontally(RelaySprings.bouncy()) { full ->
                    SharedAxisTravel.exitOffset(MotionKind.BACKWARD, full)
                } +
                    fadeOut(tween(M3Duration.FADE_OUT, easing = M3Easing.Standard))),
        )
    }

    put(MotionKind.CONTAINER, crossfade())
}

/**
 * True crossfade — incoming and outgoing fade SIMULTANEOUSLY, so the two
 * screens blend through each other with no bare-background gap in between.
 * (The old fade-through faded out fully before fading in, which read as a
 * hard "solid screen" cut during navigation.)
 */
private fun crossfade(): ContentTransform =
    fadeIn(
        tween(
            M3Duration.Medium2,
            easing = M3Easing.EmphasizedDecelerate,
        ),
        initialAlpha = 0f,
    ) togetherWith fadeOut(
        tween(
            M3Duration.Medium2,
            easing = M3Easing.EmphasizedDecelerate,
        )
    )

/**
 * Provided at the navigation root, wrapping content in [SharedTransitionLayout].
 * Screens read it via [motionSharedBounds]/[motionSharedElement]; when absent
 * (previews), those modifiers degrade gracefully to no-ops.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** AnimatedVisibilityScope for the current destination; provided by the nav root. */
val LocalAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Master switch for shared-element registration in a subtree. The two-pane
 * detail pane provides `false`: its chat has no shared-element counterpart
 * in the list pane (the rows register under the inbox's own internal
 * category-swap scope), and a one-sided registration renders in the
 * SharedTransitionLayout overlay during the detail crossfade — the
 * two-pane transition flash. With registration off, the detail pane is a
 * pure crossfade and nothing can flash in the overlay.
 *
 * This is the ONLY registration gate left (Garrett 2026-10-20): it is a
 * static per-subtree property, never flipped in response to navigation,
 * so it cannot tear a match mid-transition the way the old
 * direction-based gate did (see [motionSharedBounds]).
 */
val LocalSharedElementsEnabled = staticCompositionLocalOf { true }

/** Namespaced keys prevent duplicate-key collisions across features. */
object SharedElementKeys {
    fun conversation(id: Any?): String = "conversation/$id"
    fun avatar(id: Any?): String = "avatar/$id"
}

/**
 * CONTAINER TRANSFORM — put the SAME key on the tapped origin AND the detail target.
 * Pass each side's own [shape] so corners round correctly in the transition overlay.
 *
 * BIDIRECTIONAL (Garrett 2026-10-20, close-flash root-cause fix): the
 * registration is unconditional — the morph runs on pop as the exact
 * reverse of the push, same [BoundsTransform] spring, endpoints
 * swapped, every frame clipped to the animated bounds by the overlay
 * clip. The previous push-only gate (a nav-direction flag read during
 * composition) REMOVED this registration in the same frame a pop
 * began. The shared-transition scope samples its matches from the
 * settled registrations as the transition starts, so the chat-side
 * entry was torn mid-flight — its orphaned overlay copy rendered for
 * several frames, unfaded and outside any morph path: the whole thread
 * surface displaced right/down over the returning inbox, text and
 * images readable at the wrong position, while the destination
 * underneath slid away (his device video, 2026-10-20 11:49; the same
 * ActiveMatchRemovedDuringTransition class the inbox's firstLoad
 * comment documents for the avatar). A registration that is never
 * added or removed in response to navigation cannot be torn: close
 * is now the true mirror of open at the shared layer too, matching
 * the pane transforms ([SharedAxisTravel]).
 *
 * IMPORTANT: apply at the actual visual endpoint only. Never inside reusable item
 * composables that may also be composed in measurement/offscreen layers — duplicate
 * shared-content registrations break the transition.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.motionSharedBounds(
    key: String,
    shape: Shape? = null,
    scaleToFit: ContentScale? = null,
): Modifier {
    if (!LocalSharedElementsEnabled.current) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalAnimatedVisibilityScope.current ?: return this
    val boundsTransform = rememberExpressiveBoundsTransform()

    return with(sharedScope) {
        val sharedContentState = rememberSharedContentState(key)
        val overlayClip = shape?.let { OverlayClip(it) }
        val resizeMode = scaleToFit?.let { SharedTransitionScope.ResizeMode.scaleToBounds(it) }
        when {
            overlayClip != null && resizeMode != null ->
                this@motionSharedBounds.sharedBounds(
                    sharedContentState = sharedContentState,
                    animatedVisibilityScope = animatedScope,
                    boundsTransform = boundsTransform,
                    resizeMode = resizeMode,
                    clipInOverlayDuringTransition = overlayClip,
                )

            overlayClip != null ->
                this@motionSharedBounds.sharedBounds(
                    sharedContentState = sharedContentState,
                    animatedVisibilityScope = animatedScope,
                    boundsTransform = boundsTransform,
                    clipInOverlayDuringTransition = overlayClip,
                )

            resizeMode != null ->
                this@motionSharedBounds.sharedBounds(
                    sharedContentState = sharedContentState,
                    animatedVisibilityScope = animatedScope,
                    boundsTransform = boundsTransform,
                    resizeMode = resizeMode,
                )

            else ->
                this@motionSharedBounds.sharedBounds(
                    sharedContentState = sharedContentState,
                    animatedVisibilityScope = animatedScope,
                    boundsTransform = boundsTransform,
                )
        }
    }
}

/**
 * AVATAR-ONLY shared bounds — BIDIRECTIONAL (Garrett 2026-10-20): the
 * avatar morphs up into the chat header when a conversation opens AND
 * morphs back down into its inbox row when the chat exits — the exact
 * reverse movement, driven by the same [BoundsTransform] spring in both
 * directions, for EVERY chat (1:1 and group alike; group clusters carry
 * this modifier on the whole cluster).
 *
 * The 2026-10-19 pop-flash that originally disabled the reverse morph was
 * a SYMPTOM, not a property of the morph: the inbox returned at scroll
 * position 0, so the morph's target row was absent or at the wrong place
 * and the avatar drew in the overlay with nowhere true to land. The inbox
 * now restores its exact pre-chat scroll position on the first pop frame
 * (see InboxViewModel's chat-exit scroll restore point), so the target is
 * always where the entrance left it.
 *
 * Scoped NARROWLY to the avatar/cluster: message content carries no
 * shared modifiers, so the #45 text-ghosting fix is preserved.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.motionSharedAvatar(key: String, shape: Shape? = null): Modifier {
    if (!LocalSharedElementsEnabled.current) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalAnimatedVisibilityScope.current ?: return this
    val boundsTransform = rememberExpressiveBoundsTransform()

    return with(sharedScope) {
        val sharedContentState = rememberSharedContentState(key)
        val overlayClip = shape?.let { OverlayClip(it) }
        if (overlayClip != null) {
            this@motionSharedAvatar.sharedBounds(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedScope,
                boundsTransform = boundsTransform,
                clipInOverlayDuringTransition = overlayClip,
            )
        } else {
            this@motionSharedAvatar.sharedBounds(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedScope,
                boundsTransform = boundsTransform,
            )
        }
    }
}

/**
 * Shared ELEMENT (not bounds) — the content itself morphs. Use for a single icon/image
 * that travels between two layouts. Same key + scope rules as [motionSharedBounds]:
 * registration is unconditional and bidirectional (see the close-flash
 * note there); no call sites exist today.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.motionSharedElement(key: String, shape: Shape? = null): Modifier {
    if (!LocalSharedElementsEnabled.current) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalAnimatedVisibilityScope.current ?: return this
    val boundsTransform = rememberExpressiveBoundsTransform()

    return with(sharedScope) {
        val sharedContentState = rememberSharedContentState(key)
        val overlayClip = shape?.let { OverlayClip(it) }
        if (overlayClip != null) {
            this@motionSharedElement.sharedElement(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedScope,
                boundsTransform = boundsTransform,
                clipInOverlayDuringTransition = overlayClip,
            )
        } else {
            this@motionSharedElement.sharedElement(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedScope,
                boundsTransform = boundsTransform,
            )
        }
    }
}

@Composable
private fun rememberExpressiveBoundsTransform(): BoundsTransform {
    val reducedMotion = LocalReducedMotion.current
    // Bouncy morph: the shared element overshoots slightly and settles —
    // this is the fluid avatar travel Garrett asked for.
    val spec: FiniteAnimationSpec<Rect> = if (reducedMotion) {
        tween(durationMillis = M3Duration.FADE_IN)
    } else {
        RelaySprings.bouncy()
    }
    return remember(spec) { BoundsTransform { _, _ -> spec } }
}
