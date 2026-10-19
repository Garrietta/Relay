package com.garrietta.relay.ui.inbox

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.ui.motion.RelaySprings

/**
 * The inbox category bar — Garrett's 2026-10-19 directive, modeled on the
 * music app's top pill bar (see his screen recording): one long continuous
 * stadium container, horizontally scrollable, with the selection as a
 * filled pill that SLIDES behind the tapped segment (Koda-style slider,
 * RelaySprings-bouncy).
 *
 * Segments: "All" first (always), then the user's custom categories in
 * their Settings order. No cap — horizontal scroll handles overflow.
 * Shown ONLY when at least one custom category exists; otherwise the inbox
 * shows no bar at all.
 *
 * Positioning uses ROOT coordinates for both the container and the
 * segments, so the sliding pill stays glued to the selected segment even
 * while the bar is scrolled (parent-relative coordinates don't account
 * for scroll offset).
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
    // Segment ids: null = All, then each category id.
    val segmentIds: List<String?> = remember(categories) {
        listOf(null) + categories.map { it.id }
    }
    val segmentNames: List<String> = remember(categories) {
        listOf("All") + categories.map { it.name }
    }
    val selectedIndex = segmentIds.indexOf(selectedId).takeIf { it >= 0 } ?: 0

    // Selected segment bounds in ROOT coordinates; re-measured on scroll.
    val segBounds = remember { mutableStateMapOf<Int, FloatArray>() }
    var boxX by remember { mutableStateOf(0f) }
    var boxY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val b = segBounds[selectedIndex]
    val pillX: Dp
    val pillY: Dp
    val pillW: Dp
    val pillH: Dp
    with(density) {
        pillX = ((b?.get(0) ?: 0f) - boxX).toDp()
        pillY = ((b?.get(1) ?: 0f) - boxY).toDp()
        pillW = (b?.get(2) ?: 0f).toDp()
        pillH = (b?.get(3) ?: 0f).toDp()
    }
    val animX: Dp by animateDpAsState(pillX, RelaySprings.bouncy(), label = "catPillX")
    val animY: Dp by animateDpAsState(pillY, RelaySprings.bouncy(), label = "catPillY")
    val animW: Dp by animateDpAsState(pillW, RelaySprings.bouncy(), label = "catPillW")
    val animH: Dp by animateDpAsState(pillH, RelaySprings.bouncy(), label = "catPillH")

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onGloballyPositioned {
                boxX = it.positionInRoot().x
                boxY = it.positionInRoot().y
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(52.dp),
    ) {
        // Sliding selection pill — drawn UNDER the labels.
        if (pillW > 0.dp && pillH > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = animX, y = animY)
                    .width(animW)
                    .height(animH)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segmentIds.forEachIndexed { index, id ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val p = coords.positionInRoot()
                            segBounds[index] = floatArrayOf(
                                p.x, p.y,
                                coords.size.width.toFloat(),
                                coords.size.height.toFloat(),
                            )
                        }
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(id) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = segmentNames[index],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
