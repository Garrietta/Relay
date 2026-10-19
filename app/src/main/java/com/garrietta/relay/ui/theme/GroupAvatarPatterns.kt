package com.garrietta.relay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-group avatar cluster pattern. Each group chat gets ONE pattern,
 * assigned deterministically from its conversation id, so every group is
 * visually distinct and stable within a session: one group's collage is a
 * spiral, another's a grid, another's a zigzag.
 */
enum class GroupPattern { SPIRAL, GRID, ZIGZAG }

/** Deterministic pattern pick — same conversation, same pattern, every time. */
fun groupPatternFor(conversationKey: String): GroupPattern {
    val values = GroupPattern.entries
    return values[(conversationKey.hashCode() and Int.MAX_VALUE) % values.size]
}

/** One member of a group avatar cluster. */
data class ClusterMember(
    val photoUri: String?,
    val name: String,
    val address: String,
)

/** Placement of one avatar: center as box fractions, size as box fraction. */
private data class Placement(val xFrac: Float, val yFrac: Float, val sizeFrac: Float)

/** Golden-angle spiral: first member large at center, rest orbit outward. */
private fun spiralPlacements(n: Int): List<Placement> {
    val golden = (137.5f * PI / 180f).toFloat()
    return List(n) { i ->
        if (i == 0) {
            Placement(0.5f, 0.5f, 0.54f)
        } else {
            val r = 0.24f * sqrt(i.toFloat())
            val a = i * golden
            val x = (0.5f + r * cos(a)).coerceIn(0.17f, 0.83f)
            val y = (0.5f + r * sin(a) * 0.92f).coerceIn(0.17f, 0.83f)
            Placement(x, y, (0.54f - i * 0.06f).coerceAtLeast(0.26f))
        }
    }
}

/** Compact 2-column grid with a slight per-cell size rhythm; 5th overlaps center. */
private fun gridPlacements(n: Int): List<Placement> {
    val cells = listOf(
        Pair(0.27f, 0.27f),
        Pair(0.73f, 0.27f),
        Pair(0.27f, 0.73f),
        Pair(0.73f, 0.73f),
        Pair(0.5f, 0.5f),
    )
    return List(n) { i ->
        val (x, y) = cells[i]
        // Alternate the cell sizes slightly for the scattered feel.
        val s = if (i % 2 == 0) 0.44f else 0.38f
        Placement(x, y, if (i == 4) 0.42f else s)
    }
}

/** Zigzag: alternating left/right offsets stepping down, overlapping. */
private fun zigzagPlacements(n: Int): List<Placement> =
    List(n) { i ->
        val x = if (i % 2 == 0) 0.33f else 0.67f
        val y = if (n == 1) 0.5f else 0.19f + i * (0.62f / (n - 1))
        Placement(x, y, 0.42f - i * 0.015f)
    }

/** Deterministic slight rotation per member (±11°) — the scattered feel. */
private fun memberRotation(address: String): Float {
    val h = address.hashCode() and 1023
    return (h / 1023f - 0.5f) * 22f
}

/**
 * A group's contact photos arranged in that group's unique pattern
 * (spiral / grid / zigzag): different sizes, different per-contact
 * expressive shapes, slight rotations, overlapping — fitted into a compact
 * [boxSize] square. Max [maxShown] photos; beyond that a "+N" circle tucked
 * at the cluster's bottom-right. Every avatar shape obeys the 1:1-square
 * rule, so sizing stays consistent.
 */
@Composable
fun GroupAvatarCluster(
    members: List<ClusterMember>,
    conversationKey: String,
    boxSize: Dp,
    maxShown: Int = 5,
    modifier: Modifier = Modifier,
) {
    val pattern = remember(conversationKey) { groupPatternFor(conversationKey) }
    val shown = members.take(maxShown)
    val remaining = members.size - shown.size
    val placements = remember(pattern, shown.size) {
        when (pattern) {
            GroupPattern.SPIRAL -> spiralPlacements(shown.size)
            GroupPattern.GRID -> gridPlacements(shown.size)
            GroupPattern.ZIGZAG -> zigzagPlacements(shown.size)
        }
    }
    val smallText = boxSize < 80.dp
    Box(modifier = modifier.size(boxSize)) {
        shown.forEachIndexed { i, member ->
            val p = placements[i]
            val s = boxSize * p.sizeFrac
            val rot = remember(member.address) { memberRotation(member.address) }
            ContactAvatar(
                photoUri = member.photoUri,
                name = member.name,
                size = s,
                shapeKey = member.address,
                textStyle = if (smallText) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .offset(
                        x = boxSize * p.xFrac - s / 2,
                        y = boxSize * p.yFrac - s / 2,
                    )
                    .graphicsLayer { rotationZ = rot }
                    .zIndex(i.toFloat()),
            )
        }
        if (remaining > 0) {
            val ns = boxSize * 0.36f
            Box(
                modifier = Modifier
                    .offset(
                        x = boxSize * 0.78f - ns / 2,
                        y = boxSize * 0.80f - ns / 2,
                    )
                    .size(ns)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .zIndex(10f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$remaining",
                    style = if (smallText) MaterialTheme.typography.labelSmall
                        else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
