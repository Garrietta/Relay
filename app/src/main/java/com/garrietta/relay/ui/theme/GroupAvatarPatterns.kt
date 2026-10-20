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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.abs
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
 * AREA RULE (Garrett 2026-10-19, 23:16, replacing the envelope-normalization
 * rule): the cluster's icons together take up roughly the SAME AREA as one
 * single-contact shape — the 3-person conglomerate should read like the
 * Monroe Health icon in size. Per-icon scale = 1/√N (N icons × (1/√N)² =
 * 1.0 single-shape area), and the hex spacing is re-derived per count so
 * the arrangement FITS that scale inside the 1:1 box: the overlap gets
 * tighter as N shrinks (3 = tight triangle, 6+ = airier hex).
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
 * Per-icon scale for N cluster avatars in the 1:1 box — the AREA RULE
 * (Garrett 2026-10-19): N icons at (1/√N)² summed area = 1.0 — the same
 * area as one single-contact shape takes in the same box. N=3 → 0.577,
 * N=4 → 0.50, N=5 → 0.447, N=6 → 0.408, N=9 → 0.333.
 */
private fun baseScale(n: Int): Float =
    1f / sqrt(n.toFloat().coerceAtLeast(1f))

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
 * Envelope cap for the cluster at EVERY member count. The hex spacing is
 * derived backwards from it: d = (CAP/2 − s/2) / e1, where s is the
 * area-rule scale and e1 the unit-spacing slot extent — so the bigger
 * area-sized icons never exceed the box while the tight hex overlap
 * keeps the conglomerate filling it (small N = tight triangle, large N
 * = airier hex). 0.96 leaves headroom for the ±0.025 position jitter
 * (worst case envelope ≈ 1.01, still inside); the per-icon clamp is the
 * final safety.
 */
private const val TARGET_CLUSTER_FILL = 0.96f

/**
 * V3 placements for a cluster of [n] members: hex-ring slots at a
 * count-derived spacing, per-member position jitter, per-member scale
 * jitter, all clamped into the 1:1 box.
 *
 * AREA RULE (Garrett 2026-10-19 23:16): each icon's scale is 1/√N, so N
 * icons sum to the area of one single-contact shape — the conglomerate
 * reads at the same size as a Monroe Health row icon at every count.
 * The old envelope normalization shrank icons to ≈0.43 at N=3–7 (summed
 * area 0.55–0.75 — visibly tiny clusters with dead space). Spacing is now
 * re-derived from the area scale: with the unit-spacing slot extent e1
 * measured from [hexSlots], d = (CAP/2 − s/2) / e1 puts the cluster's
 * outer envelope exactly at [TARGET_CLUSTER_FILL] while the OVERLAP
 * absorbs the area scale — N=3 overlaps ≈65% (tight triangle), N=6
 * ≈30%, N=7 ≈20% (airy hex), the adaptation Garrett asked for. The
 * per-icon clamp remains the hard no-overflow safety.
 */
private fun clusterPlacements(
    members: List<ClusterMember>,
    conversationKey: String,
): List<Placement> {
    val n = members.size
    if (n == 0) return emptyList()
    val size = baseScale(n)
    // Unit-spacing slot extent: the farthest slot offset from the box
    // center (either axis) when the hex spacing is 1 — exact, because
    // slot offsets scale linearly with spacing. Divide the available
    // half-box (envelope cap minus this icon's radius) by it to get the
    // spacing that both fits the area-sized icons and fills the box.
    val unitExtent = hexSlots(n, 1f).maxOf { (sx, sy, _) ->
        maxOf(abs(sx - 0.5f), abs(sy - 0.5f))
    }
    val spacing = if (unitExtent > 0f) {
        ((TARGET_CLUSTER_FILL / 2f - size / 2f) / unitExtent).coerceAtLeast(0.05f)
    } else {
        0f
    }
    val slots = hexSlots(n, spacing)
    val maxRing = slots.maxOf { it.third }
    return members.mapIndexed { i, member ->
        val (sx, sy, ring) = slots[i]
        val memberSize = memberScale(conversationKey, member.address, size)
        val jx = (jitter01("$conversationKey|${member.address}|x") - 0.5f) * 0.05f
        val jy = (jitter01("$conversationKey|${member.address}|y") - 0.5f) * 0.05f
        val half = memberSize / 2f
        Placement(
            xFrac = (sx + jx).coerceIn(half, 1f - half),
            yFrac = (sy + jy).coerceIn(half, 1f - half),
            sizeFrac = memberSize,
            // Inner rings draw on top of outer rings (center highest).
            zIndex = (maxRing - ring).toFloat(),
        )
    }
}

/**
 * Initial-letter style for cluster members (Garrett 2026-10-19): the
 * letter scales WITH the member's rendered size AND keeps the single
 * avatar's style — headlineMedium with only its font size scaled, so
 * weight and family stay identical (bold stays bold). The old fixed
 * labelMedium/titleSmall styles rendered the letters tiny inside
 * scaled-down cluster shapes. Ratio = headlineMedium's 28sp at
 * ContactAvatar's 56dp default size = 0.5 × size.
 */
@Composable
private fun clusterLetterStyle(size: Dp): TextStyle =
    MaterialTheme.typography.headlineMedium.copy(fontSize = (size.value * 0.5f).sp)

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
                textStyle = clusterLetterStyle(s),
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
    Box(modifier = modifier.size(boxSize)) {
        val rotA = remember(a.address) { memberRotationOld(a.address) }
        val rotB = remember(b.address) { memberRotationOld(b.address) }
        ContactAvatar(
            photoUri = a.photoUri,
            name = a.name,
            size = s,
            shapeKey = a.address,
            textStyle = clusterLetterStyle(s),
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
            textStyle = clusterLetterStyle(s),
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
