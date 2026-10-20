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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection

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
 * The inbox chrome band, in window pixels: the vertical span the
 * conversation-list surface occupies, between the inbox's top chrome
 * (the collapsing "Messages" header + the category pill bar) above and
 * the floating bottom cluster (New conversation pill + search button)
 * below. [topPx] is the list surface's top edge; [bottomPx] is the
 * bottom cluster's top edge.
 *
 * Written by InboxScreen from layout callbacks — deliberately NOT
 * snapshot state: the top edge moves 1:1 with list scroll (the header
 * collapses), and reading it in composition would recompose every row
 * on every scroll frame. Read by [ChromeBandOverlayClip] once per
 * overlay frame at draw time, so a transition always clips against the
 * chrome geometry in effect at that moment — including mid-collapse.
 *
 * The bottom edge is the cluster SLOT's top. The slot's height is a
 * constant across the cluster's FULL and MINIMIZED forms (the form
 * morph is a crossfade inside the slot, never a resize — see
 * ClusterStateLogic), so this one line serves the full pill, the
 * minimized circle, and every frame of the morph between them; the
 * traveling surface lands behind the cluster whatever form it is in.
 *
 * Coordinates: `positionInWindow` of the measured nodes. The nav
 * root's SharedTransitionLayout fills the window at its origin in
 * both hosts, so window coordinates ARE the shared scope's
 * coordinates — the space [SharedTransitionScope.OverlayClip]'s
 * bounds arrive in. Until the inbox first lays out, [topPx] = 0 and
 * [bottomPx] = +infinity, which degenerates to the unclipped shape
 * clip; the inbox is the start destination, so real values land
 * before any chat can open.
 */
object InboxChromeBand {
    @Volatile var topPx: Float = 0f
    @Volatile var bottomPx: Float = Float.POSITIVE_INFINITY
}

/** Pure span math for [ChromeBandOverlayClip] (unit-tested). */
object ChromeBandClipMath {

    /**
     * The [top, bottom] slice of a vertical span that lies inside the
     * band [bandTopPx, bandBottomPx]: the span clamped to the band.
     * A span lying entirely outside the band (or a degenerate band)
     * yields a zero-height slice (bottom == top), never an inverted
     * one, so callers can build a Rect directly from the result.
     */
    fun visibleSpan(
        top: Float,
        bottom: Float,
        bandTopPx: Float,
        bandBottomPx: Float,
    ): Pair<Float, Float> {
        val clippedTop = maxOf(top, bandTopPx)
        val clippedBottom = minOf(bottom, bandBottomPx)
        if (clippedBottom >= clippedTop) return clippedTop to clippedBottom
        // No overlap: park the zero-height slice on the band edge the
        // span sits beyond — above the band, its top edge; below it,
        // its bottom edge (a degenerate crossed band also lands here).
        val park = if (bottom <= bandTopPx) bandTopPx else bandBottomPx
        return park to park
    }

    /**
     * The band's bottom edge for a cluster slot whose layout top is
     * [clusterTopPx] and whose measured height is [clusterHeightPx]:
     * the slot's top while the cluster is composed, +infinity once it
     * leaves composition (height 0) — with no cluster on screen there
     * is nothing at the bottom for a morph to pass behind, so the
     * band opens to the window bottom, exactly as it did before the
     * band existed.
     *
     * Form-independent by construction: the FULL cluster (pill +
     * search button) and the MINIMIZED circular button share the one
     * constant-height slot (see ClusterStateLogic.SLOT_HEIGHT_DP), so
     * any composed height yields the slot's top, for either form and
     * every frame of the morph between them.
     */
    fun bandBottomForCluster(clusterTopPx: Float, clusterHeightPx: Int): Float =
        if (clusterHeightPx > 0) clusterTopPx else Float.POSITIVE_INFINITY
}

/**
 * Overlay clip for the conversation CONTAINER morph
 * ([motionSharedBounds]): the element's own shape clip, intersected
 * with the inbox chrome band ([InboxChromeBand]).
 *
 * The SharedTransitionLayout overlay is the topmost surface in the
 * nav root — it draws above EVERY destination's own chrome. The
 * container morph's animated bounds sweep between the thread surface
 * and the tapped row, crossing the band the inbox header occupies,
 * so unclipped, the returning thread's text painted OVER the
 * "Messages" title and the category pills for the top row — the only
 * row whose morph path reaches that band (Garrett's device video,
 * 2026-10-20 12:37; symmetrically, a morph to/from the last visible
 * row could cross the floating bottom cluster). Intersecting the
 * copy with the band makes the traveling surface pass BEHIND the
 * chrome in both directions: it emerges from under the header line,
 * lands in its row, and never hovers over the main page's elements —
 * at any header collapse state, for 1:1 and group rows alike, because
 * the clip hangs on the shared container registration itself, not on
 * any row composable.
 *
 * Deliberately NOT applied to [motionSharedAvatar]: the avatar's
 * chat-side endpoint lives in the chat header, ABOVE the band —
 * band-clipping it would erase the glyph for most of its flight and
 * kill the picture movement. The band protects the chrome from page
 * CONTENT (text, images, whole surfaces); the avatar is a compact
 * pictorial element traveling between two pages' own header/row
 * slots, which is the morph Garrett asked to keep as-is.
 */
private class ChromeBandOverlayClip(
    private val shape: Shape?,
) : SharedTransitionScope.OverlayClip {

    private val shapePath = Path()
    private val bandPath = Path()
    private val clippedPath = Path()

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        shapePath.reset()
        if (shape != null) {
            // The framework's own shape-clip construction: the shape's
            // outline at the animated bounds' size, offset to the
            // bounds' position in the shared scope.
            shapePath.addOutline(shape.createOutline(bounds.size, layoutDirection, density))
            shapePath.translate(bounds.topLeft)
        } else {
            shapePath.addRect(bounds)
        }
        val bandTop = InboxChromeBand.topPx
        val bandBottom = InboxChromeBand.bottomPx
        if (bandTop <= bounds.top && bandBottom >= bounds.bottom) {
            // Bounds fully inside the band: the shape clip alone is
            // the answer — the common case for every non-top row.
            return shapePath
        }
        val (visibleTop, visibleBottom) =
            ChromeBandClipMath.visibleSpan(bounds.top, bounds.bottom, bandTop, bandBottom)
        bandPath.reset()
        bandPath.addRect(Rect(bounds.left, visibleTop, bounds.right, visibleBottom))
        clippedPath.reset()
        clippedPath.op(shapePath, bandPath, PathOperation.Intersect)
        return clippedPath
    }
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
 * The overlay clip is [ChromeBandOverlayClip]: the element's shape clip
 * intersected with the inbox chrome band, so the traveling surface
 * passes BEHIND the main page's header and bottom cluster instead of
 * painting over them (the top-row hover, Garrett 2026-10-20 12:37).
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
        val overlayClip = ChromeBandOverlayClip(shape)
        val resizeMode = scaleToFit?.let { SharedTransitionScope.ResizeMode.scaleToBounds(it) }
        if (resizeMode != null) {
            this@motionSharedBounds.sharedBounds(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedScope,
                boundsTransform = boundsTransform,
                resizeMode = resizeMode,
                clipInOverlayDuringTransition = overlayClip,
            )
        } else {
            this@motionSharedBounds.sharedBounds(
                sharedContentState = sharedContentState,
                animatedVisibilityScope = animatedScope,
                boundsTransform = boundsTransform,
                clipInOverlayDuringTransition = overlayClip,
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
