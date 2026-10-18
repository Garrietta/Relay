package com.garrietta.relay.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.ui.motion.M3Duration
import com.garrietta.relay.ui.motion.M3Easing
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
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
                            // Shared-element target: the inbox avatar morphs here.
                            ChatAvatar(
                                name = displayName,
                                photoUri = photoUri,
                                modifier = Modifier.motionSharedBounds(
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
                                    "SMS",
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
        animationSpec = tween(
            durationMillis = M3Duration.Short2,
            easing = M3Easing.Standard,
        ),
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
 * M3 Expressive composer: a LARGE tall text-box bubble, a + button that
 * toggles a Koda-style segmented attachment pill, and a LARGE circular send
 * button with a spring pop on send.
 */
@Composable
private fun Composer(
    sending: Boolean,
    onSend: (String) -> Unit,
    onAttachmentSoon: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank() && !sending
    val scope = rememberCoroutineScope()
    val sendPop = remember { Animatable(1f) }

    fun submit() {
        if (!canSend) return
        onSend(text.trim())
        text = ""
        menuExpanded = false
        // Spring pop on send — one of the few sanctioned expressive springs.
        scope.launch {
            sendPop.snapTo(0.82f)
            sendPop.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
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
        // Koda-style segmented attachment pill: expands below the text box
        // when + is tapped. Full row width so every segment breathes.
        AnimatedVisibility(
            visible = menuExpanded,
            enter = expandVertically(
                animationSpec = tween(300, easing = M3Easing.EmphasizedDecelerate),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(200)),
            exit = shrinkVertically(
                animationSpec = tween(200, easing = M3Easing.Standard),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(150)),
            label = "attachPill",
        ) {
            AttachmentSplitPill(
                onPick = {
                    menuExpanded = false
                    onAttachmentSoon()
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            // The + morphs toward × as the pill opens — shape-as-state.
            val rotation by animateFloatAsState(
                targetValue = if (menuExpanded) 45f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "attachToggleRotation",
            )
            FloatingActionButton(
                onClick = { menuExpanded = !menuExpanded },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { rotationZ = rotation },
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = if (menuExpanded) "Close attachments" else "Add attachment",
                )
            }
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
 * Koda-style segmented attachment pill: a horizontal pill container whose
 * segments (Gallery | Camera | GIF | Files) stagger in; the picked segment
 * gets the filled treatment. Picks are stubbed ("coming soon") — no faked
 * functionality.
 */
@Composable
private fun AttachmentSplitPill(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember { listOf("Gallery", "Camera", "GIF", "Files") }
    var picked by remember { mutableStateOf<Int?>(null) }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, label ->
                // Staggered entrance per segment — expressive, not noisy.
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(
                        tween(180, delayMillis = index * 55, easing = M3Easing.Standard),
                    ) + slideInHorizontally(
                        tween(220, delayMillis = index * 55, easing = M3Easing.EmphasizedDecelerate),
                    ) { it / 3 },
                    label = "attachSegment$label",
                    modifier = Modifier.weight(1f),
                ) {
                    val selected = picked == index
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .clickable {
                                picked = index
                                onPick(label)
                            }
                            .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
