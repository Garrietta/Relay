package com.garrietta.relay.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedAvatar
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.SquircleShape
import com.garrietta.relay.ui.theme.WavyBlobShape
import com.garrietta.relay.ui.theme.rememberContactChatScheme
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
    onBack: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val seedColor by viewModel.seedColor.collectAsState()
    val photoUri by viewModel.photoUri.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Shared-element target: the inbox avatar morphs here on
                            // push — and morphs BACK down on pop via
                            // motionSharedAvatar (message content is exempt,
                            // so the #45 ghosting fix holds).
                            ChatAvatar(
                                name = displayName,
                                photoUri = photoUri,
                                modifier = Modifier.motionSharedAvatar(
                                    SharedElementKeys.avatar(threadId),
                                    shape = WavyBlobShape,
                                ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    displayName,
                                    // Emphasized type for the eye-stop moment.
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                                Text(
                                    transportLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
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
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
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
                            )
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

/**
 * M3E shape-as-state: bubbles rest soft and bubbly; long-press selection
 * squares them off (and tints them) to communicate the state change.
 */
@Composable
private fun MessageBubble(
    message: Message,
    position: ItemPosition,
    selected: Boolean,
    selectionActive: Boolean,
    onToggleSelect: () -> Unit,
) {
    val squareness by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = RelaySprings.bouncy(),
        label = "bubbleSquareness",
    )
    val shape = remember(squareness, message.isOutgoing, position) {
        lerpBubbleShape(squareness, message.isOutgoing, position)
    }
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        message.isOutgoing -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(containerColor)
                .combinedClickable(
                    onClick = { if (selectionActive) onToggleSelect() },
                    onLongClick = onToggleSelect,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                message.body,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
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

/** Corner radii for the soft resting bubble shape. */
private data class BubbleCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomStart: Dp,
    val bottomEnd: Dp,
)

private fun bubbleCorners(isOutgoing: Boolean, position: ItemPosition): BubbleCorners {
    val large = 28.dp
    val small = 10.dp
    val tail = 4.dp
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
        // Large tall text-box bubble.
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
                .heightIn(min = 104.dp),
            minLines = 3,
            maxLines = 6,
        )
        Spacer(Modifier.height(8.dp))
        // Attached attachment bar: persistent row of rounded-square buttons
        // beneath the text box; send sits at the row's right end.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachmentBarButton(
                icon = Icons.Filled.Image,
                label = "Gallery",
                onClick = onAttachmentSoon,
            )
            AttachmentBarButton(
                icon = Icons.Filled.PhotoCamera,
                label = "Camera",
                onClick = onAttachmentSoon,
            )
            AttachmentBarButton(
                icon = Icons.Filled.Gif,
                label = "GIF",
                onClick = onAttachmentSoon,
            )
            AttachmentBarButton(
                icon = Icons.Filled.AttachFile,
                label = "Files",
                onClick = onAttachmentSoon,
            )
            Spacer(Modifier.weight(1f))
            // Large circular send button — the densest color in the scheme.
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
                    .size(64.dp)
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
 * One persistent attachment button: SQUIRCLE (expressive superellipse),
 * tonal container, no toggle behavior. Presses bounce and the squircle
 * tightens slightly — shape-as-state. Picks are stubbed ("coming soon").
 */
@Composable
private fun AttachmentBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
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
        shape = SquircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Position of a bubble within a sender group (segmented corners). */
private enum class ItemPosition { SINGLE, FIRST, MIDDLE, LAST }

private fun formatDay(millis: Long): String =
    SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(millis))

@Composable
private fun ChatAvatar(name: String, photoUri: String?, modifier: Modifier = Modifier) {
    ContactAvatar(
        photoUri = photoUri,
        name = name,
        modifier = modifier,
        size = 44.dp,
        textStyle = MaterialTheme.typography.titleLarge,
    )
}
