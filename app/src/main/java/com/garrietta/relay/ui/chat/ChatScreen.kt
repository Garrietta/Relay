package com.garrietta.relay.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.relaySpring
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
    onBack: () -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val seedColor by viewModel.seedColor.collectAsState()
    val photoUri by viewModel.photoUri.collectAsState()
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
                                ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    displayName,
                                    style = MaterialTheme.typography.titleLarge,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
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
                        MessageBubble(message, position)
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

@Composable
private fun MessageBubble(message: Message, position: ItemPosition) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(bubbleShape(message.isOutgoing, position))
                .background(
                    if (message.isOutgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
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

/**
 * Bubbly grouped bubbles: consecutive messages from one sender merge with
 * segmented corners (SegmentedShape's ItemPosition), keeping the tail corner
 * tight on the sender's side for the last/single bubble.
 */
private fun bubbleShape(isOutgoing: Boolean, position: ItemPosition): RoundedCornerShape {
    val large = 28.dp
    val small = 10.dp
    val tail = 4.dp
    return when (position) {
        ItemPosition.SINGLE -> RoundedCornerShape(
            topStart = large, topEnd = large,
            bottomStart = if (isOutgoing) large else tail,
            bottomEnd = if (isOutgoing) tail else large,
        )
        ItemPosition.FIRST -> RoundedCornerShape(
            topStart = large, topEnd = large,
            bottomStart = small, bottomEnd = small,
        )
        ItemPosition.MIDDLE -> RoundedCornerShape(small)
        ItemPosition.LAST -> RoundedCornerShape(
            topStart = small, topEnd = small,
            bottomStart = if (isOutgoing) large else tail,
            bottomEnd = if (isOutgoing) tail else large,
        )
    }
}

/**
 * M3 Expressive composer (per reference): a LARGE tall text-box bubble, an
 * attachment button row UNDERNEATH it, and a LARGE circular send button.
 */
@Composable
private fun Composer(
    sending: Boolean,
    onSend: (String) -> Unit,
    onAttachmentSoon: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank() && !sending

    fun submit() {
        if (canSend) {
            onSend(text.trim())
            text = ""
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Attachment button row underneath the text box.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttachmentSoon) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = "Gallery",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAttachmentSoon) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = "Camera",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onAttachmentSoon) {
                    Text(
                        "GIF",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAttachmentSoon) {
                    Icon(
                        Icons.Filled.Brush,
                        contentDescription = "Draw",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // Large circular send button.
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
                    .clip(CircleShape),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
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

/** Position of a bubble within a sender group (replaces SegmentedShape library). */
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
