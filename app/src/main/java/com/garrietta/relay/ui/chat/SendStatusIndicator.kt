package com.garrietta.relay.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.toPath
import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator

/**
 * What an outgoing message's send indicator shows (pure mapping —
 * [SendIndicatorLogic]).
 */
enum class SendIndicatorState { HIDDEN, SPINNER, CHECK, READ }

/**
 * Send-status → indicator mapping (Garrett 2026-10-20): the
 * seven-shape sending animation he asked to have back — moved off
 * the composer button (#165 removed it from there for lag) and placed
 * beside the message it belongs to — shows while a send is in flight
 * and MORPHS into a check mark when the message is DELIVERED (his
 * word: completed = delivered). Pure JVM; unit-tested
 * (SendIndicatorLogicTest).
 */
object SendIndicatorLogic {

    fun stateFor(
        isOutgoing: Boolean,
        status: DeliveryStatus,
        uploadInProgress: Boolean,
        sendInFlight: Boolean,
    ): SendIndicatorState {
        if (!isOutgoing) return SendIndicatorState.HIDDEN
        // Media sends: the bubble's own upload scrim owns the upload
        // phase; the indicator covers the delivery phase after it.
        if (uploadInProgress) return SendIndicatorState.HIDDEN
        return when (status) {
            DeliveryStatus.NONE -> SendIndicatorState.HIDDEN
            // The spinner means "this send is happening right now" —
            // it is licensed by live-send provenance
            // (Message.sendInFlight), never by the status value alone.
            // Only the ViewModel's own send pipeline ever produces
            // SENDING, and always with the flag set; a SENDING that
            // somehow arrives without it resolves to the static
            // marker rather than lying about an in-flight send.
            DeliveryStatus.SENDING ->
                if (sendInFlight) SendIndicatorState.SPINNER
                else SendIndicatorState.CHECK
            // A live send keeps spinning at SENT: the echo has landed
            // but delivery receipts still flow, and DELIVERED is the
            // completion Garrett means. A HISTORICAL SENT (loaded from
            // the server history or the chat-history cache — including
            // long-delivered messages whose payload carries no
            // delivered code, e.g. ones sent from another client)
            // resolves to its static marker: the check. Spinning there
            // was the stranded-spinner bug — nothing would ever
            // resolve it, because status pushes only patch the open
            // chat and history is never re-asked.
            DeliveryStatus.SENT ->
                if (sendInFlight) SendIndicatorState.SPINNER
                else SendIndicatorState.CHECK
            DeliveryStatus.DELIVERED -> SendIndicatorState.CHECK
            DeliveryStatus.READ -> SendIndicatorState.READ
        }
    }

    /**
     * The live morph fires ONLY when a message this composition has
     * watched spinning completes. A message that first composes
     * already delivered (history, or scrolled into view after the
     * fact) shows its check statically — no replayed morphs.
     */
    fun morphsToCheck(
        previous: SendIndicatorState,
        current: SendIndicatorState,
    ): Boolean =
        previous == SendIndicatorState.SPINNER &&
            (current == SendIndicatorState.CHECK ||
                current == SendIndicatorState.READ)
}

/**
 * Per-message send indicator (Garrett 2026-10-20): sits immediately to
 * the LEFT of an outgoing bubble, bottom-aligned with it. While the
 * send is in flight it is the seven-shape loading animation (the one
 * #165 removed from the send button — the button stays idle-instant);
 * on DELIVERED it morphs — a graphics-shapes [Morph] from the
 * spinner's resting circle into the check glyph, on the house spring —
 * and the check then stays with the message as its delivered marker.
 * READ keeps the established language (primary double check). Failed
 * sends never reach this composable with a status: the ViewModel
 * removes the optimistic bubble and the snackbar carries the verbatim
 * reason, exactly as before.
 *
 * This REPLACES the under-bubble DeliveryTicks row as the one
 * outgoing-status language — sent/delivered/read are all read from
 * this slot now, never two markers on the same message.
 */
@Composable
fun SendStatusIndicator(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val state = SendIndicatorLogic.stateFor(
        isOutgoing = message.isOutgoing,
        status = message.deliveryStatus,
        uploadInProgress = message.uploadProgress != null,
        sendInFlight = message.sendInFlight,
    )

    // Morph bookkeeping: remembers are keyed by the message id, so the
    // echo's id swap (optimistic → server id) starts a fresh record at
    // the echo's own status — still spinning — and the later
    // status-only DELIVERED update lands on the SAME id and morphs.
    // (Declared unconditionally, before the HIDDEN return below, so
    // the slot order never changes when the state flips.)
    var seenState by remember(message.id) { mutableStateOf(state) }
    val morphProgress = remember(message.id) {
        Animatable(
            if (state == SendIndicatorState.CHECK ||
                state == SendIndicatorState.READ
            ) {
                1f
            } else {
                0f
            },
        )
    }
    LaunchedEffect(state) {
        val from = seenState
        seenState = state
        when {
            SendIndicatorLogic.morphsToCheck(from, state) -> {
                morphProgress.snapTo(0f)
                morphProgress.animateTo(1f, RelaySprings.bouncy())
            }
            state == SendIndicatorState.CHECK ||
                state == SendIndicatorState.READ -> morphProgress.snapTo(1f)
            else -> morphProgress.snapTo(0f)
        }
    }

    if (state == SendIndicatorState.HIDDEN) return

    Box(
        modifier = modifier
            .size(18.dp)
            .semantics {
                contentDescription = when (state) {
                    SendIndicatorState.SPINNER -> "Sending"
                    SendIndicatorState.CHECK -> "Delivered"
                    SendIndicatorState.READ -> "Read"
                    SendIndicatorState.HIDDEN -> ""
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state == SendIndicatorState.SPINNER ->
                SevenShapeLoadingIndicator(diameter = 18.dp)
            state == SendIndicatorState.READ && morphProgress.value >= 1f ->
                Icon(
                    Icons.Filled.DoneAll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            else -> CheckMorphGlyph(
                progress = morphProgress.value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The delivered check: a [Morph] from a circle (the spinner loop's
 * resting silhouette, so the eye reads one continuous shape change)
 * to the Material check glyph as a filled polygon, drawn at
 * [progress] — 1f is the settled check. Same drawWithCache shape
 * machinery as the seven-shape indicator.
 */
@Composable
private fun CheckMorphGlyph(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.drawWithCache {
            val r = size.minDimension / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val circle = RoundedPolygon.circle(
                numVertices = 16, radius = r, centerX = cx, centerY = cy,
            )
            // The Material "Done" glyph as a filled ribbon polygon —
            // its 24-unit path (9,16.17) (4.83,12) (3.41,13.41) (9,19)
            // (21,7) (19.59,5.59) — scaled into this box.
            val s = size.minDimension / 24f
            val ox = (size.width - 24f * s) / 2f
            val oy = (size.height - 24f * s) / 2f
            fun px(x: Float) = ox + x * s
            fun py(y: Float) = oy + y * s
            val check = RoundedPolygon(
                vertices = floatArrayOf(
                    px(9f), py(16.17f),
                    px(4.83f), py(12f),
                    px(3.41f), py(13.41f),
                    px(9f), py(19f),
                    px(21f), py(7f),
                    px(19.59f), py(5.59f),
                ),
                // Under half the glyph's shortest edge (≈2 units) so
                // the corner cuts always fit.
                rounding = CornerRounding(0.8f * s),
                centerX = cx,
                centerY = cy,
            )
            val morph = Morph(circle, check)
            val androidPath = android.graphics.Path()
            onDrawBehind {
                androidPath.rewind()
                if (progress >= 1f) {
                    check.toPath(androidPath)
                } else {
                    morph.toPath(progress.coerceIn(0f, 1f), androidPath)
                }
                drawPath(androidPath.asComposePath(), color)
            }
        },
    )
}
