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
 * Group avatar cluster layout V3 (Garrett 2026-10-19, 22:23): group chats
 * get overlapping icons of each contact photo — the SAME treatment as the
 * 2-person overlap — arranged on a hexagonal packing that tightens as the
 * chat grows: one center avatar, then hexagonal rings around it (3 =
 * triangle-ish, 4–7 = a hex cluster, then outer rings).
 *
 * - Base scale = f(member count): min(0.75, 0.9/√N) — the scale that fits
 *   N overlapping icons inside the 1:1 box a single 1:1 avatar occupies
 *   (his calibration: 6 people ≈ 35%).
 * - Per-contact jitter: ±3% of the base scale, deterministic per
 *   (conversation, contact), for uniqueness.
 * - Random positions AND rotations, deterministic per (conversation,
 *   contact) — same layout every render, on every surface.
 * - Bounding box always = one 1:1 avatar box; avatars clamp inside it.
 * - Max [maxShown] photos; beyond that a "+N" badge.
 */

/** One member of a group avatar cluster. */
data class ClusterMember(
    val photoUri: String?,
    val name: String,
    val address: String,
)

/** Placement of one avatar: center as box fractions, size as box fraction. */
private data class Placement(val xFrac: Float, val yFrac: Float, val sizeFrac: Float, val zIndex: Float)

/**
 * Base scale for N overlapping avatars in the 1:1 box — derived from the
 * member count, never a per-N guess table: min(0.75, 0.9/√N). N=3 → 0.52,
 * N=4 → 0.45, N=5 → 0.40, N=6 → 0.37 (≈ his 35%), N=8 → 0.32.
 */
private fun baseScale(n: Int): Float =
    minOf(0.75f, 0.9f / sqrt(n.toFloat().coerceAtLeast(1f)))

/** Deterministic pseudo-random in [0, 1) from a string seed. */
private fun jitter01(seed: String): Float =
    ((seed.hashCode() and Int.MAX_VALUE) % 10_000) / 10_000f

/** Per-contact scale: base ±3%, deterministic per (conversation, contact). */
private fun memberScale(conversationKey: String, address: String, base: Float): Float =
    base * (1f + (jitter01("$conversationKey|$address|scale") - 0.5f) * 0.06f)

/** Deterministic slight rotation per member (±11°) — the scattered feel. */
private fun memberRotation(conversationKey: String, address: String): Float =
    (jitter01("$conversationKey|$address|rot") - 0.5f) * 22f

/**
 * Hexagonal-packing slot centers for the first [n] avatars: slot 0 is the
 * center; ring k holds 6k slots at radius k × [spacing], each ring rotated
 * a few degrees for the organic feel. Returned (x, y, ring).
 */
private fun hexSlots(n: Int, spacing: Float): List<Triple<Float, Float, Int>> {
    val slots = mutableListOf(Triple(0.5f, 0.5f, 0))
    var ring = 1
    while (slots.size < n) {
        val count = 6 * ring
        val r = spacing * ring
        val offsetDeg = ring * 15f
        for (j in 0 until count) {
            if (slots.size >= n) break
            val a = ((offsetDeg + j * (360f / count)) * PI / 180f).toFloat()
            slots += Triple(0.5f + r * cos(a), 0.5f + r * sin(a) * 0.94f, ring)
        }
        ring++
    }
    return slots
}

/**
 * V3 placements for a cluster of [n] members: hex-ring slots with a
 * loose ~38% overlap (center distance = 0.62 × base scale), per-member
 * position jitter, per-member scale jitter, all clamped into the 1:1 box.
 */
private fun clusterPlacements(
    members: List<ClusterMember>,
    conversationKey: String,
): List<Placement> {
    val n = members.size
    if (n == 0) return emptyList()
    val base = baseScale(n)
    val slots = hexSlots(n, base * 0.62f)
    val maxRing = slots.maxOf { it.third }
    return members.mapIndexed { i, member ->
        val (sx, sy, ring) = slots[i]
        val size = memberScale(conversationKey, member.address, base)
        val jx = (jitter01("$conversationKey|${member.address}|x") - 0.5f) * 0.05f
        val jy = (jitter01("$conversationKey|${member.address}|y") - 0.5f) * 0.05f
        val half = size / 2f
        Placement(
            xFrac = (sx + jx).coerceIn(half, 1f - half),
            yFrac = (sy + jy).coerceIn(half, 1f - half),
            sizeFrac = size,
            // Inner rings draw on top of outer rings (center highest).
            zIndex = (maxRing - ring).toFloat(),
        )
    }
}

/**
 * A group's contact photos in the V3 hex cluster: each keeps its
 * per-contact expressive shape, photo and initial; the whole cluster
 * fills exactly the [boxSize] square a single avatar occupies.
 */
@Composable
fun GroupAvatarCluster(
    members: List<ClusterMember>,
    conversationKey: String,
    boxSize: Dp,
    maxShown: Int = 5,
    modifier: Modifier = Modifier,
) {
    val shown = members.take(maxShown)
    val remaining = members.size - shown.size
    // Two-person chats (Garrett 2026-10-19, corrected 21:10): two FULL
    // avatar icons at 75% size, overlapped with deterministic random
    // positions/orientations per conversation — NOT a split circle.
    if (members.size == 2) {
        TwoAvatarOverlap(members[0], members[1], conversationKey, boxSize, modifier)
        return
    }
    val placements = remember(conversationKey, shown.size, shown.map { it.address }) {
        clusterPlacements(shown, conversationKey)
    }
    val smallText = boxSize < 80.dp
    Box(modifier = modifier.size(boxSize)) {
        shown.forEachIndexed { i, member ->
            val p = placements[i]
            val s = boxSize * p.sizeFrac
            val rot = remember(conversationKey, member.address) {
                memberRotation(conversationKey, member.address)
            }
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
                    .zIndex(p.zIndex),
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
    // clamped to [0.36, 0.64] (Garrett 2026-10-19 21:37: LOOSER overlap for
    // 2-person chats — a wider diagonal spread than the n>=3 clusters) so
    // with the 0.375 half-size the avatars never cross the box edges.
    fun jitter(shift: Int): Float = (((h ushr shift) and 255) / 255f) * 0.04f - 0.02f
    val axC = (0.36f + jitter(1)).coerceIn(0.36f, 0.64f)
    val ayC = (0.36f + jitter(9)).coerceIn(0.36f, 0.64f)
    val bxC = (0.64f + jitter(17)).coerceIn(0.36f, 0.64f)
    val byC = (0.64f + jitter(25)).coerceIn(0.36f, 0.64f)
    val ax = if (flipX) 1f - axC else axC
    val bx = if (flipX) 1f - bxC else bxC
    val aOnTop = ((h ushr 5) and 1) == 1
    val s = boxSize * 0.75f
    val smallText = boxSize < 80.dp
    Box(modifier = modifier.size(boxSize)) {
        val rotA = remember(a.address) { memberRotationOld(a.address) }
        val rotB = remember(b.address) { memberRotationOld(b.address) }
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

/** Rotation for the 2-person layout: keyed on the contact only (unchanged). */
private fun memberRotationOld(address: String): Float {
    val h = address.hashCode() and 1023
    return (h / 1023f - 0.5f) * 22f
}
