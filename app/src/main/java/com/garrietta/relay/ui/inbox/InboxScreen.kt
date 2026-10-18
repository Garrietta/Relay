package com.garrietta.relay.ui.inbox

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedAvatar
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.CloverShape
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.WavyBlobShape
import com.garrietta.relay.ui.theme.WavyBlobShapeCalm
import com.garrietta.relay.ui.theme.WavyBlobShapePressed
import com.garrietta.relay.ui.theme.expressiveShape
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    isDefaultSmsApp: Boolean,
    userMessages: SharedFlow<String>,
    onRequestDefaultSms: () -> Unit,
    onOpenChat: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val conversations by viewModel.conversations.collectAsState()
    val query by viewModel.query.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Scroll-linked collapsing header: the big "Messages" title shrinks all
    // the way down to a compact bar title as the list scrolls. The collapse
    // is driven DIRECTLY by the scroll offset — deliberately no spring.
    // A spring retargeted on every scroll frame can never settle: it
    // oscillates, recomposes the header continuously, and visibly lags the
    // scroll (diagnosed 2026-10-18 as the 120Hz scroll-choppiness culprit).
    // The scroll itself is the animation; 1:1 tracking is correct.
    val collapse by remember {
        derivedStateOf {
            val offset = listState.firstVisibleItemScrollOffset.toFloat()
            val jump = if (listState.firstVisibleItemIndex > 0) 360f else 0f
            ((jump + offset) / 360f).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(Unit) {
        userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // Transparent: the screen lives inside the list-pane container (or
        // the window background in single-pane) — the conversation list is
        // its own rounded container, visually independent of the app bar.
        containerColor = Color.Transparent,
        topBar = {
            CollapsingMessagesHeader(
                collapse = collapse,
                onOpenSettings = onOpenSettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isDefaultSmsApp) {
                    DefaultSmsBanner(onRequestDefaultSms)
                }
                // Search — soft pill, expressive radius.
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search conversations") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(32.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // The conversation LIST is its own rounded container
                // (Google Messages M3E pattern) — solid color, no decoration.
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    if (conversations.isEmpty()) {
                        EmptyInbox()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // Bottom inset clears the floating pill so the
                            // last row is never hidden underneath it.
                            contentPadding = PaddingValues(top = 8.dp, bottom = 108.dp),
                        ) {
                            items(
                                items = conversations,
                                key = { it.threadId },
                                contentType = { "conversation" },
                            ) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    onClick = { onOpenChat(conversation) },
                                    onMarkRead = {
                                        if (conversation.unread) viewModel.markRead(conversation.threadIds)
                                        else viewModel.markUnread(conversation.threadIds)
                                    },
                                    onDelete = { viewModel.delete(conversation.threadIds) },
                                )
                            }
                        }
                    }
                }
            }
            // True floating pill: elevated above the list with margins on all
            // sides — the list scrolls beneath it. Not a docked bar. The
            // densest color in the scheme, per the expressive contrast rule.
            NewConversationPill(
                onClick = onNewConversation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

/**
 * The "Messages" header collapses aggressively on scroll: the 52sp display
 * title shrinks all the way down to a compact 20sp bar title, and the header
 * tints as it collapses. Driven by the spring-smoothed collapse fraction —
 * fluid and bouncy, never rigid.
 */
@Composable
private fun CollapsingMessagesHeader(
    collapse: Float,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = collapse * 0.92f),
        // Explicit content color: at collapse=0 the surface is fully
        // transparent, and contentColorFor() can't resolve a proper tone
        // from a transparent color — dark mode then rendered the title
        // near-invisible (diagnosed 2026-10-18). onBackground is legible
        // in both themes at every collapse fraction.
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = if (collapse > 0.6f) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The status-bar inset is applied ALWAYS, independent of the
                // collapse offset — never 0. Without this, the title + gear
                // render under the status bar in some states (cold start /
                // after pop-back, when the collapse spring is mid-flight;
                // diagnosed 2026-10-18 from a device screenshot). This is the
                // same pattern TopAppBar uses; the Scaffold topBar slot does
                // not inset custom headers for us.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    start = 20.dp,
                    end = 8.dp,
                    top = (12 - 6 * collapse).dp,
                    bottom = (4 + 4 * collapse).dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Messages",
                // Emphasized display type for the eye-stop moment; shrinks
                // with the collapse fraction.
                style = MaterialTheme.typography.displayLarge,
                fontSize = (52 - 32 * collapse).sp,
                lineHeight = (56 - 30 * collapse).sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }
}

/**
 * Floating "New conversation" bar — a plain full-width, slightly rounded
 * rectangle (18dp corners). Real elevation + shadow so it reads as floating
 * above the conversation list. Bouncy press-scale in the unified spring
 * language; the densest color in the scheme, per the expressive contrast
 * rule.
 */
@Composable
private fun NewConversationPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "pillPress",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        // Plain slightly-rounded rectangle — Garrett's call: no flourishes.
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                "New conversation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DefaultSmsBanner(onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onRequest)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Make Relay your texting app",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Set as default to send and receive SMS & MMS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            "Set default",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            // Belt-and-suspenders: the whole banner Row is clickable, and the
            // label itself is an explicit button-role target too.
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    role = Role.Button,
                    onClick = onRequest,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EmptyInbox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Wavy organic emblem — Koda's shape language for empty states.
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(WavyBlobShapeCalm)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "No conversations yet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your texts will land here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // Shape-as-state on press: the row's corners square off while pressed,
    // then bounce back — the unified spring language.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowCorner by animateDpAsState(
        targetValue = if (pressed) 18.dp else 24.dp,
        animationSpec = RelaySprings.bouncy(),
        label = "rowPress",
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            // Container-transform origin: the row morphs into the chat
            // thread via shared bounds (avatar morphs nested inside it).
            .motionSharedBounds(
                SharedElementKeys.conversation(conversation.threadId),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(rowCorner)),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onMarkRead()
                    // Spring back; the read state updates the row itself.
                    scope.launch { dismissState.reset() }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete() // item leaves the list; no reset needed
                }
                SwipeToDismissBoxValue.Settled -> {}
            }
        },
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                else -> Color.Transparent
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.Check
                SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
                else -> null
            }
            val label = when (direction) {
                SwipeToDismissBoxValue.StartToEnd ->
                    if (conversation.unread) "Mark read" else "Mark unread"
                SwipeToDismissBoxValue.EndToStart -> "Delete"
                else -> ""
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                if (icon != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(icon, contentDescription = label)
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        // Flat row inside the list container — the container carries the
        // shape; the row itself stays neutral.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(conversation, pressed)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.unread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTime(conversation.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.unread)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.unread) {
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        conversation.snippet.ifBlank { "No messages" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (conversation.unread) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    TransportChip(conversation.transportLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Avatar(conversation: Conversation, pressed: Boolean) {
    // Shape-as-state: the blob morphs tighter while pressed, with a bouncy
    // press scale — the avatar feels alive under the finger. The avatar is
    // the ONE shared element that reverse-morphs on pop (motionSharedAvatar);
    // message content carries no shared modifiers, preserving the #45
    // text-ghosting fix.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "avatarPress",
    )
    // The avatar sits centered in a uniform-width slot so the column
    // forms a straight edge regardless of each shape's width.
    AvatarSlot {
        ContactAvatar(
            photoUri = conversation.photoUri,
            name = conversation.displayName,
            shapeKey = conversation.address.ifBlank { conversation.displayName },
            modifier = Modifier
                .motionSharedAvatar(
                    SharedElementKeys.avatar(conversation.threadId),
                    shape = WavyBlobShape,
                )
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            size = 56.dp,
            shape = if (pressed) WavyBlobShapePressed else WavyBlobShape,
        )
    }
}

/**
 * Avatar slot: every inbox avatar is centered in a UNIFORM-WIDTH box
 * (72dp — the 56dp height's 1.25x max avatar width plus breathing room),
 * so the avatar column forms a clean straight edge no matter how wide
 * each contact's shape is. Uniform height + locked ratios are unchanged.
 */
@Composable
private fun AvatarSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Transport chip: SMS gets the stadium pill, MMS the clover — two members
 * of the same shape family, so the labels vary without clashing.
 */
@Composable
private fun TransportChip(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(if (label == "MMS") CloverShape else RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

// Hoisted date formatters: formatTime runs per row per composition, and
// allocating two SimpleDateFormats each time is per-frame churn during a
// fling. ThreadLocal keeps them safe wherever the row composes.
private val dayFormatHolder = ThreadLocal.withInitial {
    SimpleDateFormat("yyyyMMdd", Locale.getDefault())
}
private val timeFormatHolder = ThreadLocal.withInitial {
    SimpleDateFormat("h:mm a", Locale.getDefault())
}
private val dateFormatHolder = ThreadLocal.withInitial {
    SimpleDateFormat("MMM d", Locale.getDefault())
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = System.currentTimeMillis()
    val dayFormat = dayFormatHolder.get() ?: return ""
    val sameDay = dayFormat.format(Date(millis)) == dayFormat.format(Date(now))
    return if (sameDay) {
        (timeFormatHolder.get() ?: return "").format(Date(millis))
    } else {
        (dateFormatHolder.get() ?: return "").format(Date(millis))
    }
}
