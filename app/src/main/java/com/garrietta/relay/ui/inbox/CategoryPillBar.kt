package com.garrietta.relay.ui.inbox

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.ui.motion.RelaySprings
import kotlin.math.roundToInt

/**
 * The inbox category bar — Garrett's 2026-10-19 directive, modeled on the
 * music app's top pill bar (see his screen recording): ONE full-width pill
 * divided into N EQUAL segments (2 categories = split down the middle,
 * 3 = thirds, and so on) with visible dividers between them. Tapping a
 * segment slides the rounded-corner selection surround to it
 * (RelaySprings-bouncy, music-app style).
 *
 * Segments: "All" first (always), then the user's custom categories in
 * their Settings order. No cap — when segments no longer fit at a readable
 * width the bar becomes horizontally scrollable with fixed-width segments.
 * Shown ONLY when at least one custom category exists; otherwise the inbox
 * shows no bar at all.
 *
 * Positioning is computed deterministically from the segment index (equal
 * segments = known geometry), so the sliding pill needs no measurement
 * pass and never flashes at the wrong size.
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

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(52.dp),
    ) {
        val density = LocalDensity.current
        val containerWPx = with(density) { maxWidth.toPx() }
        val insetPx = with(density) { 4.dp.toPx() }
        // Equal segments while they fit at a readable width; beyond that
        // the bar scrolls with fixed-width segments (still equal).
        val minSegPx = with(density) { 96.dp.toPx() }
        val fillMode = count * minSegPx <= containerWPx
        val segWPx = if (fillMode) {
            (containerWPx - insetPx * 2) / count
        } else {
            with(density) { 120.dp.toPx() }
        }
        val scrollState = rememberScrollState()
        // Pill geometry in container-local coords. In scroll mode the scroll
        // offset shifts it; scrollState.value is State-backed, so this stays
        // glued while scrolling.
        val targetXPx = insetPx + selectedIndex * segWPx -
            (if (fillMode) 0f else scrollState.value.toFloat())
        val pillYPx = with(density) { 4.dp.toPx() }
        val animX by animateFloatAsState(targetXPx, RelaySprings.bouncy(), label = "catPillX")
        val animW by animateFloatAsState(segWPx, RelaySprings.bouncy(), label = "catPillW")
        // Shape morph (Garrett 2026-10-19): while the surround slides it's a
        // rounded rectangle; settling onto the tapped segment it blooms into
        // the sideways oval. Lightweight: the corner radius keys off whether
        // the slide has settled (animX == target when the spring rests).
        val cornerTarget = if (animX == targetXPx) 28.dp else 10.dp
        val animCorner by animateDpAsState(cornerTarget, RelaySprings.bouncy(), label = "catPillCorner")

        // Keep the selected segment on screen in scroll mode.
        LaunchedEffect(selectedIndex, fillMode) {
            if (!fillMode) {
                val target = (insetPx + selectedIndex * segWPx - containerWPx / 2 + segWPx / 2)
                    .roundToInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target, RelaySprings.bouncy())
            }
        }

        // Sliding selection surround — drawn UNDER the labels.
        Box(
            modifier = Modifier
                .offset { IntOffset(animX.roundToInt(), pillYPx.roundToInt()) }
                .width(with(density) { animW.toDp() })
                .height(44.dp)
                .clip(RoundedCornerShape(animCorner))
                .background(MaterialTheme.colorScheme.primaryContainer),
        )

        val rowModifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
        val segWDp = with(density) { segWPx.toDp() }
        Row(
            modifier = if (fillMode) rowModifier
            else rowModifier.horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segmentIds.forEachIndexed { index, id ->
                Segment(
                    name = segmentNames[index],
                    selected = index == selectedIndex,
                    onClick = { onSelect(id) },
                    // RowScope: weight() only resolves here, not outside.
                    modifier = if (fillMode) Modifier.weight(1f)
                    else Modifier.width(segWDp),
                )
                if (index < count - 1) SegmentDivider()
            }
        }
    }
}

/** One equal segment of the bar. */
@Composable
private fun RowScope.Segment(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
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

/** Visible segmentation between categories — the "split" Garrett asked for. */
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
