package com.garrietta.relay.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedAvatar
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.SquircleShape
import com.garrietta.relay.ui.theme.WavyBlobShape
import com.garrietta.relay.ui.theme.expressiveShape
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
    // Header mode from the OTHER-participant count (self excluded — the
    // provider's MMS addr table lists self, which used to misdetect him+1
    // threads as groups). Per-sender bubble theming applies to pair + hex.
    val headerMode = when {
        others.size <= 1 -> HeaderMode.OneToOne
        others.size == 2 -> HeaderMode.Pair
        else -> HeaderMode.Hex
    }
    val isGroup = headerMode != HeaderMode.OneToOne
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Hex header minimizes 1:1 with scroll — direct tracking, NO spring (a
    // spring retargeted every scroll frame never settles; the #102 lesson).
    val rawCollapse by remember {
        derivedStateOf {
            val offset = listState.firstVisibleItemScrollOffset.toFloat()
            val jump = if (listState.firstVisibleItemIndex > 0) 240f else 0f
            ((jump + offset) / 240f).coerceIn(0f, 1f)
        }
    }

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
                    transportLabel = transportLabel,
                    collapse = rawCollapse,
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
                            // reverseLayout: index+1 is visually above, index-1 below.
                            val aboveDiff = messages.getOrNull(index + 1)?.isOutgoing != message.isOutgoing
                            val belowDiff = messages.getOrNull(index - 1)?.isOutgoing != message.isOutgoing
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

/** Which header layout a thread gets — from the OTHER-participant count. */
private enum class HeaderMode { OneToOne, Pair, Hex }

/**
 * Chat header dispatch. 1:1 = their face centered at the top with their name
 * directly under. 2 others = the two avatars side by side (second offset to
 * the right of the first, small gap, never overlapping) with comma-separated
 * names below — or the chat name when the chat has one. 3+ = avatars filling
 * a hexagonal space, minimizing on scroll. Back arrow always top-left.
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
    collapse: Float,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        when (mode) {
            HeaderMode.OneToOne -> {
                // Self-chat (no others): show self.
                val peer = others.firstOrNull() ?: participants.firstOrNull()
                val name = peer?.displayName?.ifBlank { peer.address }
                    ?.ifBlank { displayName } ?: displayName
                OneToOneHeader(
                    name = name,
                    photoUri = peer?.photoUri ?: photoUri,
                    shapeKey = peer?.address ?: recipient,
                    threadId = threadId,
                    transportLabel = transportLabel,
                )
            }
            HeaderMode.Pair -> PairHeader(
                others = others,
                transportLabel = transportLabel,
            )
            HeaderMode.Hex -> HexHeader(
                others = others,
                transportLabel = transportLabel,
                collapse = collapse,
            )
        }
    }
}

/**
 * 1:1 header: their face centered at the top with their name directly under.
 * Compact. The avatar keeps the inbox shared-element morph target (message
 * content is exempt, so the #45 ghosting fix holds).
 */
@Composable
private fun OneToOneHeader(
    name: String,
    photoUri: String?,
    shapeKey: String,
    threadId: Long,
    transportLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContactAvatar(
            photoUri = photoUri,
            name = name,
            size = 60.dp,
            shapeKey = shapeKey,
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.motionSharedAvatar(
                SharedElementKeys.avatar(threadId),
                shape = WavyBlobShape,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = transportLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 2-other-people header: the two avatars side by side — the second
 * horizontally offset to the right of the first with a small gap, never
 * overlapping — names comma-separated below (or the chat name when the chat
 * has a custom name; hook: replace [names] with the custom name).
 */
@Composable
private fun PairHeader(
    others: List<Participant>,
    transportLabel: String,
) {
    val names = remember(others) {
        others.take(2).map {
            it.displayName.substringBefore(" ").ifBlank { it.address }
        }.joinToString(", ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            others.take(2).forEach { p ->
                ContactAvatar(
                    photoUri = p.photoUri,
                    name = p.displayName.ifBlank { p.address },
                    size = 48.dp,
                    shapeKey = p.address,
                    textStyle = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = names,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = transportLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 3+-other-people header: avatars arranged to fill a hexagonal space —
 * one centered with up to six around it (or all evenly around the ring when
 * six or fewer), small gaps, never overlapping. Minimizes 1:1 with scroll.
 */
@Composable
private fun HexHeader(
    others: List<Participant>,
    transportLabel: String,
    collapse: Float,
) {
    val shown = others.take(7)
    val avatarSize = (40 - 14 * collapse).dp
    val nameSize = (20 - 5 * collapse).sp
    val verticalPad = (10 - 6 * collapse).dp
    val groupName = remember(others) {
        val names = others.map {
            it.displayName.substringBefore(" ").ifBlank { it.address }
        }
        if (names.size <= 3) names.joinToString(", ")
        else names.take(3).joinToString(", ") + " +${names.size - 3}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = verticalPad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HexAvatarField(
            participants = shown,
            avatarSize = avatarSize,
        )
        Spacer(Modifier.height((8 - 4 * collapse).dp))
        Text(
            text = groupName,
            fontSize = nameSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = "${others.size} people \u00b7 $transportLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Hexagonal avatar arrangement: with 7, one centered and six around it;
 * with 6 or fewer, all evenly spaced around the ring starting at the top.
 * The ring radius derives from avatar size + gap so neighbors never overlap.
 */
@Composable
private fun HexAvatarField(
    participants: List<Participant>,
    avatarSize: Dp,
) {
    val k = participants.size
    if (k == 0) return
    val gap = 8.dp
    val ring = if (k == 7) participants.drop(1) else participants
    val center = if (k == 7) participants.first() else null
    val ringCount = ring.size
    // Chord between adjacent ring avatars must clear avatarSize + gap:
    // R = chord / (2 * sin(pi / ringCount)).
    val radius: Dp = if (ringCount < 3) {
        (avatarSize + gap) / 2
    } else {
        (avatarSize + gap) / (2 * kotlin.math.sin(kotlin.math.PI / ringCount).toFloat())
    }
    Box(
        modifier = Modifier.size(radius * 2 + avatarSize),
        contentAlignment = Alignment.Center,
    ) {
        center?.let { p ->
            ContactAvatar(
                photoUri = p.photoUri,
                name = p.displayName.ifBlank { p.address },
                size = avatarSize,
                shapeKey = p.address,
                textStyle = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ring.forEachIndexed { index, p ->
            val angle = -kotlin.math.PI / 2 + index * 2 * kotlin.math.PI / ringCount
            val xOff = radius * kotlin.math.cos(angle).toFloat()
            val yOff = radius * kotlin.math.sin(angle).toFloat()
            ContactAvatar(
                photoUri = p.photoUri,
                name = p.displayName.ifBlank { p.address },
                size = avatarSize,
                shapeKey = p.address,
                textStyle = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = xOff, y = yOff),
            )
        }
    }
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
 * M3 Expressive composer: a LARGE tall text-box bubble with a PERSISTENT
 * attached attachment bar beneath it (Garrett's call: no toggles, no
 * pop-ups) — rounded-square attachment buttons in a row, and the LARGE
 * circular send button at the row's right end with a spring pop on send.
 */
@Composable
private fun Composer(
    sending: Boolean,
    onSend: (String) -> Unit,
    onAttachmentSoon: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank() && !sending
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Compact text-box bubble: single-line height, grows to maxLines.
        // The button row below holds a FIXED size — the text field takes
        // the flexible space, buttons never shrink (typing or not).
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            minLines = 1,
            maxLines = 5,
        )
        Spacer(Modifier.height(8.dp))
        // Attached attachment bar: persistent row of expressive buttons
        // beneath the text box; send sits at the row's right end. Each
        // attachment button takes a DIFFERENT shape and tonal color from the
        // vocabulary (one family, varied members); the send button keeps the
        // densest color in the scheme.
        val attachStyles = listOf(
            AttachSpec(
                icon = Icons.Filled.Image,
                label = "Gallery",
                shape = expressiveShape("Cookie6"),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            AttachSpec(
                icon = Icons.Filled.PhotoCamera,
                label = "Camera",
                shape = expressiveShape("Sunny"),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
            AttachSpec(
                icon = Icons.Filled.Gif,
                label = "GIF",
                shape = expressiveShape("Gem"),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            AttachSpec(
                icon = Icons.Filled.AttachFile,
                label = "Files",
                shape = expressiveShape("Pill"),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Fixed row height: the button sizes below are required, so
                // the row can never collapse and shrink them (typing or not).
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (spec in attachStyles) {
                AttachmentBarButton(
                    icon = spec.icon,
                    label = spec.label,
                    shape = spec.shape,
                    containerColor = spec.container,
                    contentColor = spec.content,
                    onClick = onAttachmentSoon,
                )
            }
            Spacer(Modifier.weight(1f))
            // Large circular send button — the densest color in the scheme.
            // requiredSize: never coerced smaller by tight parent constraints.
            IconButton(
                onClick = ::submit,
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .requiredSize(64.dp)
                    .clip(CircleShape)
                    .graphicsLayer {
                        scaleX = sendPop.value
                        scaleY = sendPop.value
                    },
            ) {
                if (sending) {
                    // Expressive sending state: the seven-shape loop.
                    SevenShapeLoadingIndicator(
                        diameter = 26.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/**
 * One persistent attachment button: an expressive shape + tonal color from
 * the vocabulary (not all squircles), no toggle behavior. Presses bounce —
 * shape-as-state via press scale. Picks are stubbed ("coming soon").
 */
private data class AttachSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val shape: Shape,
    val container: Color,
    val content: Color,
)

@Composable
private fun AttachmentBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    shape: Shape = SquircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "attachPress",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = shape,
        color = containerColor,
        tonalElevation = 1.dp,
        modifier = Modifier
            // requiredSize: the button holds 52dp in every state — typing,
            // keyboard open, or not. Never coerced smaller by the parent.
            .requiredSize(52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Position of a bubble within a sender group (segmented corners). */
private enum class ItemPosition { SINGLE, FIRST, MIDDLE, LAST }

private fun formatDay(millis: Long): String =
    SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(millis))
