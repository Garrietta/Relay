package com.garrietta.relay.ui.inbox

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.ui.motion.RelaySprings
import kotlin.math.roundToInt

/**
 * The inbox category bar — Garrett's 2026-10-19 directive, modeled on the
 * YouTube Music top pill bar (see his screen recording): there is NO
 * background container pill. Each category is its OWN pill chip, and the
 * chips themselves form the bar. Unselected chips are small-radius rounded
 * rectangles; the selected chip blooms into a filled sideways oval.
 *
 * Tapping a chip morphs THAT chip instantly — no sliding surround, no
 * delay. The shape morph fires the frame the tap lands (RelaySprings
 * bouncy), which is what Garrett asked for after the #117 build showed a
 * visible lag in the old sliding-surround transform.
 *
 * Segments: "All" first (always), then the user's custom categories in
 * their Settings order. Equal widths while they fit (2 = halves, 3 =
 * thirds, ...); beyond a readable width the bar scrolls with fixed-width
 * chips. Shown ONLY when at least one custom category exists.
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
        val gapPx = with(density) { 8.dp.toPx() }
        // Equal chips while they fit at a readable width; beyond that the
        // bar scrolls with fixed-width chips (still equal).
        val minChipPx = with(density) { 96.dp.toPx() }
        val fillMode = count * minChipPx + (count - 1) * gapPx <= containerWPx
        val scrollState = rememberScrollState()

        // Keep the selected chip on screen in scroll mode.
        LaunchedEffect(selectedIndex, fillMode) {
            if (!fillMode) {
                val chipPx = with(density) { 120.dp.toPx() } + gapPx
                val target = (selectedIndex * chipPx - containerWPx / 2 + chipPx / 2)
                    .roundToInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target, RelaySprings.bouncy())
            }
        }

        Row(
            modifier = if (fillMode) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            segmentIds.forEachIndexed { index, id ->
                CategoryChip(
                    name = segmentNames[index],
                    selected = index == selectedIndex,
                    onClick = { onSelect(id) },
                    // RowScope: weight() only resolves here, not outside.
                    modifier = if (fillMode) Modifier.weight(1f).fillMaxHeight()
                    else Modifier.width(120.dp).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * One category chip. The selection morph lives ON the chip: rounded
 * rectangle (10dp) -> sideways oval (26dp = stadium on the 52dp bar),
 * unfilled -> filled, and it fires instantly on tap with the bouncy
 * spring — no intermediate sliding pill, so there is no transform delay.
 */
@Composable
private fun RowScope.CategoryChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner by animateDpAsState(
        targetValue = if (selected) 26.dp else 10.dp,
        animationSpec = RelaySprings.bouncy(),
        label = "chipCorner",
    )
    // Color snaps fast (tween): a bouncy spring overshoots through
    // out-of-gamut intermediates on color, which reads as a flash.
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(180),
        label = "chipBg",
    )
    // Tactile press scale in the unified spring language.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "chipPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(corner))
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
