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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.ui.motion.RelaySprings
import kotlin.math.roundToInt

/**
 * The inbox category bar — Garrett 2026-10-19 21:45 directive (supersedes
 * the #125/#127 squeeze-to-fit rule): SEPARATE chips with spacing between
 * them, and EVERY chip is IDENTICAL in size at ALL times. Selection never
 * changes a chip's width — the selected state is SHAPE + COLOR ONLY,
 * morphing within the same fixed slot. Adding a category INSERTS a new
 * same-size chip in order and pushes later chips (Unknown always last)
 * right; when the chips exceed the bar's width the bar SCROLLS
 * horizontally — no squeezing, no compact text, no per-chip width changes,
 * ever.
 *
 * Geometry kept: 40dp height, 8dp gaps, 10dp middle radius (0.25h measured
 * on his music-app screenshots), end chips (All first, Unknown last)
 * ALWAYS half-oval (20dp = h/2) outward with the 10dp radius on the inward
 * edge, selected morphs to the full 20dp stadium. Corner radii are clamped
 * >= 0 against the bouncy spring's overshoot (the #122 crash).
 *
 * Segments: "All" first (always), then the user's custom categories in
 * their Settings order, then the built-in Unknown tab last.
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

    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    // Keep the selected chip on screen: scroll so the selected chip's slot
    // starts at the leading edge (clamped to the scroll range).
    LaunchedEffect(selectedIndex, count) {
        val chipPx = with(density) { (ChipWidth + ChipGap).toPx() }
        val target = (selectedIndex * chipPx).roundToInt().coerceAtLeast(0)
        scrollState.animateScrollTo(
            target.coerceAtMost(scrollState.maxValue),
            RelaySprings.bouncy(),
        )
    }

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(40.dp)
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
    ) {
        segmentIds.forEachIndexed { index, id ->
            ChocolateSegment(
                name = segmentNames[index],
                selected = index == selectedIndex,
                isFirst = index == 0,
                isLast = index == count - 1,
                onClick = { onSelect(id) },
                modifier = Modifier
                    .width(ChipWidth)
                    .fillMaxHeight(),
            )
        }
    }
}

/** Fixed chip geometry: one width for every chip, forever. */
private val ChipWidth = 96.dp
private val ChipGap = 8.dp

/**
 * One chocolate-bar segment. Unselected middles are rounded rectangles at
 * the reference radius (10dp); unselected end caps are half-oval outward
 * (20dp) with 10dp on the inward edge. Selection morphs to the full 20dp
 * stadium and fills with the dynamic accent — SHAPE + COLOR ONLY, no
 * scale, no width change: the chip is pixel-identical in size whether
 * selected or not.
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
    // its tiled value to the full 20dp oval. Clamped so the spring's
    // overshoot can never drive a corner radius negative (the #122 crash).
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = RelaySprings.bouncy(),
        label = "chocSel",
    )
    val selC = sel.coerceIn(0f, 1f)
    fun corner(outwardRounded: Boolean): androidx.compose.ui.unit.Dp {
        val base = if (outwardRounded) 20f else 10f
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
    // Tactile press feedback only — a shrink, never a grow, so it can never
    // overlap a neighbor. Selection itself does not scale.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "chocPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
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
            // IDENTICAL typography for every state (Garrett 2026-10-19
            // 21:45): only color distinguishes the selected chip, so the
            // text metrics — and therefore the chip — can never differ.
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
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
