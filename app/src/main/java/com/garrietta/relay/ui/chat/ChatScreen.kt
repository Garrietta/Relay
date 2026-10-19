package com.garrietta.relay.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.model.isTransportSystemNotice
import com.garrietta.relay.rcs.proto.RcsMediaAttachment
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedAvatar
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.AvatarShapeAssigner
import com.garrietta.relay.ui.theme.ClusterMember
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.GroupAvatarCluster
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.WavyBlobShape
import com.garrietta.relay.ui.theme.rememberContactChatScheme
import com.garrietta.relay.ui.theme.senderBubbleColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    displayName: String,
    recipient: String,
    threadId: Long,
    threadIds: List<Long>,
    transportLabel: String = "SMS",
    /** Server conversation id for RCS rows (paired mode); null = Telephony. */
    rcsConversationId: String? = null,
    onBack: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val seedColor by viewModel.seedColor.collectAsState()
    val photoUri by viewModel.photoUri.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val participants by viewModel.participants.collectAsState()
    val others by viewModel.others.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Visible-chat tracking for notification suppression: while this chat
    // is on screen, MainActivity's collector skips its incoming messages.
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    // Header mode from the OTHER-participant count (self excluded and
    // deduped — the server's participant list can include him twice).
    // Per-sender bubble theming applies to groups.
    val headerMode = if (others.size <= 1) HeaderMode.OneToOne else HeaderMode.Group
    val isGroup = headerMode == HeaderMode.Group
    // Stable per-conversation key for the group avatar pattern: the same
    // group always gets the same cluster arrangement (spiral/grid/zigzag).
    val patternKey = rcsConversationId ?: "tel:$threadId"
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Sticky collapse with hysteresis, gated on real scrollability. The old
    // "any offset > 0" flapped: a short thread's overscroll bounce would
    // start the minimize and snap it straight back to full size. Now the
    // header only minimizes past a threshold when the list can actually
    // scroll, and stays minimized until the user returns to the very top.
    // Boolean-driven (never a spring retargeted every scroll frame — the
    // #102 lesson); RelaySprings animate the change.
    var headerCollapsed by remember { mutableStateOf(false) }
    val collapseThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
    ) {
        val idx = listState.firstVisibleItemIndex
        val off = listState.firstVisibleItemScrollOffset
        val scrollable = listState.canScrollForward || listState.canScrollBackward
        headerCollapsed = if (!headerCollapsed) {
            scrollable && (idx > 0 || off > collapseThresholdPx)
        } else {
            !(idx == 0 && off == 0)
        }
    }
    val scrolled = headerCollapsed
    // The REAL transport label: the ViewModel refreshes it from the
    // server's Conversation.type for RCS rows (never hardcoded).
    val liveTransportLabel by viewModel.transportLabel.collectAsState()

    // Per-contact theming: seed the chat color scheme from the contact's
    // profile photo (Palette). Falls back to the wallpaper dynamic scheme.
    LaunchedEffect(recipient) { viewModel.loadContactTheme(recipient) }
    val chatScheme = rememberContactChatScheme(seedColor)

    LaunchedEffect(sendError) {
        sendError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }

    MaterialTheme(colorScheme = chatScheme) {
        // Inside the seeded scheme: the thread background the sender-name
        // tones are contrast-checked against.
        val threadBackground = MaterialTheme.colorScheme.surfaceContainerLow
        Scaffold(
            topBar = {
                ChatHeader(
                    mode = headerMode,
                    others = others,
                    participants = participants,
                    displayName = displayName,
                    photoUri = photoUri,
                    recipient = recipient,
                    threadId = threadId,
                    transportLabel = liveTransportLabel,
                    scrolled = scrolled,
                    patternKey = patternKey,
                    onBack = onBack,
                )
            },
            // Seamless with the thread container below — the header
            // background and the list background are one continuous tone.
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            // Thread container: the chat reads as one large rounded container
            // below the app bar (M3E containment), visually independent of it.
            // Shared-bounds target for the inbox conversation row.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .motionSharedBounds(
                        SharedElementKeys.conversation(threadId),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    )
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .imePadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Bubbles never span the full width: received keep an
                    // empty right margin, sent an empty left one.
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val maxBubbleWidth = maxWidth * 0.8f
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                        itemsIndexed(
                            items = messages,
                            key = { _, it -> it.id },
                            contentType = { _, _ -> "message" },
                        ) { index, message ->
                            // Transport system notices render as centered
                            // inline text — never bubbles, never attributed.
                            if (isTransportSystemNotice(message.body)) {
                                SystemNotice(text = message.body.trim())
                                return@itemsIndexed
                            }
                            // RCS media: image/file bubble (downloaded +
                            // decrypted on first compose), never an empty
                            // placeholder.
                            val media = message.media
                            if (media != null) {
                                MediaBubble(
                                    message = message,
                                    media = media,
                                    loadMedia = { att -> viewModel.mediaBytes(att) },
                                    maxBubbleWidth = maxBubbleWidth,
                                )
                                return@itemsIndexed
                            }
                            // reverseLayout: index+1 is visually above, index-1 below.
                            // System notices don't break bubble grouping.
                            val aboveDiff = nearestNonNotice(messages, index, +1)?.isOutgoing != message.isOutgoing
                            val belowDiff = nearestNonNotice(messages, index, -1)?.isOutgoing != message.isOutgoing
                            val position = when {
                                aboveDiff && belowDiff -> ItemPosition.SINGLE
                                aboveDiff -> ItemPosition.FIRST
                                belowDiff -> ItemPosition.LAST
                                else -> ItemPosition.MIDDLE
                            }
                            MessageBubble(
                                message = message,
                                position = position,
                                selected = message.id in selectedIds,
                                selectionActive = selectedIds.isNotEmpty(),
                                onToggleSelect = { viewModel.toggleSelect(message.id) },
                                // Group mode: resolve the sender for
                                // per-sender bubble theming. 1:1 chats pass
                                // null and render exactly as before.
                                sender = if (isGroup && !message.isOutgoing)
                                    viewModel.senderFor(message.address) else null,
                                senderSeed = if (isGroup && !message.isOutgoing)
                                    viewModel.seedFor(message.address) else null,
                                isGroup = isGroup,
                                threadBackground = threadBackground,
                                darkTheme = darkTheme,
                                maxBubbleWidth = maxBubbleWidth,
                            )
                        }
                        }
                    }
                    Composer(
                        sending = sending,
                        onSend = { viewModel.send(recipient, it) },
                        onAttachmentSoon = {
                            scope.launch {
                                snackbarHostState.showSnackbar("Attachments are coming soon")
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Which header layout a thread gets — from the OTHER-participant count (self excluded, deduped). */
private enum class HeaderMode { OneToOne, Group }

/**
 * Chat header dispatch. 1:1 = their face centered at the top with their
 * name directly under (compact: ~half the old footprint); on scroll it
 * collapses (RelaySprings) to the height of the top bar. Group = a static
 * inline row: back arrow, the group's unique avatar cluster (spiral / grid
 * / zigzag, max five, then a "+N" circle), comma-separated member names
 * with ellipsis; on scroll the photos drop away and only the names remain.
 * Background matches the thread container — no seam.
 */
@Composable
private fun ChatHeader(
    mode: HeaderMode,
    others: List<Participant>,
    participants: List<Participant>,
    displayName: String,
    photoUri: String?,
    recipient: String,
    threadId: Long,
    transportLabel: String,
    scrolled: Boolean,
    patternKey: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        when (mode) {
            HeaderMode.OneToOne -> {
                // Self-chat (no others): show self, once.
                val peer = others.firstOrNull() ?: participants.firstOrNull()
                val name = peer?.displayName?.ifBlank { peer.address }
                    ?.ifBlank { displayName } ?: displayName
                OneToOneHeader(
                    name = name,
                    photoUri = peer?.photoUri ?: photoUri,
                    shapeKey = peer?.address ?: recipient,
                    threadId = threadId,
                    transportLabel = transportLabel,
                    scrolled = scrolled,
                    onBack = onBack,
                )
            }
            HeaderMode.Group -> GroupHeader(
                others = others,
                transportLabel = transportLabel,
                scrolled = scrolled,
                patternKey = patternKey,
                onBack = onBack,
            )
        }
    }
}

/**
 * 1:1 header: their face centered at the top with their name directly
 * under. On scroll it collapses (RelaySprings crossfade) to the height of
 * the top bar: back arrow, small avatar, name + transport. The avatar keeps
 * the inbox shared-element morph target (message content is exempt, so the
 * #45 ghosting fix holds).
 */
@Composable
private fun OneToOneHeader(
    name: String,
    photoUri: String?,
    shapeKey: String,
    threadId: Long,
    transportLabel: String,
    scrolled: Boolean,
    onBack: () -> Unit,
) {
    AnimatedContent(
        targetState = scrolled,
        transitionSpec = {
            fadeIn(animationSpec = RelaySprings.bouncy()) togetherWith
                fadeOut(animationSpec = RelaySprings.bouncy())
        },
        label = "oneToOneCollapse",
    ) { isScrolled ->
        if (isScrolled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                ContactAvatar(
                    photoUri = photoUri,
                    name = name,
                    size = 40.dp,
                    shapeKey = shapeKey,
                    textStyle = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = transportLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Compact expanded header (~half the old footprint): the back
            // arrow overlays the top-start corner instead of consuming a
            // full row; 48dp avatar, tighter type and spacing.
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Overlay clip matches the resolved per-contact shape (not
                    // a hardcoded WavyBlobShape) — the inbox side was fixed
                    // for the same corner-glitch; both ends must agree.
                    val overlayShape = remember(shapeKey) {
                        AvatarShapeAssigner.namedShapeFor(shapeKey).shape
                    }
                    ContactAvatar(
                        photoUri = photoUri,
                        name = name,
                        size = 48.dp,
                        shapeKey = shapeKey,
                        textStyle = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.motionSharedAvatar(
                            SharedElementKeys.avatar(threadId),
                            shape = overlayShape,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 56.dp),
                    )
                    Text(
                        text = transportLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Group header: a static inline row — back arrow, the group's UNIQUE avatar
 * cluster (spiral / grid / zigzag, deterministic per conversation: different
 * sizes, shapes and rotations, overlapping), then the comma-separated member
 * names, ellipsized before the right edge. No scroll-driven avatar
 * animation: the cluster stays in line from the start; on scroll the photos
 * drop away and only the names remain.
 */
@Composable
private fun GroupHeader(
    others: List<Participant>,
    transportLabel: String,
    scrolled: Boolean,
    patternKey: String,
    onBack: () -> Unit,
) {
    // Defense in depth: a participant whose name is blank or the literal
    // "Unknown" renders as its number, never the word "Unknown".
    val names = remember(others) {
        others.map {
            val n = it.displayName.substringBefore(" ").ifBlank { it.address }
            if (n.equals("Unknown", ignoreCase = true)) it.address else n
        }.filter { it.isNotBlank() }.joinToString(", ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
        }
        AnimatedVisibility(
            visible = !scrolled,
            enter = fadeIn(RelaySprings.gentle()) + expandHorizontally(RelaySprings.gentle()),
            exit = fadeOut(RelaySprings.gentle()) + shrinkHorizontally(RelaySprings.gentle()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatarCluster(
                    members = others.map {
                        ClusterMember(
                            photoUri = it.photoUri,
                            name = it.displayName.ifBlank { it.address },
                            address = it.address,
                        )
                    },
                    conversationKey = patternKey,
                    boxSize = 76.dp,
                )
                Spacer(Modifier.width(10.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = names,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = transportLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Google Messages-style inline transport notice ("RCS chat with {name}",
 * "Texting with {number} (SMS/MMS)"): centered small text at its timeline
 * position, marking the transport switch point. Never a bubble, never
 * attributed to a sender.
 */
@Composable
private fun SystemNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 32.dp),
    )
}

/**
 * True pixel dimensions of encoded image [bytes] without decoding the
 * pixels, or null when the bytes aren't a decodable image.
 */
private fun decodeImageBounds(bytes: ByteArray): Pair<Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
}

/**
 * RCS media bubble: downloads + decrypts the attachment on first compose
 * (disk-cached in the transport), renders images with an expressive clip,
 * tap to view fullscreen. Failures surface VERBATIM ("Media unavailable:
 * <reason>") instead of the old empty placeholder bubble.
 */
@Composable
private fun MediaBubble(
    message: Message,
    media: RcsMediaAttachment,
    loadMedia: suspend (RcsMediaAttachment) -> ByteArray,
    maxBubbleWidth: Dp,
) {
    var bytes by remember(message.id) { mutableStateOf<ByteArray?>(null) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    var zoomed by remember(message.id) { mutableStateOf(false) }

    LaunchedEffect(message.id) {
        if (bytes == null && error == null) {
            try {
                bytes = loadMedia(media)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    onClick = { if (bytes != null && media.isImage) zoomed = true },
                    onLongClick = { },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val b = bytes
            val err = error
            when {
                err != null -> Text(
                    text = "Media unavailable: $err",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                b != null && media.isImage -> {
                    // Natural-size rendering: decode the true pixel bounds and
                    // NEVER upscale. A small inline/thumbnail image blown up to
                    // bubble size is exactly the blocky "corrupted" look
                    // (diagnosed 2026-10-19). Small images render small and
                    // sharp; large ones are capped at the bubble max.
                    val bounds = remember(message.id) { decodeImageBounds(b) }
                    if (bounds == null) {
                        Text(
                            text = "Media unavailable: undecodable image",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        val density = LocalDensity.current
                        val naturalW = with(density) { bounds.first.toDp() }
                        val naturalH = with(density) { bounds.second.toDp() }
                        val w = minOf(naturalW, maxBubbleWidth)
                        val h = (w * (naturalH.value / naturalW.value)).coerceAtLeast(24.dp)
                        AsyncImage(
                            model = b,
                            contentDescription = "Shared image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(w, h),
                        )
                    }
                }
                b != null -> {
                    // Non-image file: labeled tile.
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = media.mimeType.ifBlank { "File" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (media.size > 0) {
                                Text(
                                    text = formatBytes(media.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                else -> Box(
                    modifier = Modifier.size(width = 220.dp, height = 150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SevenShapeLoadingIndicator(
                        diameter = 40.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (message.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            // Caption as a compact bubble under the media.
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (message.isOutgoing) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (zoomed) {
        val b = bytes
        if (b != null) {
            Dialog(onDismissRequest = { zoomed = false }) {
                AsyncImage(
                    model = b,
                    contentDescription = "Shared image fullscreen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> "${n / (1024 * 1024)} MB"
}

/**
 * Nearest timeline entry that is NOT a system notice — notices sit inline
 * but must not break bubble grouping.
 */
private fun nearestNonNotice(messages: List<Message>, index: Int, dir: Int): Message? {
    var i = index + dir
    while (i in messages.indices) {
        val m = messages[i]
        if (!isTransportSystemNotice(m.body)) return m
        i += dir
    }
    return null
}

/**
 * M3E shape-as-state: bubbles rest soft and bubbly; long-press selection
 * squares them off (and tints them) to communicate the state change.
 *
 * In GROUP chats, incoming bubbles are themed per SENDER: the bubble fill
 * comes from the sender's avatar image (Palette seed) and the sender's name
 * rides above the bubble in a matching tone — the reference's high-variety
 * group look. 1:1 chats keep the neutral incoming treatment.
 */
@Composable
private fun MessageBubble(
    message: Message,
    position: ItemPosition,
    selected: Boolean,
    selectionActive: Boolean,
    onToggleSelect: () -> Unit,
    sender: Participant? = null,
    senderSeed: Color? = null,
    isGroup: Boolean = false,
    threadBackground: Color = Color.Transparent,
    darkTheme: Boolean = false,
    /** Received bubbles keep an empty right margin; sent an empty left one. */
    maxBubbleWidth: Dp = Dp.Unspecified,
) {
    val squareness by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = RelaySprings.bouncy(),
        label = "bubbleSquareness",
    )
    val shape = remember(squareness, message.isOutgoing, position) {
        lerpBubbleShape(squareness, message.isOutgoing, position)
    }
    val groupIncoming = isGroup && !message.isOutgoing && sender != null
    // Per-sender colors; every text pair is contrast-verified inside.
    val (senderContainer, senderOnContainer, senderNameTone) =
        senderBubbleColors(senderSeed, threadBackground, darkTheme, MaterialTheme.colorScheme)
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        message.isOutgoing -> MaterialTheme.colorScheme.primaryContainer
        groupIncoming -> senderContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
        groupIncoming -> senderOnContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        if (isGroup && !message.isOutgoing && sender != null) {
            // Sender identity: mini avatar in the sender's own expressive
            // shape, name above the bubble in the sender's tone.
            // (Smart-cast: the null check is inline so `sender` is non-null.)
            Row(verticalAlignment = Alignment.Top) {
                ContactAvatar(
                    photoUri = sender.photoUri,
                    name = sender.displayName.ifBlank { sender.address },
                    size = 28.dp,
                    shapeKey = sender.address,
                    textStyle = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = maxBubbleWidth),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = sender.displayName.ifBlank { sender.address },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = senderNameTone,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    BubbleBox(
                        shape = shape,
                        containerColor = containerColor,
                        contentColor = contentColor,
                        selectionActive = selectionActive,
                        onToggleSelect = onToggleSelect,
                        body = message.body,
                        maxBubbleWidth = maxBubbleWidth,
                    )
                }
            }
        } else {
            BubbleBox(
                shape = shape,
                containerColor = containerColor,
                contentColor = contentColor,
                selectionActive = selectionActive,
                onToggleSelect = onToggleSelect,
                body = message.body,
                maxBubbleWidth = maxBubbleWidth,
            )
        }
        if (message.isMms) {
            Text(
                "MMS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/** The bubble surface itself, shared by the 1:1 and group layouts. */
@Composable
private fun BubbleBox(
    shape: RoundedCornerShape,
    containerColor: Color,
    contentColor: Color,
    selectionActive: Boolean,
    onToggleSelect: () -> Unit,
    body: String,
    maxBubbleWidth: Dp = Dp.Unspecified,
) {
    Box(
        modifier = Modifier
            .widthIn(max = maxBubbleWidth)
            .clip(shape)
            .background(containerColor)
            .combinedClickable(
                onClick = { if (selectionActive) onToggleSelect() },
                onLongClick = onToggleSelect,
            )
            // Uniform interior padding on every bubble.
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
    }
}

/** Corner radii for the soft resting bubble shape. */
private data class BubbleCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomStart: Dp,
    val bottomEnd: Dp,
)

private fun bubbleCorners(isOutgoing: Boolean, position: ItemPosition): BubbleCorners {
    // Generous radii over the background underlay (Garrett's reference):
    // big soft rounds, tighter sender-side tails.
    val large = 30.dp
    val small = 12.dp
    val tail = 6.dp
    return when (position) {
        ItemPosition.SINGLE -> BubbleCorners(
            topStart = large, topEnd = large,
            bottomStart = if (isOutgoing) large else tail,
            bottomEnd = if (isOutgoing) tail else large,
        )
        ItemPosition.FIRST -> BubbleCorners(large, large, small, small)
        ItemPosition.MIDDLE -> BubbleCorners(small, small, small, small)
        ItemPosition.LAST -> BubbleCorners(
            topStart = small, topEnd = small,
            bottomStart = if (isOutgoing) large else tail,
            bottomEnd = if (isOutgoing) tail else large,
        )
    }
}

/**
 * Interpolates between the soft resting bubble and the squared selection
 * shape. [squareness] 0 = resting, 1 = fully squared off.
 */
private fun lerpBubbleShape(
    squareness: Float,
    isOutgoing: Boolean,
    position: ItemPosition,
): RoundedCornerShape {
    val soft = bubbleCorners(isOutgoing, position)
    val squared = 10.dp
    fun lerp(a: Dp, b: Dp): Dp = a + (b - a) * squareness
    return RoundedCornerShape(
        topStart = lerp(soft.topStart, squared),
        topEnd = lerp(soft.topEnd, squared),
        bottomEnd = lerp(soft.bottomEnd, squared),
        bottomStart = lerp(soft.bottomStart, squared),
    )
}

/**
 * Reference-style one-pill composer (Garrett's reference image): ONE outer
 * pill holding [+] [camera] [voice] on the left, the text field as a
 * DISTINCT INNER pill, and [smiley] + [send] at the right end.
 *
 * [+] opens the attachment sheet (Gallery/Camera/GIF/Files); the camera
 * button opens the sheet too; voice and smiley are stubs until those
 * features land. Send keeps its fixed size and spring pop, and no button
 * shrinks while typing (requiredSize throughout — the #110 no-shrink rule).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    sending: Boolean,
    onSend: (String) -> Unit,
    onAttachmentSoon: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank() && !sending
    // Focus animation (Garrett's directive): tapping the text field expands
    // the inner Message pill to the full width of the outer pill while the
    // attachment buttons fade+shrink away. They spring back WHENEVER the
    // field loses focus — "whenever i tap on this and i exit out that
    // should move back over to its original position because i'm no longer
    // trying to type" — not only when the field is empty. Send + smiley
    // stay put.
    val expanded = focused
    val scope = rememberCoroutineScope()
    val sendPop = remember { Animatable(1f) }

    fun submit() {
        if (!canSend) return
        onSend(text.trim())
        text = ""
        // Spring pop on send — the unified bouncy language.
        scope.launch {
            sendPop.snapTo(0.82f)
            sendPop.animateTo(1f, RelaySprings.bouncy())
        }
    }

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Attachment buttons hide while the text field is expanded
            // (focused): fade+shrink out, bouncy spring back on focus loss.
            // Exits use a quick tween — exits never bounce, so nothing
            // flickers. Fixed sizes — never coerced smaller (#110 rule).
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn() + expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = RelaySprings.bouncy(),
                ),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(200),
                ),
            ) {
                // + : opens the attachment sheet.
                IconButton(
                    onClick = { showAttachSheet = true },
                    modifier = Modifier.requiredSize(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Attachments",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn() + expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = RelaySprings.bouncy(),
                ),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(200),
                ),
            ) {
                // Camera: opens the sheet (Camera is one of its options).
                IconButton(
                    onClick = { showAttachSheet = true },
                    modifier = Modifier.requiredSize(48.dp),
                ) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = "Camera",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn() + expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = RelaySprings.bouncy(),
                ),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(200),
                ),
            ) {
                // Voice messages: stub for now.
                IconButton(
                    onClick = onAttachmentSoon,
                    modifier = Modifier.requiredSize(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice message",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            // Inner text pill: the distinct "Message" field, like the
            // reference's white inner pill on the blue outer pill.
            // CONTRAST (Garrett 2026-10-19): the pill blended into the chat
            // background — surfaceContainerHighest gives it real tonal
            // separation from the surfaceContainerLow thread behind it, in
            // both light and dark themes.
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, end = 2.dp),
                ) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Message") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focused = it.isFocused },
                        minLines = 1,
                        maxLines = 5,
                    )
                    // Smiley: emoji picker stub for now.
                    IconButton(
                        onClick = onAttachmentSoon,
                        modifier = Modifier.requiredSize(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.SentimentSatisfied,
                            contentDescription = "Emoji",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            // Send at the pill's right end — the densest color in the scheme.
            // requiredSize: never coerced smaller by tight parent constraints.
            IconButton(
                onClick = ::submit,
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .padding(end = 2.dp)
                    .requiredSize(52.dp)
                    .clip(CircleShape)
                    .graphicsLayer {
                        scaleX = sendPop.value
                        scaleY = sendPop.value
                    },
            ) {
                if (sending) {
                    // Expressive sending state: the seven-shape loop.
                    SevenShapeLoadingIndicator(
                        diameter = 24.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    if (showAttachSheet) {
        AttachmentSheet(
            onDismiss = { showAttachSheet = false },
            onPick = {
                showAttachSheet = false
                onAttachmentSoon()
            },
        )
    }
}

/**
 * Attachment picker sheet: Gallery, Camera, GIF, Files. Picks are still
 * stubbed ("coming soon") — the sheet is the entry point for sends later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Text(
                text = "Attachments",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            val options = listOf(
                Icons.Filled.Image to "Gallery",
                Icons.Filled.PhotoCamera to "Camera",
                Icons.Filled.Gif to "GIF",
                Icons.Filled.AttachFile to "Files",
            )
            for ((icon, label) in options) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(label) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}


/** Position of a bubble within a sender group (segmented corners). */
private enum class ItemPosition { SINGLE, FIRST, MIDDLE, LAST }

private fun formatDay(millis: Long): String =
    SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(millis))
