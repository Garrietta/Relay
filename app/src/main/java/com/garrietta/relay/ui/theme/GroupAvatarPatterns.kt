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
 *
 * MINIMUM-SPACING RULE (Garrett 2026-10-20, 07:52, amending the hex
 * packing for counts 3–6): at the area-rule scale the hex packing put a
 * member in the CENTER and ring members only ≈0.34 icon-widths from it,
 * so the middle photo/letter was buried — in the 4-person "Haley,
 * Kelly, Kevin" chat Haley's photo covered Kevin and Kelly almost
 * entirely. For 3–6 shown members the cluster now uses spread
 * templates (triangle / corners / ring / staggered brick — never a
 * center member) plus a relaxation pass that GUARANTEES a minimum
 * center-to-center distance of 0.65 × the icon size between every
 * pair: every member's center point — where the letter sits and the
 * face of the photo lives — stays outside every other member's shape
 * (graphics-shapes polygons are inscribed at radius = size/2, so
 * 0.65 × size clears the shape edge with margin for the ±3% scale
 * jitter and ±11° rotations). Counts 7+ keep the dense hex stacking —
 * past six, the box is too small for the floor and members are simply
 * thrown on in the deterministic hex order, per Garrett.
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
 * V3 placements via hex-ring slots at a count-derived spacing,
 * per-member position jitter, per-member scale jitter, all clamped
 * into the 1:1 box. This is the path for counts 7+ (dense stacking,
 * per Garrett's spacing rule) and 1–2; counts 3–6 dispatch to
 * [spacedPlacements] before reaching here.
 *
 * AREA RULE (Garrett 2026-10-19 23:16): each icon's scale is 1/√N, so N
 * icons sum to the area of one single-contact shape — the conglomerate
 * reads at the same size as a Monroe Health row icon at every count.
 * The old envelope normalization shrank icons to ≈0.43 at N=3–7 (summed
 * area 0.55–0.75 — visibly tiny clusters with dead space). Spacing is
 * re-derived from the area scale: with the unit-spacing slot extent e1
 * measured from [hexSlots], d = (CAP/2 − s/2) / e1 puts the cluster's
 * outer envelope exactly at [TARGET_CLUSTER_FILL] while the OVERLAP
 * absorbs the area scale. The per-icon clamp remains the hard
 * no-overflow safety.
 */
private fun clusterPlacements(
    members: List<ClusterMember>,
    conversationKey: String,
): List<Placement> {
    val n = members.size
    if (n == 0) return emptyList()
    if (n in 3..6) return spacedPlacements(members, conversationKey)
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
 * Minimum center-to-center distance between cluster members, as a
 * fraction of the area-rule icon size — Garrett's minimum-spacing rule
 * (2026-10-20): "the outside edge must be so far from the center of
 * another icon… there's enough space between the center to where you
 * can still identify who is who." Derivation: the expressive shapes
 * are graphics-shapes polygons inscribed in a circle of radius
 * size/2, so a neighbor's shape edge reaches at most 0.5 × size from
 * its center; 0.65 × size keeps every member's CENTER POINT (the
 * letter, the face) clear of every other shape with 0.15 × size of
 * margin for the ±3% scale jitter, rotations, and position jitter.
 */
private const val MIN_CENTER_FRACTION = 0.65f

/**
 * Spread templates for 3–6 members, as offsets from the box center in
 * units of the center half-range c = CAP/2 − size/2 — so a template
 * icon resting at a template extreme exactly touches the 0.96
 * envelope. No template has a center member (the hex packing's buried
 * middle icon is what Garrett flagged). Verified minimum pairwise
 * distances (nominal, fraction of icon size): 3 → 0.663 (point-up
 * triangle: two bottom corners + top center; the bottom pair binds),
 * 4 → 0.920 (four corners), 5 → 0.674 (pentagon ring, vertex up),
 * 6 → 0.812 (staggered brick: columns at −c/0/+c with the middle
 * column offset by a third — the max-min arrangement for six in a
 * square, and the app's hex language fitted to the box).
 */
private fun spacedTemplate(n: Int): List<Pair<Float, Float>> = when (n) {
    3 -> listOf(
        0f to -1f, -1f to 1f, 1f to 1f,
    )
    4 -> listOf(
        -1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f,
    )
    5 -> (0 until 5).map { k ->
        val a = ((-90 + 72 * k) * PI / 180f).toFloat()
        cos(a) to sin(a)
    }
    6 -> listOf(
        -1f to -1f, -1f to (1f / 3f),
        0f to (-1f / 3f), 0f to 1f,
        1f to -1f, 1f to (1f / 3f),
    )
    else -> emptyList()
}

/**
 * Placements for 3–6 members under the minimum-spacing rule: the
 * spread template for the count, the usual per-member scale and
 * position jitter, then a deterministic relaxation that enforces
 * [MIN_CENTER_FRACTION] between every pair BY CONSTRUCTION — any pair
 * the jitter pulled too close is pushed apart along its own axis,
 * with the per-icon box clamp re-applied after every pass. Verified
 * offline against 20,000 random jitter draws per count: the floor
 * held in every draw, every member's center point stayed outside
 * every other member's shape, and the clamped envelope never left
 * the 1:1 box (nominal envelope 0.96, exactly as the area rule's).
 * Summed icon area is the area rule's 1.000 (nominal) — unchanged.
 */
private fun spacedPlacements(
    members: List<ClusterMember>,
    conversationKey: String,
): List<Placement> {
    val n = members.size
    val size = baseScale(n)
    val c = TARGET_CLUSTER_FILL / 2f - size / 2f
    val sizes = members.map { memberScale(conversationKey, it.address, size) }
    val template = spacedTemplate(n)
    val xs = FloatArray(n) { i ->
        0.5f + template[i].first * c +
            (jitter01("$conversationKey|${members[i].address}|x") - 0.5f) * 0.05f
    }
    val ys = FloatArray(n) { i ->
        0.5f + template[i].second * c +
            (jitter01("$conversationKey|${members[i].address}|y") - 0.5f) * 0.05f
    }
    val floor = MIN_CENTER_FRACTION * size
    repeat(64) {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = xs[j] - xs[i]
                var dy = ys[j] - ys[i]
                var d = sqrt(dx * dx + dy * dy)
                if (d < floor) {
                    if (d < 1e-4f) {
                        // Coincident centers: separate along a
                        // deterministic per-pair direction.
                        val ang = ((i * 37 + j * 91) * PI / 180f).toFloat()
                        dx = cos(ang)
                        dy = sin(ang)
                        d = 1f
                    }
                    val push = (floor - d) / 2f
                    val ux = dx / d
                    val uy = dy / d
                    xs[i] -= ux * push
                    ys[i] -= uy * push
                    xs[j] += ux * push
                    ys[j] += uy * push
                }
            }
        }
        for (i in 0 until n) {
            val half = sizes[i] / 2f
            xs[i] = xs[i].coerceIn(half, 1f - half)
            ys[i] = ys[i].coerceIn(half, 1f - half)
        }
    }
    return members.mapIndexed { i, _ ->
        Placement(
            xFrac = xs[i],
            yFrac = ys[i],
            sizeFrac = sizes[i],
            // No rings here: earlier members draw on top, as the hex
            // path's center member did.
            zIndex = (n - i).toFloat(),
        )
    }
}

/**
 * Where the "+N" badge goes: whichever candidate spot — the four
 * inset corners or the box center — is FARTHEST from the nearest
 * shown member's center, so the badge can never sit on top of a
 * member's center point (Garrett's identifiability rule applies to
 * the badge too; the old fixed bottom-right spot buried the
 * bottom-right member of the ring layouts). Scanned in TL, TR, BL,
 * center, BR order with >=, so exact ties keep the familiar
 * bottom-right. Verified offline: across 20,000 jitter draws per
 * count 3–6 the nearest member center was always beyond the badge's
 * radius.
 */
private fun badgeCenter(placements: List<Placement>): Pair<Float, Float> {
    val inset = 0.335f
    val candidates = listOf(
        (0.5f - inset) to (0.5f - inset),
        (0.5f + inset) to (0.5f - inset),
        (0.5f - inset) to (0.5f + inset),
        0.5f to 0.5f,
        (0.5f + inset) to (0.5f + inset),
    )
    var best = candidates.last()
    var bestScore = -1f
    for (candidate in candidates) {
        val score = placements.minOf { p ->
            val dx = candidate.first - p.xFrac
            val dy = candidate.second - p.yFrac
            sqrt(dx * dx + dy * dy)
        }
        if (score >= bestScore) {
            bestScore = score
            best = candidate
        }
    }
    return best
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
    // Photo-less palette collisions inside this one cluster (Garrett
    // 2026-10-20): if two shown members' assigned palettes collide
    // exactly, the later member nudges to the next palette so the
    // cluster stays readable. Solo surfaces always use the raw
    // assignment — the nudge exists only here, where members share
    // one box.
    val paletteIndexes = remember(shown.map { it.address }) {
        ContactPalettes.paletteIndexesForCluster(shown.map { it.address })
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
                paletteIndex = paletteIndexes[i],
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
            val ns = boxSize * 0.33f
            val (badgeX, badgeY) = badgeCenter(placements)
            Box(
                modifier = Modifier
                    .offset(
                        x = boxSize * badgeX - ns / 2,
                        y = boxSize * badgeY - ns / 2,
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
    // Same cluster palette rule as the hex cluster: an exact palette
    // collision between the two members nudges the second one.
    val paletteIndexes = remember(a.address, b.address) {
        ContactPalettes.paletteIndexesForCluster(listOf(a.address, b.address))
    }
    Box(modifier = modifier.size(boxSize)) {
        val rotA = remember(a.address) { memberRotationOld(a.address) }
        val rotB = remember(b.address) { memberRotationOld(b.address) }
        ContactAvatar(
            photoUri = a.photoUri,
            name = a.name,
            size = s,
            shapeKey = a.address,
            paletteIndex = paletteIndexes[0],
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
            paletteIndex = paletteIndexes[1],
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
