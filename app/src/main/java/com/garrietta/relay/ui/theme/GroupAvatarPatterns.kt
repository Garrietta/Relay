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

/** Airiness factor (Garrett 2026-10-19): larger counts get slightly
 * smaller avatars so the cluster fills the 1:1 square without ever
 * feeling cramped — larger scale for 2, smaller as counts rise. */
private fun countScale(n: Int): Float = when {
    n <= 3 -> 1f
    n == 4 -> 0.95f
    else -> 0.9f
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
        val air = countScale(shown.size)
        when (pattern) {
            GroupPattern.SPIRAL -> spiralPlacements(shown.size)
            GroupPattern.GRID -> gridPlacements(shown.size)
            GroupPattern.ZIGZAG -> zigzagPlacements(shown.size)
        }.map { p ->
            // 1:1 BOUND RULE (Garrett 2026-10-19): the whole cluster must
            // fill exactly the square a single avatar occupies — scale for
            // airiness, then clamp every placement so no avatar spills
            // outside the box.
            val sizeFrac = p.sizeFrac * air
            val half = sizeFrac / 2f
            p.copy(
                sizeFrac = sizeFrac,
                xFrac = p.xFrac.coerceIn(half, 1f - half),
                yFrac = p.yFrac.coerceIn(half, 1f - half),
            )
        }
    }
    // Two-person chats (Garrett 2026-10-19, corrected 21:10): two FULL
    // avatar icons at 75% size, overlapped with deterministic random
    // positions/orientations per conversation — NOT a split circle.
    if (members.size == 2) {
        TwoAvatarOverlap(members[0], members[1], conversationKey, boxSize, modifier)
        return
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

/**
 * Two-person avatar layout (Garrett 2026-10-19): two FULL avatar icons,
 * each 75% of normal size, overlapped on a diagonal with deterministic
 * random positions and orientations per conversation — the default
 * 2-person chat layout. Each keeps its per-contact expressive shape,
 * photo, and initial; one deterministically overlaps the other.
 */
@Composable
private fun TwoAvatarOverlap(
    a: ClusterMember,
    b: ClusterMember,
    conversationKey: String,
    boxSize: Dp,
    modifier: Modifier = Modifier,
) {
    val h = conversationKey.hashCode()
    // Diagonal direction (↘ or ↙), deterministic per conversation.
    val flipX = (h and 1) == 1
    // Deterministic jitter ±0.02 around the diagonal slots; centers are
    // clamped to [0.375, 0.625] so with the 0.375 half-size the avatars
    // never cross the box edges.
    fun jitter(shift: Int): Float = (((h ushr shift) and 255) / 255f) * 0.04f - 0.02f
    val axC = (0.375f + jitter(1)).coerceIn(0.375f, 0.625f)
    val ayC = (0.375f + jitter(9)).coerceIn(0.375f, 0.625f)
    val bxC = (0.625f + jitter(17)).coerceIn(0.375f, 0.625f)
    val byC = (0.625f + jitter(25)).coerceIn(0.375f, 0.625f)
    val ax = if (flipX) 1f - axC else axC
    val bx = if (flipX) 1f - bxC else bxC
    val aOnTop = ((h ushr 5) and 1) == 1
    val s = boxSize * 0.75f
    val smallText = boxSize < 80.dp
    Box(modifier = modifier.size(boxSize)) {
        val rotA = remember(a.address) { memberRotation(a.address) }
        val rotB = remember(b.address) { memberRotation(b.address) }
        ContactAvatar(
            photoUri = a.photoUri,
            name = a.name,
            size = s,
            shapeKey = a.address,
            textStyle = if (smallText) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .offset(x = boxSize * ax - s / 2, y = boxSize * ayC - s / 2)
                .graphicsLayer { rotationZ = rotA }
                .zIndex(if (aOnTop) 2f else 1f),
        )
        ContactAvatar(
            photoUri = b.photoUri,
            name = b.name,
            size = s,
            shapeKey = b.address,
            textStyle = if (smallText) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .offset(x = boxSize * bx - s / 2, y = boxSize * byC - s / 2)
                .graphicsLayer { rotationZ = rotB }
                .zIndex(if (aOnTop) 1f else 2f),
        )
    }
}
