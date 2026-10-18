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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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

/** Shared-axis travel is a fraction of the container width, not a fixed dp value. */
const val SHARED_AXIS_TRAVEL_DIVISOR = 4

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
    val tokens = MotionTokens.resolve()
    val reducedMotion = LocalReducedMotion.current
    return remember(tokens, reducedMotion) { MotionTransforms(buildTransforms(tokens, reducedMotion)) }
}

private fun buildTransforms(t: MotionTokens, reducedMotion: Boolean): Map<MotionKind, ContentTransform> = buildMap {
    put(MotionKind.TOP_LEVEL, crossfade())

    if (reducedMotion) {
        put(MotionKind.FORWARD, crossfade())
        put(MotionKind.BACKWARD, crossfade())
    } else {
        put(
            MotionKind.FORWARD,
            (slideInHorizontally(t.move) { full -> full / SHARED_AXIS_TRAVEL_DIVISOR } +
                fadeIn(
                    tween(
                        M3Duration.FADE_IN,
                        delayMillis = M3Duration.FADE_OUT,
                        easing = M3Easing.EmphasizedDecelerate,
                    ),
                )) togetherWith
                (slideOutHorizontally(t.move) { full -> -full / SHARED_AXIS_TRAVEL_DIVISOR } +
                    fadeOut(tween(M3Duration.FADE_OUT, easing = M3Easing.Standard))),
        )

        put(
            MotionKind.BACKWARD,
            // Pop: the exiting screen fades and shrinks WITHIN its bounds
            // (already clipped to its container) — no horizontal slide, so
            // its content can never sweep across the incoming destination.
            // The inbox fades in beneath it.
            fadeIn(
                tween(
                    M3Duration.FADE_IN,
                    easing = M3Easing.EmphasizedDecelerate,
                ),
            ) togetherWith
                (fadeOut(tween(150, easing = M3Easing.Standard)) +
                    scaleOut(
                        tween(
                            M3Duration.Short4,
                            easing = M3Easing.EmphasizedDecelerate,
                        ),
                        targetScale = 0.96f,
                    )),
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
 * Navigation direction for the current transition, provided by the nav root.
 * Shared-element morphs run on push only: on pop the shared bounds are
 * disabled so the exiting screen fades and shrinks within its own bounds —
 * nothing from the exiting screen may draw above the incoming one (the
 * transition overlay always renders above both destinations). Defaults to
 * true so surfaces outside the single-pane backstack (two-pane detail,
 * previews) keep the morph behavior.
 */
val LocalNavForward = staticCompositionLocalOf { true }

/** Namespaced keys prevent duplicate-key collisions across features. */
object SharedElementKeys {
    fun conversation(id: Any?): String = "conversation/$id"
    fun avatar(id: Any?): String = "avatar/$id"
}

/**
 * CONTAINER TRANSFORM — put the SAME key on the tapped origin AND the detail target.
 * Pass each side's own [shape] so corners round correctly in the transition overlay.
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
    // No shared morph on pop: the transition overlay renders above both
    // destinations, so a morphing exiting screen would draw over the
    // incoming one (e.g. thread text ghosting over the inbox pill).
    if (!LocalNavForward.current) return this
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
 * Shared ELEMENT (not bounds) — the content itself morphs. Use for a single icon/image
 * that travels between two layouts. Same key + scope rules as [motionSharedBounds].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.motionSharedElement(key: String, shape: Shape? = null): Modifier {
    if (!LocalNavForward.current) return this
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
    val spec: FiniteAnimationSpec<Rect> = if (reducedMotion) {
        tween(durationMillis = M3Duration.FADE_IN)
    } else {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }
    return remember(spec) { BoundsTransform { _, _ -> spec } }
}
