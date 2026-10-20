package com.garrietta.relay.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.DoneAll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garrietta.relay.RelayApp
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.model.allRcsIds
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.model.isUnknownSenderGhost
import com.garrietta.relay.data.model.isTransportSystemNotice
import com.garrietta.relay.rcs.proto.RcsMediaAttachment
import com.garrietta.relay.ui.TopSearchPopup
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedAvatar
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.AvatarShapeAssigner
import com.garrietta.relay.ui.theme.ChatBubblePair
import com.garrietta.relay.ui.theme.ClusterMember
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.GroupAvatarCluster
import com.garrietta.relay.ui.theme.SevenShapeLoadingIndicator
import com.garrietta.relay.ui.theme.WavyBlobShape
import com.garrietta.relay.ui.theme.chatBackgroundOption
import com.garrietta.relay.ui.theme.chatBackgroundTones
import com.garrietta.relay.ui.theme.chatBubblePair
import com.garrietta.relay.ui.theme.chatColorwayOption
import com.garrietta.relay.ui.theme.rememberContactChatScheme
import com.garrietta.relay.ui.theme.senderBubbleColors
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    /**
     * The inbox row's group classification (Garrett 2026-10-20): the
     * header honors it even if this screen's own participant list loads
     * thinner than the row's — the surfaces must never disagree.
     */
    knownGroup: Boolean = false,
    onBack: () -> Unit,
    /**
     * Opens the conversation details page (Garrett 2026-10-19): tapping any
     * chat header passes null (conversation mode); tapping a sender's
     * avatar next to their message passes their address (person mode).
     */
    onOpenDetails: (focusAddress: String?) -> Unit,
) {
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val seedColor by viewModel.seedColor.collectAsState()
    val photoUri by viewModel.photoUri.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    // Multi-message forwarding (Garrett 2026-10-20, feature queue 4/7):
    // the selection bar's Forward action opens the conversation picker
    // (the shared TopSearchPopup scaffold); the batch outcome lands as a
    // one-shot snackbar message, same pattern as sendError.
    val forwarding by viewModel.forwarding.collectAsState()
    val forwardResult by viewModel.forwardResult.collectAsState()
    val forwardTargets by viewModel.conversations.collectAsState()
    var showForwardPicker by remember { mutableStateOf(false) }
    val participants by viewModel.participants.collectAsState()
    val others by viewModel.others.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val chatContext = LocalContext.current
    val listState = rememberLazyListState()
    // Fullscreen image pager (Garrett 2026-10-19 21:42): tapping ANY image
    // opens a Google Photos-style pager across the conversation's images.
    // The message id of the tapped image; null = pager closed.
    var viewerMessageId by remember { mutableStateOf<Long?>(null) }
    // Composer collapse-back triggers (Garrett 2026-10-19: the expand worked
    // but "whenever i stop typing" it never collapsed back — nothing ever
    // cleared focus). Focus loss is the single driver of the collapse
    // animation, so every end-of-typing path must clear focus:
    //  (a) tapping anywhere outside the text field (the tap detector below),
    //  (b) dismissing the keyboard (IME hide),
    //  (c) sending a message (submit()).
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible) focusManager.clearFocus()
    }
    // Visible-chat tracking for notification suppression: while this chat
    // is on screen, MainActivity's collector skips its incoming messages.
    // Also the scroll-position memory (Garrett 2026-10-19): on exit we save
    // the first visible message id + offset; on open we restore it (below).
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose {
            viewModel.setVisible(false)
            val current = messages
            if (current.isNotEmpty()) {
                val idx = listState.firstVisibleItemIndex.coerceIn(0, current.size - 1)
                viewModel.saveScrollPosition(
                    current[idx].id,
                    listState.firstVisibleItemScrollOffset,
                )
            }
        }
    }
    // Header mode from the OTHER-participant count (self excluded and
    // deduped — the server's participant list can include him twice),
    // OR the inbox row's own group classification: a conversation the
    // inbox showed as a group opens with the group header even while
    // its participant list is still loading (or loads thinner than the
    // row's — Garrett 2026-10-20, "No Dad"). Per-sender bubble theming
    // applies to groups.
    val headerMode = if (others.size <= 1 && !knownGroup) HeaderMode.OneToOne else HeaderMode.Group
    val isGroup = headerMode == HeaderMode.Group
    // Stable per-conversation key for the group avatar pattern: the same
    // group always gets the same cluster arrangement (spiral/grid/zigzag).
    val patternKey = rcsConversationId ?: "tel:$threadId"
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Per-chat theme (Garrett 2026-10-20, feature queue 3/7): LOCAL
    // presentation only — a background gradient + bubble colorway for
    // THIS conversation, picked in Chat Details and stored in
    // ChatThemeStore under the same conversation key as the mute.
    // A null option renders EXACTLY today's look; nothing here touches
    // the wire, the inbox, or other conversations.
    val themeStore = remember { (chatContext.applicationContext as RelayApp).chatThemeStore }
    // Sticker recents (Garrett 2026-10-20, feature queue 5/7): the
    // composer's smiley panel surfaces last-used stickers first.
    val stickerStore = remember { (chatContext.applicationContext as RelayApp).stickerRecentsStore }
    val recentStickerIds by stickerStore.recentIds.collectAsState()
    val recentStickerDefs = remember(recentStickerIds) {
        recentStickerIds.mapNotNull { StickerPack.byId(it) }
    }
    val chatThemes by themeStore.themes.collectAsState()
    val themeKey = remember(rcsConversationId, threadId) {
        CategoryStore.conversationKey(rcsConversationId, threadId)
    }
    val chatTheme = chatThemes[themeKey]
    val bgOption = chatBackgroundOption(chatTheme?.backgroundId)
    val bubblePair = remember(chatTheme?.colorwayId, darkTheme) {
        chatColorwayOption(chatTheme?.colorwayId)?.let { chatBubblePair(it.seed, darkTheme) }
    }
    // Wallpaper tones crossfade on RelaySprings when the pick (or the
    // light/dark mode) changes — no hard cut. Unthemed, both stops are
    // the flat surfaceContainerLow and the gradient IS today's
    // background; the scaffold/header still read the scheme directly
    // then, so the unthemed path is untouched.
    val themedSurfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val bgTop by animateColorAsState(
        targetValue = bgOption?.let { chatBackgroundTones(it.seed, darkTheme).first }
            ?: themedSurfaceLow,
        animationSpec = RelaySprings.gentle(),
        label = "chatBgTop",
    )
    val bgBottom by animateColorAsState(
        targetValue = bgOption?.let { chatBackgroundTones(it.seed, darkTheme).second }
            ?: themedSurfaceLow,
        animationSpec = RelaySprings.gentle(),
        label = "chatBgBottom",
    )
    val bgBrush = remember(bgTop, bgBottom) {
        Brush.verticalGradient(listOf(bgTop, bgBottom))
    }
    // Scroll-restore bookkeeping (declared here, above the header state:
    // the header's initial collapse derives from the saved position, and
    // the collapse driver below stays frozen until the restore lands).
    // See the restore effect further down for the full rationale.
    var scrollRestored by remember(viewModel) { mutableStateOf(false) }
    var restoreAttempts by remember(viewModel) { mutableStateOf(0) }
    // Sticky collapse with hysteresis, gated on real scrollability. The old
    // "any offset > 0" flapped: a short thread's overscroll bounce would
    // start the minimize and snap it straight back to full size. Now the
    // header only minimizes past a threshold when the list can actually
    // scroll, and stays minimized until the user returns to the very top.
    // Boolean-driven (never a spring retargeted every scroll frame — the
    // #102 lesson); RelaySprings animate the change.
    //
    // INITIAL STATE (Garrett 2026-10-20): a chat reopened at a restored
    // SCROLLED position starts with the header ALREADY collapsed — the
    // state that scroll position implies. The old code always started
    // expanded and let the collapse driver flip it once the restore
    // landed: mid-entrance-transition the header crossfaded to its other
    // layout, the avatar carrying the shared-element morph vanished from
    // its registered branch (the collapsed layouts register no morph
    // target), and the morph drew the photo stranded mid-screen. With
    // the header in its final state from the first frame, a scrolled
    // chat simply has no morph target on its side — the entrance/exit
    // degrade cleanly to the surface slide, the exact reverse both
    // ways — while a chat reopened at the newest message starts
    // expanded and morphs exactly as before. A saved position IS the
    // newest message at offset 0 exactly when the chat was left at the
    // bottom; anything else means it reopens scrolled.
    var headerCollapsed by remember(viewModel) {
        val saved = viewModel.savedScrollPosition()
        val newestId = messages.firstOrNull()?.id
        val opensAtNewest = saved != null && newestId != null &&
            saved.messageId == newestId && saved.scrollOffset == 0
        mutableStateOf(saved != null && !opensAtNewest)
    }
    /** Message whose reaction badge was tapped — shows the who-reacted dialog. */
    var reactionDetail by remember { mutableStateOf<Message?>(null) }
    val collapseThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        scrollRestored,
    ) {
        // Frozen until the saved scroll position is restored: before
        // that, the list still reports (0, 0) — "not scrolled" — which
        // would flip the header expanded, only for the restore to flip
        // it back, mid-transition (the stranded-avatar bug above).
        // headerCollapsed was initialized FROM the saved position, so
        // it is already correct while this driver is frozen.
        if (!scrollRestored) return@LaunchedEffect
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
    LaunchedEffect(forwardResult) {
        forwardResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearForwardResult()
        }
    }
    // Scroll restore (Garrett 2026-10-19: reopening a chat must NOT jump
    // back to the top — it restores the exact position where he left off).
    // The old LaunchedEffect(messages.size) slammed scrollToItem(0) on EVERY
    // size change (cache → 50 → deep-fill 300, plus live arrivals), which is
    // what yanked him. Now: restore by saved MESSAGE ID (stable while the
    // list grows underneath — older messages append at higher indices, so
    // the saved message keeps its identity). The effect retries across the
    // load sequence until the saved message appears (cache → network →
    // deep fill); after a few misses it falls back to newest, and once
    // restored it never touches scroll again — live arrivals in the
    // reverseLayout list settle at the bottom on their own.
    // (scrollRestored / restoreAttempts are declared above the header
    // state — the header's initial collapse derives from the position
    // this effect restores.)
    LaunchedEffect(messages) {
        if (messages.isEmpty() || scrollRestored) return@LaunchedEffect
        val saved = viewModel.savedScrollPosition()
        if (saved == null) {
            listState.scrollToItem(0)
            scrollRestored = true
            return@LaunchedEffect
        }
        val index = messages.indexOfFirst { it.id == saved.messageId }
        if (index >= 0) {
            listState.scrollToItem(index, saved.scrollOffset)
            scrollRestored = true
        } else {
            restoreAttempts++
            if (restoreAttempts >= 4) {
                // The message is gone (deleted/expired) — newest it is.
                listState.scrollToItem(0)
                scrollRestored = true
            }
            // Otherwise a deeper load may still bring it; wait for the
            // next emission without touching scroll.
        }
    }

    MaterialTheme(colorScheme = chatScheme) {
        // Inside the seeded scheme: the thread background the sender-name
        // tones are contrast-checked against — the theme's bottom tone
        // when a background is picked, the scheme surface otherwise.
        val threadBackground = if (bgOption != null) bgBottom
            else MaterialTheme.colorScheme.surfaceContainerLow
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
                    // The header takes the wallpaper's TOP tone flat:
                    // the thread container owns the gradient starting
                    // from that same tone, so the seam is invisible.
                    backgroundColor = if (bgOption != null) bgTop else null,
                    onBack = onBack,
                    onOpenDetails = onOpenDetails,
                )
            },
            // Seamless with the thread container below — the header
            // background and the list background are one continuous tone.
            containerColor = if (bgOption != null) bgBottom
                else MaterialTheme.colorScheme.surfaceContainerLow,
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
                    // Chat wallpaper (Garrett 2026-10-20): the picked
                    // background gradient fills the thread; unthemed
                    // chats keep the flat scheme surface, byte-for-byte
                    // today's look.
                    .then(
                        if (bgOption != null) Modifier.background(bgBrush)
                        else Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                    .imePadding()
                    // Tap-outside clears the text field's focus: message
                    // bubbles consume their own taps (child clickables win),
                    // so this only fires on empty areas / the composer shell.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    },
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
                            // Remote typing indicator (Garrett 2026-10-19):
                            // animated dots in a received-style bubble at the
                            // bottom of the thread. reverseLayout puts this
                            // head item visually below the newest message.
                            if (isTyping) {
                                item(key = "typing-indicator", contentType = "typing") {
                                    TypingIndicatorBubble()
                                }
                            }
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
                                    selected = message.id in selectedIds,
                                    selectionActive = selectedIds.isNotEmpty(),
                                    onToggleSelect = { viewModel.toggleSelect(message.id) },
                                    // Images get a UNIFORM preview width
                                    // matching Google Messages (Garrett
                                    // 2026-10-19 evening: measured 0.676 of
                                    // screen width on his GM screenshot —
                                    // between the old small size and #125's
                                    // 0.92 overshoot). Every image bubble is
                                    // the same width; height follows aspect
                                    // ratio. Text bubbles keep the 0.8 cap.
                                    maxBubbleWidth = maxWidth * 0.66f,
                                    onShowReactions = { reactionDetail = it },
                                    // Tapping an image opens the conversation
                                    // image pager (Garrett 2026-10-19 21:42),
                                    // landing on this image's page.
                                    onOpenImage = { viewerMessageId = it.id },
                                    onReact = { emoji ->
                                        viewModel.sendReaction(message, emoji)
                                    },
                                    // Group mode: the media renders inside
                                    // the sender column so its leading edge
                                    // matches group text bubbles exactly
                                    // (Garrett 2026-10-19).
                                    sender = if (isGroup && !message.isOutgoing)
                                        viewModel.senderFor(message.address) else null,
                                    isGroup = isGroup,
                                    bubblePair = bubblePair,
                                    onAvatarClick = { sender ->
                                        onOpenDetails(sender.address)
                                    },
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
                                bubblePair = bubblePair,
                                threadBackground = threadBackground,
                                darkTheme = darkTheme,
                                maxBubbleWidth = maxBubbleWidth,
                                onAvatarClick = { sender -> onOpenDetails(sender.address) },
                                onShowReactions = { reactionDetail = it },
                                onReact = { emoji ->
                                    viewModel.sendReaction(message, emoji)
                                },
                            )
                        }
                        }
                    }
                    // Selection action bar (Garrett 2026-10-20, feature
                    // queue 4/7: multi-message forwarding): slides in
                    // above the composer while any bubble is selected —
                    // the count, a clear (X), and Forward, which opens
                    // the conversation picker. Media bubbles select too
                    // (long-press menu → Select), so attachments can be
                    // forwarded, not just text.
                    AnimatedVisibility(
                        visible = selectedIds.isNotEmpty(),
                        enter = slideInVertically(RelaySprings.bouncy()) { it } +
                            fadeIn(tween(120)),
                        exit = slideOutVertically(tween(160)) { it } +
                            fadeOut(tween(120)),
                    ) {
                        SelectionBar(
                            count = selectedIds.size,
                            forwarding = forwarding,
                            onClear = { viewModel.clearSelection() },
                            onForward = { showForwardPicker = true },
                        )
                    }
                    Composer(
                        sending = sending,
                        onSend = { viewModel.send(recipient, it) },
                        onAttachmentSoon = {
                            // Voice messages stay stubbed (Garrett 2026-10-19:
                            // the four picker options send for real now).
                            scope.launch {
                                snackbarHostState.showSnackbar("Voice messages are coming soon")
                            }
                        },
                        onAttach = { uri ->
                            viewModel.sendAttachment(uri, chatContext.contentResolver)
                        },
                        onSticker = { def ->
                            viewModel.sendSticker(def)
                            stickerStore.record(def.id)
                        },
                        recentStickers = recentStickerDefs,
                    )
                }
            }
        }
    }

    // Fullscreen image pager (Garrett 2026-10-19 21:40/21:41/21:42):
    // swipe left/right across the conversation's images, each page fills
    // the screen edge-to-edge with its own pinch-zoom/pan. Opens on the
    // tapped image's page; neighbors preload (beyondViewportPageCount).
    val viewerId = viewerMessageId
    if (viewerId != null) {
        val imageMessages = remember(messages) {
            messages.filter { it.media?.isImage == true }.sortedBy { it.date }
        }
        FullscreenImagePager(
            images = imageMessages,
            initialIndex = imageMessages.indexOfFirst { it.id == viewerId }
                .coerceAtLeast(0),
            loadMedia = { att -> viewModel.mediaBytes(att) },
            onDismiss = { viewerMessageId = null },
        )
    }

    // Forward picker (Garrett 2026-10-20, feature queue 4/7): the shared
    // TopSearchPopup card listing conversations; the current conversation
    // is excluded (forwarding into the chat you're reading is a no-op),
    // and merged duplicates match on ANY of their server ids.
    if (showForwardPicker) {
        ForwardPickerPopup(
            conversations = forwardTargets,
            currentRcsConversationId = rcsConversationId,
            currentThreadId = threadId,
            currentThreadIds = threadIds,
            onDismiss = { showForwardPicker = false },
            onPick = { target ->
                showForwardPicker = false
                viewModel.forwardSelected(target)
            },
        )
    }

    // Who-reacted dialog (Garrett 2026-10-19): tapping an inline reaction
    // badge lists every reactor with their emoji.
    reactionDetail?.let { reacted ->
        AlertDialog(
            onDismissRequest = { reactionDetail = null },
            title = { Text("Reactions") },
            text = {
                Column {
                    reacted.reactions.forEach { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Text(r.emoji, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                r.reactorName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reactionDetail = null }) { Text("Close") }
            },
        )
    }
}

/**
 * Selection action bar (Garrett 2026-10-20, feature queue 4/7): shown
 * above the composer while any message is selected — clear (X), the
 * selected count, and the Forward action that opens the conversation
 * picker. While a forward batch is sending, Forward disables and reads
 * "Forwarding…" so the batch can't be double-fired.
 */
@Composable
private fun SelectionBar(
    count: Int,
    forwarding: Boolean,
    onClear: () -> Unit,
    onForward: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear selection",
                )
            }
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onForward,
                enabled = count > 0 && !forwarding,
            ) {
                Icon(
                    Icons.Filled.Forward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (forwarding) "Forwarding…" else "Forward")
            }
        }
    }
}

/**
 * Forward target picker (Garrett 2026-10-20, feature queue 4/7): the
 * shared [TopSearchPopup] scaffold — the same top-center bouncy card as
 * New message and inbox Search — listing the inbox's conversations with
 * live search (name, address, or snippet). The conversation being read
 * is excluded: an RCS row matches on ANY of its duplicate-merged server
 * ids, a Telephony row on any of its merged thread ids. Tapping a row
 * runs the popup's animated exit first, then fires [onPick].
 */
@Composable
private fun ForwardPickerPopup(
    conversations: List<Conversation>,
    currentRcsConversationId: String?,
    currentThreadId: Long,
    currentThreadIds: List<Long>,
    onDismiss: () -> Unit,
    onPick: (Conversation) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val targets = remember(
        conversations, query, currentRcsConversationId, currentThreadId, currentThreadIds,
    ) {
        val q = query.trim()
        conversations.filter { conv ->
            val isCurrent =
                (currentRcsConversationId != null &&
                    currentRcsConversationId in conv.allRcsIds()) ||
                    conv.threadId == currentThreadId ||
                    conv.threadIds.any { it in currentThreadIds }
            !isCurrent
        }.filter { conv ->
            q.isEmpty() ||
                conv.displayName.contains(q, ignoreCase = true) ||
                conv.address.contains(q, ignoreCase = true) ||
                conv.snippet.contains(q, ignoreCase = true)
        }
    }
    TopSearchPopup(
        title = "Forward to",
        query = query,
        onQueryChange = { query = it },
        placeholder = "Search conversations",
        onDismiss = onDismiss,
    ) { requestExit ->
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
        ) {
            items(targets, key = { it.threadId }) { conv ->
                ForwardTargetRow(
                    conversation = conv,
                    onClick = { requestExit { onPick(conv) } },
                )
            }
            if (targets.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) "No other conversations"
                        else "No conversations match \"${query.trim()}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One forward target row: the conversation's avatar (group cluster for
 * groups, per-contact shape otherwise), its name, and its latest
 * snippet — the inbox search row's content without list machinery.
 */
@Composable
private fun ForwardTargetRow(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conversation.participants.size > 1) {
            GroupAvatarCluster(
                members = conversation.participants
                    .filterNot { it.isUnknownSenderGhost() }
                    .map {
                        ClusterMember(
                            photoUri = it.photoUri,
                            name = it.displayName.ifBlank { it.address },
                            address = it.address,
                        )
                    },
                conversationKey = conversation.rcsConversationId
                    ?: "tel:${conversation.threadId}",
                boxSize = 48.dp,
            )
        } else {
            ContactAvatar(
                photoUri = conversation.photoUri,
                name = conversation.displayName,
                size = 48.dp,
                shapeKey = conversation.address.ifBlank { conversation.displayName },
                textStyle = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                conversation.snippet.ifBlank { "No messages" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Remote typing indicator (Garrett 2026-10-19): three animated dots in a
 * received-style bubble. The paired web protocol's typingEvent carries no
 * sender id, so groups show this generic indicator. Bouncy pop-in/out via
 * the surrounding AnimatedVisibility in the message list.
 */
@Composable
private fun TypingIndicatorBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i -> TypingDot(phaseDelayMs = i * 160) }
            }
        }
    }
}

@Composable
private fun TypingDot(phaseDelayMs: Int) {
    val infinite = rememberInfiniteTransition(label = "typingDot")
    val dotSpec = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 450, delayMillis = phaseDelayMs),
        repeatMode = RepeatMode.Reverse,
    )
    val scale by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = dotSpec,
        label = "typingDotScale",
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = dotSpec,
        label = "typingDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/**
 * Google Messages-style delivery ticks for outgoing bubbles: SENT = single
 * check, DELIVERED = double check, READ = double check filled blue.
 * SENDING/NONE show nothing (matches Google Messages).
 */
@Composable
private fun DeliveryTicks(
    status: DeliveryStatus,
    modifier: Modifier = Modifier,
) {
    val (icon, tint) = when (status) {
        DeliveryStatus.SENT -> Icons.Filled.Done to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryStatus.DELIVERED -> Icons.Filled.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryStatus.READ -> Icons.Filled.DoneAll to MaterialTheme.colorScheme.primary
        else -> null to Color.Unspecified
    }
    if (icon != null) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = when (status) {
                    DeliveryStatus.READ -> "Read"
                    DeliveryStatus.DELIVERED -> "Delivered"
                    else -> "Sent"
                },
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
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
    /** Picked chat wallpaper's top tone; the header tints to match the
     * thread's gradient start so the two read as one surface.
     * Null = default flat scheme surface. */
    backgroundColor: Color? = null,
    onBack: () -> Unit,
    onOpenDetails: (focusAddress: String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                backgroundColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        when (mode) {
            HeaderMode.OneToOne -> {
                // Self-chat (no others): show self, once.
                val peer = others.firstOrNull() ?: participants.firstOrNull()
                val peerName = when {
                    peer == null -> displayName
                    // UNKNOWN_SENDER! ghost: show the address, never the
                    // literal string (display layer only).
                    peer.isUnknownSenderGhost() -> peer.address.ifBlank { displayName }
                    else -> peer.displayName.ifBlank { peer.address }.ifBlank { displayName }
                }
                OneToOneHeader(
                    name = peerName,
                    photoUri = peer?.photoUri ?: photoUri,
                    shapeKey = peer?.address ?: recipient,
                    threadId = threadId,
                    transportLabel = transportLabel,
                    scrolled = scrolled,
                    onBack = onBack,
                    // Tapping the header opens details for the peer.
                    onOpenDetails = { onOpenDetails(peer?.address) },
                )
            }
            HeaderMode.Group -> GroupHeader(
                others = others,
                transportLabel = transportLabel,
                scrolled = scrolled,
                patternKey = patternKey,
                threadId = threadId,
                onBack = onBack,
                // Tapping the group header opens details in conversation
                // (member list) mode.
                onOpenDetails = { onOpenDetails(null) },
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
    onOpenDetails: () -> Unit,
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
                    // Header tap opens the details page (Garrett 2026-10-19).
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenDetails,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenDetails,
                        ),
                ) {
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
                    // The details tap region below covers the full width —
                    // the back button must sit above it in z-order or its
                    // touches get swallowed (Garrett 2026-10-19 evening).
                    modifier = Modifier.align(Alignment.TopStart).zIndex(1f),
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 10.dp)
                        // Header tap opens the details page (Garrett
                        // 2026-10-19); the back button above stays separate.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenDetails,
                        ),
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
 * drop away and only the names remain. For NAVIGATION the cluster carries
 * the same shared-avatar morph as a 1:1 photo (in on open, reverse on
 * exit) — one movement language for every chat.
 */
@Composable
private fun GroupHeader(
    others: List<Participant>,
    transportLabel: String,
    scrolled: Boolean,
    patternKey: String,
    threadId: Long,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    // UNKNOWN_SENDER! ghosts are hidden from the name list AND the avatar
    // stack (Garrett 2026-10-19) — display layer only, data untouched.
    val visibleOthers = remember(others) { others.filterNot { it.isUnknownSenderGhost() } }
    // Defense in depth: a participant whose name is blank or the literal
    // "Unknown" renders as its number, never the word "Unknown".
    val names = remember(visibleOthers) {
        visibleOthers.map {
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
            // Header tap opens the details page (Garrett 2026-10-19).
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenDetails,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatarCluster(
                    members = visibleOthers.map {
                        ClusterMember(
                            photoUri = it.photoUri,
                            name = it.displayName.ifBlank { it.address },
                            address = it.address,
                        )
                    },
                    conversationKey = patternKey,
                    boxSize = 76.dp,
                    // Morph target matching the inbox group row's
                    // cluster (Garrett 2026-10-20: same picture movement
                    // for group chats as for 1:1, in both directions).
                    modifier = Modifier.motionSharedAvatar(
                        SharedElementKeys.avatar(threadId),
                    ),
                )
                Spacer(Modifier.width(10.dp))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                // Header tap opens the details page (Garrett 2026-10-19).
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenDetails,
                ),
        ) {
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
    /** Tapping the reaction badge lists who reacted (Garrett 2026-10-19). */
    onShowReactions: (Message) -> Unit = {},
    /** Tapping an image opens the conversation image pager (Garrett
     * 2026-10-19 21:42). */
    onOpenImage: (Message) -> Unit = {},
    /** Long-press reaction send (Garrett 2026-10-19: reactions both ways). */
    onReact: (String) -> Unit = {},
    /** Group chats: the sender, so group-incoming media renders inside
     * the same sender column as group-incoming text bubbles (identical
     * leading edge). Null outside groups. */
    sender: Participant? = null,
    isGroup: Boolean = false,
    /** Picked per-chat bubble colorway (Garrett 2026-10-20); tints the
     * caption bubble under the media. Null = scheme colors. */
    bubblePair: ChatBubblePair? = null,
    /** Tapping the sender's avatar opens their details page. */
    onAvatarClick: (Participant) -> Unit = {},
    /** Multi-select (Garrett 2026-10-20, forwarding): media messages are
     * selectable exactly like text bubbles — the long-press menu's Select
     * enters selection mode, taps toggle while it's active, and the
     * selected media carries a tint overlay. */
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    var bytes by remember(message.id) { mutableStateOf<ByteArray?>(null) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    var showReactMenu by remember(message.id) { mutableStateOf(false) }
    // Bumped on tap-to-retry so the loader below re-runs with a fresh key.
    var retryNonce by remember(message.id) { mutableStateOf(0) }
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()

    LaunchedEffect(message.id, retryNonce) {
        if (bytes == null && error == null) {
            try {
                bytes = loadMedia(media)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // Whether these bytes are the full download (mediaId present) or a
    // thumbnail-only payload (inline data, nothing to download). The
    // fullscreen viewer must NEVER upscale thumbnail bytes — a small
    // thumbnail blown up to screen width is exactly the "corrupt" look
    // Garrett filmed (2026-10-19).
    val isFullSize = media.mediaId.isNotEmpty()
    val loadedBytes = bytes
    val loadedBounds = if (loadedBytes != null && media.isImage) {
        remember(message.id) { decodeImageBounds(loadedBytes) }
    } else null
    // Sticker rendering (Garrett 2026-10-20, feature queue 5/7): a
    // Relay-tagged sticker send — or any small transparent PNG/WebP —
    // renders WITHOUT the bubble background at sticker size instead of
    // the uniform photo treatment (see Stickers.kt for the rule).
    val stickerLook = if (loadedBytes != null && media.isImage) {
        remember(message.id, loadedBytes) {
            looksLikeSticker(media, loadedBounds, loadedBytes)
        }
    } else false

    // The media + caption block, emitted into whichever layout hosts it
    // below: the plain full-width column, or — for group-incoming
    // messages — the sender column, so the media's leading edge lines
    // up EXACTLY with group-incoming text bubbles (Garrett 2026-10-19:
    // "Photos are misaligned" — media used to flush to the list edge
    // while text bubbles sit 36dp in: 28dp avatar + 8dp gap. Same fix
    // the link preview got in #130.) Outgoing media already shares the
    // outgoing text bubbles' trailing edge.
    @Composable
    fun MediaContent() {
        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    // Stickers float on the thread: no bubble
                    // background, no rounded-rect clip.
                    .then(
                        if (stickerLook) Modifier
                        else Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                .combinedClickable(
                    onClick = {
                        if (selectionActive) {
                            // Selection mode: a tap toggles this message,
                            // exactly like text bubbles (Garrett 2026-10-20).
                            onToggleSelect()
                        } else if (error != null) {
                            // Tap-to-retry (Garrett 2026-10-19 evening):
                            // clearing the error and bumping retryNonce
                            // re-arms the LaunchedEffect above, which runs a
                            // fresh download (the transport itself retries
                            // truncations first).
                            error = null
                            retryNonce++
                        } else if (bytes != null && media.isImage) {
                            // Opens the conversation image pager on this
                            // image's page (Garrett 2026-10-19 21:42).
                            onOpenImage(message)
                        } else if (bytes != null) {
                            // Non-image attachment: open in the system
                            // viewer (Garrett 2026-10-19).
                            val fileBytes = bytes!!
                            ioScope.launch(Dispatchers.IO) {
                                val failure = openAttachmentInSystemViewer(
                                    context, media, fileBytes,
                                )
                                if (failure != null) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context, failure, Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        }
                    },
                    onLongClick = { showReactMenu = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val b = bytes
            val err = error
            when {
                err != null -> Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Single prefix: the transport's reasons already start
                    // with "media unavailable:" — strip it so the UI prefix
                    // doesn't double up ("Media unavailable: media
                    // unavailable: ...", Garrett 2026-10-19 evening).
                    val reason = err.replaceFirst(
                        Regex("^media unavailable:\\s*", RegexOption.IGNORE_CASE),
                        "",
                    )
                    Text(
                        text = "Media unavailable: $reason",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Tap to retry",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                b != null && media.isImage -> {
                    // Uniform preview (Garrett 2026-10-19 evening): EVERY
                    // image bubble renders at the same fixed width, whatever
                    // its native dimensions — height follows the aspect ratio
                    // from the decoded bounds. (Supersedes the old
                    // natural-size rule: he wants one consistent preview
                    // size, Google Messages style.)
                    val bounds = loadedBounds
                    // Render-side sanity (deep-dive 2026-10-19): the transport
                    // gates should already have rejected these, but a header
                    // claiming sub-24px dimensions renders as a glitch strip
                    // in the bubble — refuse it here too, never render it.
                    if (bounds == null || bounds.first < 24 || bounds.second < 24) {
                        Text(
                            text = "Media unavailable: undecodable image",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else if (stickerLook) {
                        // Sticker: natural size with the long edge
                        // capped — never the 0.66-width photo treatment,
                        // never upscaled past its pixels.
                        val density = LocalDensity.current
                        val naturalW = with(density) { bounds.first.toDp() }
                        val naturalH = with(density) { bounds.second.toDp() }
                        val (w, h) = stickerDisplaySize(naturalW, naturalH)
                        AsyncImage(
                            model = b,
                            contentDescription = "Sticker",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(w, h),
                        )
                    } else {
                        val density = LocalDensity.current
                        val naturalW = with(density) { bounds.first.toDp() }
                        val naturalH = with(density) { bounds.second.toDp() }
                        // Uniform width for every image; height from aspect.
                        val w = maxBubbleWidth
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
                    // Non-image file: labeled tile. Tap opens it in the
                    // system viewer (Garrett 2026-10-19).
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
                            Text(
                                text = "Tap to open",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
            // Upload progress (Garrett 2026-10-19: attachment sending):
            // the optimistic bubble carries the local bytes and a live
            // progress scrim until the upload finishes.
            val progress = message.uploadProgress
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Uploading ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
            // Selected state (Garrett 2026-10-20): a translucent primary
            // wash over the media so a selected attachment reads exactly
            // like a selected (squared-off, tinted) text bubble.
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        ),
                )
            }
            ReactionBadgeOverlay(message, onShowReactions)
        }
        if (message.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            // Caption as a compact bubble under the media.
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (message.isOutgoing) bubblePair?.outgoing
                            ?: MaterialTheme.colorScheme.primaryContainer
                        else bubblePair?.incoming
                            ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                // Caption text is selectable too (Garrett 2026-10-20:
                // partial text selection); the caption bubble carries no
                // gestures of its own, so nothing competes here.
                SelectionContainer {
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.isOutgoing) bubblePair?.onOutgoing
                            ?: MaterialTheme.colorScheme.onPrimaryContainer
                        else bubblePair?.onIncoming
                            ?: MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        // Smart-cast: the null check is inline so `sender` is non-null.
        if (isGroup && !message.isOutgoing && sender != null) {
            // Group-incoming: same sender column as group-incoming text
            // bubbles (28dp avatar + 8dp gap) — the media's leading edge
            // matches the bubbles' exactly.
            Row(verticalAlignment = Alignment.Top) {
                ContactAvatar(
                    photoUri = sender.photoUri,
                    name = sender.displayName.ifBlank { sender.address },
                    size = 28.dp,
                    shapeKey = sender.address,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAvatarClick(sender) },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = if (sender.isUnknownSenderGhost()) sender.address
                        else sender.displayName.ifBlank { sender.address },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    MediaContent()
                }
            }
        } else {
            MediaContent()
        }
    }

    // Long-press reaction menu (Garrett 2026-10-19: reactions both ways).
    // Select joins the menu (Garrett 2026-10-20): media messages enter
    // multi-select exactly like text bubbles, so they can be forwarded.
    ReactionPickerMenu(
        expanded = showReactMenu,
        onDismiss = { showReactMenu = false },
        onReact = onReact,
        onSelect = onToggleSelect,
    )
}

/**
 * Fullscreen image viewer (Garrett 2026-10-19): pinch-to-zoom, pan while
 * zoomed, double-tap to toggle zoom — Google Photos / Messages style.
 * The image FILLS the screen at rest (fit-to-screen, edge to edge — no
 * floating margin box), and zooming expands across the whole display:
 * the transform lives on a full-screen surface with NO clip, so the
 * zoomed image is never cropped to the rest frame. [onZoomChanged] lets
 * the host pager disable swipe-to-change while a page is zoomed (pan
 * wins over page, standard behavior).
 */
@Composable
private fun ZoomableFullscreenImage(
    bytes: ByteArray,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onZoomChanged: (Boolean) -> Unit = {},
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (newScale <= 1f) Offset.Zero else offset + panChange * newScale
        scale = newScale
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .transformable(transformState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.5f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = bytes,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/**
 * The conversation image pager (Garrett 2026-10-19 21:42): swipe
 * left/right through the conversation's images, Google Photos style.
 * The dialog uses usePlatformDefaultWidth = false so the viewer is the
 * FULL screen (the old platform-default-width dialog was the small
 * floating rectangle Garrett filmed, and its window bounds were what
 * clipped the zoom). Each page is the same fill-screen + zoom/pan
 * viewer; when the current page is zoomed the pager locks so the pan
 * takes the gesture first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenImagePager(
    images: List<Message>,
    initialIndex: Int,
    loadMedia: suspend (RcsMediaAttachment) -> ByteArray,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }
        var pageZoomed by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !pageZoomed,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val msg = images[page]
                var bytes by remember(msg.id) { mutableStateOf<ByteArray?>(null) }
                var loadFailed by remember(msg.id) { mutableStateOf(false) }
                LaunchedEffect(msg.id) {
                    val att = msg.media
                    bytes = if (att == null) null
                        else runCatching { loadMedia(att) }.getOrNull()
                    if (bytes == null) loadFailed = true
                }
                val b = bytes
                when {
                    b != null -> ZoomableFullscreenImage(
                        bytes = b,
                        contentDescription = "Shared image fullscreen",
                        onZoomChanged = { zoomed ->
                            // Only the visible page drives the pager lock.
                            if (page == pagerState.currentPage) pageZoomed = zoomed
                        },
                    )
                    loadFailed -> Text(
                        text = "Media unavailable",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    else -> SevenShapeLoadingIndicator(
                        diameter = 40.dp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Long-press reaction picker (Garrett 2026-10-19: reactions both ways):
 * the standard reaction set plus Select (multi-select stays one long-press
 * away — the menu replaces the bare selection toggle on text bubbles).
 */
@Composable
private fun ReactionPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onSelect: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Row {
                    ReactionEmojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .clickable {
                                    onReact(emoji)
                                    onDismiss()
                                }
                                .padding(6.dp),
                        )
                    }
                }
            },
            onClick = {},
        )
        onSelect?.let { select ->
            DropdownMenuItem(
                text = { Text("Select") },
                onClick = {
                    select()
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Attachment viewing (Garrett 2026-10-19): stages the attachment bytes in
 * the shared cache dir and opens them with the system viewer (ACTION_VIEW
 * through FileProvider, no hardcoded app — PDFs, docs, audio, video open
 * in his default apps). Returns null on success, or a user-facing error.
 */
private fun openAttachmentInSystemViewer(
    context: Context,
    media: RcsMediaAttachment,
    bytes: ByteArray,
): String? {
    return try {
        val ext = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(media.mimeType)?.ifBlank { null } ?: "bin"
        val safeId = media.mediaId.ifBlank { media.hashCode().toString() }
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "file" }
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, "attachment_${safeId}.$ext")
        if (!file.exists()) file.writeBytes(bytes)
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, media.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open attachment").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        null
    } catch (e: android.content.ActivityNotFoundException) {
        "No app installed can open this file type (${media.mimeType.ifBlank { "unknown" }})"
    } catch (e: Exception) {
        "Couldn't open attachment: ${e.message ?: e.javaClass.simpleName}"
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
    /** Picked per-chat bubble colorway (Garrett 2026-10-20). Null =
     * default: outgoing = scheme primaryContainer, 1:1 incoming =
     * surfaceContainerHigh, group incoming = per-sender colors. */
    bubblePair: ChatBubblePair? = null,
    threadBackground: Color = Color.Transparent,
    darkTheme: Boolean = false,
    /** Received bubbles keep an empty right margin; sent an empty left one. */
    maxBubbleWidth: Dp = Dp.Unspecified,
    /** Tapping the sender's avatar opens their details page (Garrett 2026-10-19). */
    onAvatarClick: (Participant) -> Unit = {},
    /** Tapping the reaction badge lists who reacted (Garrett 2026-10-19). */
    onShowReactions: (Message) -> Unit = {},
    /** Long-press reaction send (Garrett 2026-10-19: reactions both ways). */
    onReact: (String) -> Unit = {},
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
        message.isOutgoing -> bubblePair?.outgoing
            ?: MaterialTheme.colorScheme.primaryContainer
        // Colorway override (Garrett 2026-10-20): a picked colorway wins
        // over per-sender group colors for the bubble FILL — the user's
        // explicit choice outranks the automatic variety. Unpicked,
        // group bubbles keep their per-sender colors exactly as before.
        groupIncoming -> bubblePair?.incoming ?: senderContainer
        else -> bubblePair?.incoming
            ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        message.isOutgoing -> bubblePair?.onOutgoing
            ?: MaterialTheme.colorScheme.onPrimaryContainer
        groupIncoming -> bubblePair?.onIncoming ?: senderOnContainer
        else -> bubblePair?.onIncoming ?: MaterialTheme.colorScheme.onSurface
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
                    // Avatar tap opens that person's details page
                    // (Garrett 2026-10-19).
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onAvatarClick(sender) },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = maxBubbleWidth),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = if (sender.isUnknownSenderGhost()) sender.address
                        else sender.displayName.ifBlank { sender.address },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = senderNameTone,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box {
                        BubbleBox(
                            shape = shape,
                            containerColor = containerColor,
                            contentColor = contentColor,
                            selectionActive = selectionActive,
                            onToggleSelect = onToggleSelect,
                            body = message.body,
                            maxBubbleWidth = maxBubbleWidth,
                            onReact = onReact,
                        )
                        ReactionBadgeOverlay(message, onShowReactions)
                    }
                    // Link unfurl shares the sender column so its leading
                    // edge lines up exactly with the bubble (Garrett
                    // 2026-10-19: previews must left-align with messages).
                    LinkUnfurl(message = message, maxBubbleWidth = maxBubbleWidth)
                }
            }
        } else {
            Box {
                BubbleBox(
                    shape = shape,
                    containerColor = containerColor,
                    contentColor = contentColor,
                    selectionActive = selectionActive,
                    onToggleSelect = onToggleSelect,
                    body = message.body,
                    maxBubbleWidth = maxBubbleWidth,
                    onReact = onReact,
                )
                ReactionBadgeOverlay(message, onShowReactions)
            }
        }
        if (message.isMms) {
            Text(
                "MMS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
        // Link unfurl (Garrett 2026-10-19): a URL in the body gets a preview
        // card under the bubble. Skipped on media messages (the attachment
        // is the content there). Failures degrade to a plain link row —
        // never a gray block. Group-incoming previews render inside the
        // sender column above (same leading edge as the bubble); every
        // other case unfurls here.
        if (message.media == null && !groupIncoming) {
            LinkUnfurl(message = message, maxBubbleWidth = maxBubbleWidth)
        }
        // Read/delivery receipts (Garrett 2026-10-19): Google Messages-style
        // ticks under outgoing bubbles — sent ✓, delivered ✓✓, read ✓✓ blue.
        if (message.isOutgoing) {
            DeliveryTicks(
                status = message.deliveryStatus,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
    }
}

/**
 * Emoji reaction badge overlay (Garrett 2026-10-19): a small Google
 * Messages-style badge straddling the bottom trailing edge of the bubble.
 * Distinct emojis in first-use order, plus a count when multiple people
 * reacted. Tapping opens the who-reacted dialog via [onShowReactions].
 * Nothing renders when the message carries no reactions.
 */
@Composable
private fun BoxScope.ReactionBadgeOverlay(
    message: Message,
    onShowReactions: (Message) -> Unit,
) {
    val reactions = message.reactions
    if (reactions.isEmpty()) return
    val distinct = remember(reactions) { reactions.map { it.emoji }.distinct() }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 6.dp, y = 9.dp),
        onClick = { onShowReactions(message) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            distinct.take(5).forEach { emoji ->
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            if (reactions.size > 1) {
                Text(
                    text = reactions.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
    }
}

/** Link unfurl shared by all message layouts: the preview card for the
 * first URL in the body, or nothing. The caller places it so its leading
 * edge matches the bubble's (group-incoming: inside the sender column). */
@Composable
private fun LinkUnfurl(
    message: Message,
    maxBubbleWidth: Dp,
) {
    val linkUrl = remember(message.body) {
        LinkPreviewFetcher.extractFirstUrl(message.body)
    }
    if (linkUrl != null) {
        Spacer(Modifier.height(6.dp))
        LinkPreviewCard(
            url = linkUrl,
            isOutgoing = message.isOutgoing,
            maxBubbleWidth = maxBubbleWidth,
        )
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
    /** Long-press reaction send (Garrett 2026-10-19: reactions both ways). */
    onReact: (String) -> Unit = {},
) {
    var showReactMenu by remember { mutableStateOf(false) }
    // Bounds of the body text within the bubble (bubble-local coordinates),
    // so long-presses can be split by WHERE they started: on the text ->
    // partial text selection (Garrett 2026-10-20); on the surrounding
    // bubble padding -> the reaction picker, exactly as before.
    var textBounds by remember { mutableStateOf<Rect?>(null) }
    val latestTextBounds = rememberUpdatedState(textBounds)
    Box(
        modifier = Modifier
            .widthIn(max = maxBubbleWidth)
            .clip(shape)
            .background(containerColor)
            .combinedClickable(
                onClick = { if (selectionActive) onToggleSelect() },
                // No onLongClick here on purpose: the position-aware
                // long-press observer right below owns long-press now.
            )
            // Long-press observer (Garrett 2026-10-20: partial text
            // selection). Watches presses in the Initial pass and consumes
            // NOTHING, so taps (toggle-select) and text selection are
            // completely untouched. A press still down past the system
            // long-press timeout, inside the touch slop, opens the
            // reaction picker — but only when it STARTED outside the body
            // text. Presses starting on the text belong to the
            // SelectionContainer below (selection handles + the standard
            // Copy / Select all floating toolbar), so a long-press on the
            // words selects text and a long-press on the bubble's padded
            // ring still opens reactions — neither gesture is deleted.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.changedToDown() } ?: continue
                        val downId = down.id
                        val downPos = down.position
                        val deadline =
                            down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        var change = down
                        var decided = false
                        while (!decided) {
                            val remaining = deadline - change.uptimeMillis
                            val event = if (remaining > 0) {
                                withTimeoutOrNull(remaining) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                }
                            } else {
                                null
                            }
                            when {
                                // Still down at the deadline: a long-press.
                                // Fire the picker unless it began on the text.
                                event == null -> {
                                    val bounds = latestTextBounds.value
                                    if (bounds == null || !bounds.contains(downPos)) {
                                        showReactMenu = true
                                    }
                                    decided = true
                                }
                                else -> {
                                    val c = event.changes
                                        .firstOrNull { it.id == downId }
                                    when {
                                        c == null -> decided = true
                                        !c.pressed -> {
                                            change = c
                                            decided = true
                                        }
                                        (c.position - downPos).getDistance() >
                                            viewConfiguration.touchSlop -> {
                                            // Moved past slop: a scroll or a
                                            // selection-handle drag, not a press.
                                            change = c
                                            decided = true
                                        }
                                        else -> change = c
                                    }
                                }
                            }
                        }
                        // Sit out the rest of this gesture so one press
                        // can't be re-watched mid-flight.
                        while (change.pressed) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.firstOrNull { it.id == downId }
                                ?.let { change = it }
                        }
                    }
                }
            }
            // Uniform interior padding on every bubble.
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Per-bubble selection scope (never the whole list): long-pressing
        // the words highlights them in place with the standard handles +
        // toolbar, so any word or sentence can be copied on its own
        // instead of the whole bubble. In-bubble text carries no link
        // spans today (URLs only feed the unfurl card under the bubble),
        // so nothing else competes for the text's gestures.
        SelectionContainer(
            // The container wraps exactly the text, and its parent is the
            // bubble Box — so the text's bounding box within the parent is
            // its rect in the same bubble-local coordinates the press
            // observer sees.
            modifier = Modifier.onGloballyPositioned { coords ->
                textBounds = coords.parentCoordinates?.localBoundingBoxOf(coords)
            },
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
        }
    }
    ReactionPickerMenu(
        expanded = showReactMenu,
        onDismiss = { showReactMenu = false },
        onReact = onReact,
        onSelect = onToggleSelect,
    )
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
    // Clamp: the bouncy spring overshoots past [0,1] mid-animation, which
    // would drive a corner negative and crash (same class of bug as the
    // pill-bar corner crash, Garrett 2026-10-19 evening).
    val s = squareness.coerceIn(0f, 1f)
    val soft = bubbleCorners(isOutgoing, position)
    val squared = 10.dp
    fun lerp(a: Dp, b: Dp): Dp = (a + (b - a) * s).coerceAtLeast(0.dp)
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
    /** Fires when an attachment picker returns a content URI (Garrett
     * 2026-10-19: [+] finally SENDS — the "coming soon" toast is gone for
     * the four picker options; only the voice-mic stub keeps it). */
    onAttach: (Uri) -> Unit = {},
    /** Sends a starter-pack sticker (Garrett 2026-10-20, feature queue
     * 5/7): tapping a sticker in the smiley panel sends it immediately
     * through the attachment pipeline. */
    onSticker: (StickerDef) -> Unit = {},
    /** Recently used stickers, most recent first (smiley panel). */
    recentStickers: List<StickerDef> = emptyList(),
) {
    var text by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank() && !sending
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
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
    // The sticker panel takes the keyboard's place, so it takes the
    // keyboard's height too: remember the last real IME height and
    // reuse it when the panel opens (by then the IME inset itself is
    // already animating to zero because opening the panel drops focus).
    val density = LocalDensity.current
    var lastImeHeight by remember { mutableStateOf(0.dp) }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx > 0) lastImeHeight = with(density) { imeBottomPx.toDp() }
    }
    val stickerPanelHeight =
        if (lastImeHeight in 180.dp..440.dp) lastImeHeight else 300.dp
    // Attachment pickers (Garrett 2026-10-19): the [+] sheet's four
    // options launch real pickers. GIFs come through as image/gif and are
    // sent with their bytes untouched (never transcoded to JPEG), so they
    // stay animated end to end.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAttach) }
    val gifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAttach) }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAttach) }
    // Camera: TakePicture writes into a pre-created FileProvider URI. The
    // file lives in cacheDir/shared/, covered by file_paths.xml's
    // cache-path entry (the same staging dir attachment VIEWING uses).
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok -> if (ok) cameraUri?.let(onAttach) }
    fun launchCamera() {
        val file = java.io.File(
            context.cacheDir,
            "shared/capture-${System.currentTimeMillis()}.jpg",
        )
        file.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    fun submit() {
        if (!canSend) return
        onSend(text.trim())
        text = ""
        // End of typing: collapse the pill back (focus loss drives it).
        focusManager.clearFocus()
        // Spring pop on send — the unified bouncy language.
        scope.launch {
            sendPop.snapTo(0.82f)
            sendPop.animateTo(1f, RelaySprings.bouncy())
        }
    }

    Column {
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
                            .onFocusChanged { state ->
                                focused = state.isFocused
                                // Typing wins: focusing the field
                                // closes the sticker panel.
                                if (state.isFocused) showStickers = false
                            },
                        minLines = 1,
                        maxLines = 5,
                    )
                    // Smiley: toggles the sticker panel (Garrett
                    // 2026-10-20, feature queue 5/7). Opening it drops
                    // text focus so the keyboard makes room; tapping
                    // the text field closes it again.
                    IconButton(
                        onClick = {
                            val opening = !showStickers
                            showStickers = opening
                            if (opening) focusManager.clearFocus()
                        },
                        modifier = Modifier.requiredSize(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.SentimentSatisfied,
                            contentDescription = "Stickers",
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

    // Sticker panel (Garrett 2026-10-20, feature queue 5/7): springs
    // open under the composer pill at keyboard height; tapping a
    // sticker sends it immediately and the panel stays up for
    // follow-ups — the smiley or the text field closes it.
    AnimatedVisibility(
        visible = showStickers,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = RelaySprings.bouncy(),
        ) + fadeIn(),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(180),
        ) + fadeOut(animationSpec = tween(120)),
    ) {
        StickerPanel(
            recents = recentStickers,
            onPick = { def -> onSticker(def) },
            modifier = Modifier.height(stickerPanelHeight),
        )
    }
    }

    if (showAttachSheet) {
        AttachmentSheet(
            onDismiss = { showAttachSheet = false },
            onPick = { label ->
                showAttachSheet = false
                // The four picker options send for real now (Garrett
                // 2026-10-19: "attachments are coming soon" is gone).
                when (label) {
                    "Gallery" -> galleryLauncher.launch("image/*")
                    "GIF" -> gifLauncher.launch("image/gif")
                    "Camera" -> launchCamera()
                    "Files" -> filesLauncher.launch("*/*")
                }
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
