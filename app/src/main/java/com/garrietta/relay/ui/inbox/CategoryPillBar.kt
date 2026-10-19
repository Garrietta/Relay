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
 * The inbox category bar — Garrett's 2026-10-19 "chocolate bar" directive:
 * ONE continuous pill bifurcated by dividers. The end chips (All first,
 * Unknown last) are ALWAYS half-rectangle / half-oval — rounded on the
 * outward edge, square on the inward edge — whether selected or not. Middle
 * chips are pure rectangles. When a chip is SELECTED it morphs into a full
 * oval and pops out of the bar (scale + z-order), bouncy.
 *
 * Unselected, the chips tile into the segmented strip; selected, the tapped
 * chip blooms into the floating oval. The morph fires the frame the tap
 * lands (RelaySprings bouncy).
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
            .height(52.dp),
    ) {
        val density = LocalDensity.current
        val containerWPx = with(density) { maxWidth.toPx() }
        // Equal chips while they fit at a readable width; beyond that the
        // bar scrolls with fixed-width chips (still equal).
        val minChipPx = with(density) { 96.dp.toPx() }
        val fillMode = count * minChipPx <= containerWPx
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

        // No gaps: chips tile edge-to-edge into the continuous bar, split
        // by 1dp dividers — the chocolate bar.
        Row(
            modifier = if (fillMode) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            segmentIds.forEachIndexed { index, id ->
                val isFirst = index == 0
                val isLast = index == count - 1
                ChocolateSegment(
                    name = segmentNames[index],
                    selected = index == selectedIndex,
                    isFirst = isFirst,
                    isLast = isLast,
                    onClick = { onSelect(id) },
                    modifier = (if (fillMode) Modifier.weight(1f) else Modifier.width(120.dp))
                        .fillMaxHeight()
                        // The selected oval pops OUT of the bar, drawn over
                        // its neighbors.
                        .zIndex(if (index == selectedIndex) 1f else 0f),
                )
                if (!isLast) SegmentDivider()
            }
        }
    }
}

/**
 * One chocolate-bar segment. Unselected ends are half-oval (rounded outward,
 * square inward); middles are pure rectangles. On selection the segment
 * morphs to a FULL oval, fills, and scales up over its neighbors.
 */
@Composable
private fun RowScope.ChocolateSegment(
    name: String,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
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
    fun corner(outwardRounded: Boolean): androidx.compose.ui.unit.Dp {
        val base = if (outwardRounded) 26f else 0f
        return (base + (26f - base) * sel).dp
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
    val popScale = 1f + 0.08f * sel
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** The bifurcation between chocolate-bar segments. */
@Composable
private fun RowScope.SegmentDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .align(Alignment.CenterVertically)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
    )
}
