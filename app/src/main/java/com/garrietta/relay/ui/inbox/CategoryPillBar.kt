package com.garrietta.relay.ui.inbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.ui.motion.RelaySprings
import kotlin.math.roundToInt

/**
 * The inbox category bar — Garrett's 2026-10-19 directive, refined 2026-10-19
 * evening: SEPARATE chips with significant spacing between them (his
 * music-app reference), each morphing WITHIN its own padded slot so the
 * selected oval can never overlap its neighbors. The end chips (All first,
 * Unknown last) are ALWAYS half-oval outward with a slight radius on the
 * inward edge — whether selected or not. Middle chips are rounded
 * rectangles with a slight (18dp) radius, matching the reference. When a
 * chip is SELECTED it morphs into a full oval and pops (scale + z-order),
 * bouncy.
 *
 * Unselected, the chips sit as the spaced strip; selected, the tapped chip
 * blooms into the floating oval inside its own slot. The morph fires the
 * frame the tap lands (RelaySprings bouncy).
 *
 * Segments: "All" first (always), then the user's custom categories in
 * their Settings order, then the built-in Unknown tab last. Equal widths
 * while they fit (2 = halves, 3 = thirds, ...); beyond a readable width the
 * bar scrolls with fixed-width segments. Shown when at least one custom
 * category exists or the Unknown tab is on.
 *
 * [selectedId]: null = "All".
 */
@Composable
fun CategoryPillBar(
    categories: List<ChatCategory>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return
    val segmentIds: List<String?> = remember(categories) {
        listOf(null) + categories.map { it.id }
    }
    val segmentNames: List<String> = remember(categories) {
        listOf("All") + categories.map { it.name }
    }
    val count = segmentIds.size
    val selectedIndex = segmentIds.indexOf(selectedId).takeIf { it >= 0 } ?: 0

    // BoxWithConstraints is measurement only — it draws nothing. The chips
    // themselves are the bar.
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(40.dp),
    ) {
        val density = LocalDensity.current
        val containerWPx = with(density) { maxWidth.toPx() }
        // Squeeze rule (Garrett 2026-10-19 evening): up to 5 chips total
        // (All + customs + Unknown) always fit without scrolling — text
        // shrinks to make room. Horizontal scroll only kicks in at 6+.
        val fillMode = count <= 5
        // Compact text when squeezing 4-5 chips in.
        val compact = fillMode && count >= 4
        val scrollState = rememberScrollState()

        // Keep the selected chip on screen in scroll mode.
        LaunchedEffect(selectedIndex, fillMode) {
            if (!fillMode) {
                val chipPx = with(density) { 120.dp.toPx() }
                val target = (selectedIndex * chipPx - containerWPx / 2 + chipPx / 2)
                    .roundToInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target, RelaySprings.bouncy())
            }
        }

        // Separate chips with significant spacing (Garrett 2026-10-19
        // evening): each chip morphs WITHIN its own slot, so the selected
        // oval's pop-out scale can never reach its neighbors.
        Row(
            modifier = if (fillMode) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            segmentIds.forEachIndexed { index, id ->
                val isFirst = index == 0
                val isLast = index == count - 1
                ChocolateSegment(
                    name = segmentNames[index],
                    selected = index == selectedIndex,
                    isFirst = isFirst,
                    isLast = isLast,
                    compact = compact,
                    onClick = { onSelect(id) },
                    modifier = (if (fillMode) Modifier.weight(1f) else Modifier.width(120.dp))
                        .fillMaxHeight()
                        // The selected oval pops within its own slot, drawn
                        // over the gaps (never over neighbors).
                        .zIndex(if (index == selectedIndex) 1f else 0f),
                )
            }
        }
    }
}

/**
 * One chocolate-bar segment. Unselected middles are rounded rectangles with
 * a slight corner radius (Garrett 2026-10-19 evening: match the music-app
 * reference — NOT sharp rectangles); unselected end caps are half-oval
 * outward with the same slight radius on the inward edge. On selection the
 * segment morphs to a FULL oval, fills, and scales up within its own slot.
 */
@Composable
private fun RowScope.ChocolateSegment(
    name: String,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selection morph 0->1 on the bouncy spring; every corner lerps from
    // its tiled value to the full 26dp oval.
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = RelaySprings.bouncy(),
        label = "chocSel",
    )
    // CRASH GUARD (Garrett 2026-10-19 evening: IllegalArgumentException
    // "Corner size in Px can't be negative"): the bouncy spring overshoots
    // below 0 / above 1 mid-animation. Clamp the morph factor so no animated
    // corner radius can ever go negative.
    // Unselected middle chips are rounded rectangles with a slight radius
    // (music-app reference, Garrett 2026-10-19 evening) — 14dp at the
    // reduced 40dp bar height, not sharp. End caps keep the half-oval
    // outward edge and take the same slight radius on the inward edge.
    // Selection morphs everything to the full 20dp stadium oval.
    val selC = sel.coerceIn(0f, 1f)
    fun corner(outwardRounded: Boolean): androidx.compose.ui.unit.Dp {
        val base = if (outwardRounded) 20f else 14f
        return (base + (20f - base) * selC).dp.coerceAtLeast(0.dp)
    }
    val shape = RoundedCornerShape(
        topStart = corner(isFirst),
        bottomStart = corner(isFirst),
        topEnd = corner(isLast),
        bottomEnd = corner(isLast),
    )
    // Color snaps fast (tween): a bouncy spring overshoots through
    // out-of-gamut intermediates on color, which reads as a flash.
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(180),
        label = "chocBg",
    )
    // Tactile press scale composes with the selected pop-out scale.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "chocPress",
    )
    val popScale = 1f + 0.08f * selC
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = popScale * pressScale
                scaleY = popScale * pressScale
            }
            .clip(shape)
            .background(bgColor)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = if (compact) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 8.dp),
        )
    }
}
