package com.garrietta.relay.ui.inbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.isUnknownSenderGhost
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.ui.TopSearchPopup
import com.garrietta.relay.ui.motion.ChromeBandClipMath
import com.garrietta.relay.ui.motion.InboxChromeBand
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.SharedElementKeys
import com.garrietta.relay.ui.motion.motionSharedAvatar
import com.garrietta.relay.ui.motion.motionSharedBounds
import com.garrietta.relay.ui.theme.AvatarShapeAssigner
import com.garrietta.relay.ui.theme.CloverShape
import com.garrietta.relay.ui.theme.ClusterMember
import com.garrietta.relay.ui.theme.ContactAvatar
import com.garrietta.relay.ui.theme.GroupAvatarCluster
import com.garrietta.relay.ui.theme.WavyBlobShape
import com.garrietta.relay.ui.theme.WavyBlobShapeCalm
import com.garrietta.relay.ui.theme.WavyBlobShapePressed
import com.garrietta.relay.ui.theme.expressiveShape
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
    userMessages: SharedFlow<String>,
    onOpenChat: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit,
    /**
     * Thread currently open in the detail pane (unfolded two-pane mode).
     * Its inbox row gets the expressive selection overlay.
     */
    selectedThreadId: Long? = null,
) {
    val conversations by viewModel.conversations.collectAsState()
    val query by viewModel.query.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val unknownEnabled by viewModel.unknownEnabled.collectAsState()
    val unreadEnabled by viewModel.unreadEnabled.collectAsState()
    // True-muted conversations (Garrett 2026-10-20): their rows carry a
    // subtle bell-off indicator; suppression lives in the pipeline.
    val mutedKeys by viewModel.mutedKeys.collectAsState()
    // Active typing labels by threadId (Garrett 2026-10-20): a row with
    // an entry swaps its snippet for the italic "{FirstName} is typing...".
    val typingLabels by viewModel.typingLabels.collectAsState()
    // The built-in tabs are synthetic: Unread goes FIRST in this list so
    // it sits directly after the pill bar's implicit "All" (ahead of the
    // user's custom categories), and Unknown is appended LAST, always at
    // the far right of the pill bar (Garrett 2026-10-19 / 2026-10-20).
    // Middle slots get the standard middle-chip geometry from the bar
    // itself. The bar shows when custom categories exist OR either
    // built-in tab is on.
    val pillCategories = remember(categories, unknownEnabled, unreadEnabled) {
        buildList {
            if (unreadEnabled) add(ChatCategory(CategoryStore.UNREAD_ID, "Unread"))
            addAll(categories)
            if (unknownEnabled) add(ChatCategory(CategoryStore.UNKNOWN_ID, "Unknown"))
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // One scroll state per category: switching categories starts the new
    // list at the top, and the header-collapse + bottom-bar effects below
    // re-key on it. EXCEPT on return from a chat: the state is then
    // constructed from the ViewModel's one-shot restore point, so the
    // list sits at the exact pre-chat scroll position on the first frame
    // (Garrett 2026-10-20) — which also puts the tapped row exactly
    // where the reverse avatar morph expects to land.
    // The restore point is consumed ONCE and shared: the row-entrance
    // stagger (firstLoad, below) also reads it — on a chat-exit return the
    // rows must compose in their final form from the very first frame.
    val chatExitRestore = remember(selectedCategoryId) {
        viewModel.consumeChatExitScroll(selectedCategoryId)
    }
    val listState = remember(selectedCategoryId) {
        if (chatExitRestore != null) {
            LazyListState(chatExitRestore.first, chatExitRestore.second)
        } else {
            LazyListState()
        }
    }
    // Every chat entry point funnels through here so the scroll position
    // is captured BEFORE the chat opens, whichever surface opened it
    // (row tap or a search-popup result). The same funnel carries the
    // optimistic read (Garrett 2026-10-20): the tap itself marks the
    // chat read in the local list, so the homepage shows it read on
    // the first frame back — the server mark-read still runs from the
    // chat screen exactly as before, this only removes the wait.
    val openChat: (Conversation) -> Unit = { conversation ->
        viewModel.saveChatExitScroll(
            selectedCategoryId,
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
        )
        viewModel.markOpened(conversation)
        onOpenChat(conversation)
    }
    // Search lives behind the bottom-right expressive button; tapping it
    // opens the shared top-center search popup (see ConversationSearchPopup
    // below). The button also CLOSES the popup: it stays visible above the
    // popup's scrim, and a tap increments searchDismissSignal, which the
    // popup turns into its normal animated exit.
    var searchOpen by remember { mutableStateOf(false) }
    var searchDismissSignal by remember { mutableIntStateOf(0) }
    // Search query must NEVER persist: Garrett's bug — type "haley", exit
    // the app, come back, and the inbox is still filtered. Leaving the
    // inbox (background) or returning to it always resets to the default
    // unfiltered view.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_RESUME) {
                searchOpen = false
                viewModel.setQuery("")
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Bottom cluster form (Garrett 2026-10-20): FULL (new-conversation
    // pill + search button) at the top of the list; scrolled away from
    // the top it condenses — Koda mini-player style — into ONE circular
    // expressive new-conversation button at the bottom-right, and the
    // full form restores only back at the top. This SUPERSEDES the old
    // direction-based auto-hide, which slid the whole cluster off the
    // bottom on scroll-down: the scrolled end state is now the
    // minimized circle, present and tappable, not an empty slot.
    // Position-based and driven by this same listState the header
    // collapse reads (one scroll truth); the pure trigger, including
    // its hysteresis band, is ClusterStateLogic. Seeded from the
    // chat-exit restore point when there is one, so a return deep in
    // the list composes the minimized form on the very first frame.
    var clusterForm by remember(listState) {
        mutableStateOf(
            ClusterStateLogic.initialFormFor(
                chatExitRestore?.first ?: 0,
                chatExitRestore?.second ?: 0,
            ),
        )
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                clusterForm = ClusterStateLogic.formFor(index, offset, clusterForm)
            }
    }

    // Scroll-linked collapsing header: the big "Messages" title shrinks all
    // the way down to a compact bar title as the list scrolls. The collapse
    // is driven DIRECTLY by the scroll offset — deliberately no spring.
    // A spring retargeted on every scroll frame can never settle: it
    // oscillates, recomposes the header continuously, and visibly lags the
    // scroll (diagnosed 2026-10-18 as the 120Hz scroll-choppiness culprit).
    // The scroll itself is the animation; 1:1 tracking is correct.
    val collapse by remember(listState) {
        derivedStateOf {
            val offset = listState.firstVisibleItemScrollOffset.toFloat()
            val jump = if (listState.firstVisibleItemIndex > 0) 360f else 0f
            ((jump + offset) / 360f).coerceIn(0f, 1f)
        }
    }

    // Row entrance stagger plays ONLY on the very first load. On category
    // switches the rows must NOT replay their slide-in — Garrett 2026-10-19:
    // "i can see messages slide out in the back"; the switch is an overlay
    // pop (see below), never a slide.
    // A return from a chat is NOT a first load, and the rows must not sit
    // inside the stagger wrapper even for the first frame (Garrett
    // 2026-10-20: text flashes on the home screen when exiting a chat
    // whose header is expanded). The wrapper is a different composition
    // branch than the bare row, so flipping firstLoad off in the effect
    // below — one frame into the pop — discarded and recomposed every
    // row subtree, including the avatar's shared-element entry, WHILE
    // the reverse avatar morph was forming its match. The shared-
    // transition state machine reads the visible entry being removed
    // mid-transition as ActiveMatchRemovedDuringTransition: the orphaned
    // entry renders again alongside its replacement, the morph's
    // placeholder suppression tears, and the row flashes doubled over
    // the inbox. Collapsed-header exits never showed it — with no
    // chat-side avatar registration there is no match to tear. Starting
    // firstLoad at false whenever a chat-exit restore point was consumed
    // keeps the morph's target entry stable for the whole flight (the
    // same "final state from the first frame" rule #141 applied to the
    // chat header). Cold starts have no restore point, so the stagger
    // still plays there.
    var firstLoad by remember { mutableStateOf(chatExitRestore == null) }
    LaunchedEffect(conversations) {
        if (conversations.isNotEmpty()) firstLoad = false
    }

    LaunchedEffect(Unit) {
        userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    // RCS sync errors, verbatim — Garrett has no logcat access, so a broken
    // sync must say exactly what failed, right in the inbox.
    val syncError by viewModel.syncError.collectAsState()
    LaunchedEffect(syncError) {
        syncError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncError()
        }
    }

    // Root box (Garrett 2026-10-20): the search popup and the bottom
    // cluster are composed AFTER the Scaffold, as this screen's topmost
    // layers. Scaffold draws its topBar as a sibling AFTER its content,
    // so anything inside the Scaffold's content — however late — renders
    // UNDER the "Messages" header; that is why the open search popup
    // had the title and gear drawn over it. As later siblings of the
    // whole Scaffold in this Box, the popup and its scrim cover
    // everything in the inbox (header, pill bar, list), and the bottom
    // cluster — composed after the popup — stays above the scrim so
    // the search button remains the popup's toggle. The audit for the
    // new-message popup: it is composed at the activity level (a nav
    // destination single-pane, last in RelayTwoPane two-pane), already
    // topmost — no change needed there.
    Box(modifier = Modifier.fillMaxSize()) {
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
                // Search is the shared top-center popup now (Garrett
                // 2026-10-20: the search button gets "the same popup as
                // the new chat button") — composed as an overlay at the
                // root Box below, NOT an inline bar. The category filter
                // still COMBINES with the query: the popup's results are
                // `conversations`, which the view model already filters
                // by selected category AND query together.
                // Category bar — music-app style chips (no container pill).
                // Rendered when custom categories exist or either built-in
                // tab (Unread / Unknown) is on; otherwise no bar.
                CategoryPillBar(
                    categories = pillCategories,
                    selectedId = selectedCategoryId,
                    onSelect = viewModel::selectCategory,
                )
                // The conversation LIST is its own rounded container
                // (Google Messages M3E pattern) — solid color, no decoration.
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 8.dp)
                        // Chrome band, top edge (Garrett 2026-10-20):
                        // this surface's top is the line the transition
                        // overlay must never paint above — everything
                        // higher is main-page chrome (collapsing title +
                        // gear + this column's pill bar). Reported from
                        // layout, never snapshot state, so the band
                        // tracks the collapsing header 1:1 with scroll
                        // without recomposing rows (see InboxChromeBand).
                        .onGloballyPositioned { coords ->
                            InboxChromeBand.topPx = coords.positionInWindow().y
                        },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    // Category switch = OVERLAY pop (Garrett 2026-10-19):
                    // the current messages stay put underneath while the new
                    // category's list pops in over them — messages never
                    // slide out. Incoming content draws on top by default.
                    AnimatedContent(
                        targetState = selectedCategoryId,
                        transitionSpec = {
                            (fadeIn(RelaySprings.bouncy()) +
                                scaleIn(
                                    RelaySprings.bouncy(),
                                    initialScale = 0.96f,
                                )) togetherWith fadeOut(tween(160))
                        },
                        label = "categorySwap",
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
                            // Staggered entrance: each row fades+slides in on the
                            // very first load only (firstLoad). Keyed by
                            // threadId, so scroll recycling never replays it
                            // for the same row. Stagger caps at the first
                            // screenful so fast scrolling never feels laggy.
                            // On category switches the rows must NOT replay
                            // their slide-in — the switch is an overlay pop.
                            itemsIndexed(
                                items = conversations,
                                key = { _, it -> it.threadId },
                                contentType = { _, _ -> "conversation" },
                            ) { index, conversation ->
                                if (firstLoad) {
                                    val staggerDelay = if (index < 12) index * 45 else 0
                                    AnimatedVisibility(
                                        visible = true,
                                        enter = fadeIn(
                                            tween(280, delayMillis = staggerDelay),
                                        ) + slideInVertically(
                                            tween(320, delayMillis = staggerDelay),
                                        ) { it / 4 },
                                    ) {
                                        ConversationRow(
                                            conversation = conversation,
                                            typingLabel = typingLabels[conversation.threadId],
                                            muted = CategoryStore.conversationKey(conversation.rcsConversationId, conversation.threadId) in mutedKeys,
                                            isSelected = conversation.threadId == selectedThreadId,
                                            onClick = { openChat(conversation) },
                                            onMarkRead = {
                                                if (conversation.unread) viewModel.markRead(conversation.threadIds)
                                                else viewModel.markUnread(conversation.threadIds)
                                            },
                                            onDelete = { viewModel.delete(conversation.threadIds) },
                                        )
                                    }
                                } else {
                                    ConversationRow(
                                        conversation = conversation,
                                        typingLabel = typingLabels[conversation.threadId],
                                        muted = CategoryStore.conversationKey(conversation.rcsConversationId, conversation.threadId) in mutedKeys,
                                        isSelected = conversation.threadId == selectedThreadId,
                                        onClick = { openChat(conversation) },
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
            }
            }
        }
    }
            // Conversation search popup — the SAME TopSearchPopup as the
            // new-chat button (Garrett 2026-10-20). Composed BEFORE the
            // bottom cluster so the pill + search button render ABOVE the
            // popup's scrim and stay tappable: the search button remains
            // the toggle that closes the popup. Results are the already
            // category-filtered `conversations` (query applied by the view
            // model), so search and category combine exactly as before.
            if (searchOpen) {
                ConversationSearchPopup(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    results = conversations,
                    typingLabels = typingLabels,
                    selectedThreadId = selectedThreadId,
                    dismissSignal = searchDismissSignal,
                    onDismiss = {
                        searchOpen = false
                        viewModel.setQuery("")
                    },
                    onOpenChat = { conversation ->
                        searchOpen = false
                        viewModel.setQuery("")
                        openChat(conversation)
                    },
                )
            }
            // Bottom cluster (Garrett 2026-10-20): FULL at the top of
            // the list — the new-conversation pill (cropped width-wise
            // to make room) + the expressive search button at the
            // bottom-right; scrolled away from the top it condenses
            // into the minimized circular new-conversation button
            // (ClusterStateLogic). The form morph is an expressive
            // crossfade + corner-anchored scale INSIDE a slot whose
            // layout size never changes — both forms measure the same
            // 64dp content + 24dp bottom padding — so the morph can
            // never resize the slot, shift the list, or move the
            // chrome band's bottom line. One-shot entrance on first
            // appearance, as before.
            var pillVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { pillVisible = true }
            AnimatedVisibility(
                visible = pillVisible,
                enter = fadeIn(tween(300, delayMillis = 150)) +
                    slideInVertically(
                        tween(450, delayMillis = 150),
                    ) { it },
                exit = fadeOut(tween(180)) +
                    slideOutVertically(tween(220)) { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Chrome band, bottom edge (Garrett 2026-10-20):
                    // the cluster slot's top is the line the transition
                    // overlay must never paint below while the cluster
                    // is on screen — a morph to/from the last visible
                    // row passes behind the cluster exactly as it
                    // passes behind the header, whatever form the
                    // cluster is in: the slot's height is the same
                    // constant in the FULL and MINIMIZED forms and on
                    // every frame of the morph between them, so this
                    // one measurement serves all of them. Once the
                    // cluster leaves composition entirely the node
                    // measures 0 and the band opens to the window
                    // bottom again (bandBottomForCluster).
                    .onGloballyPositioned { coords ->
                        InboxChromeBand.bottomPx =
                            ChromeBandClipMath.bandBottomForCluster(
                                clusterTopPx = coords.positionInWindow().y,
                                clusterHeightPx = coords.size.height,
                            )
                    },
            ) {
                AnimatedContent(
                    targetState = clusterForm,
                    transitionSpec = {
                        // Anchored at the slot's bottom-right corner —
                        // where the search button sits in FULL and the
                        // circle lives in MINIMIZED — so condensing
                        // reads as the row folding INTO that corner and
                        // restoring as it unfolding back out of it. The
                        // two forms share the slot's size, so there is
                        // no size animation to fight the scale.
                        val corner = TransformOrigin(1f, 1f)
                        if (targetState == InboxClusterForm.MINIMIZED) {
                            (fadeIn(RelaySprings.bouncy()) +
                                scaleIn(
                                    RelaySprings.bouncy(),
                                    initialScale = 0.55f,
                                    transformOrigin = corner,
                                )) togetherWith
                                (fadeOut(tween(140)) +
                                    scaleOut(
                                        RelaySprings.bouncy(),
                                        targetScale = 0.92f,
                                        transformOrigin = corner,
                                    ))
                        } else {
                            (fadeIn(RelaySprings.bouncy()) +
                                scaleIn(
                                    RelaySprings.bouncy(),
                                    initialScale = 0.92f,
                                    transformOrigin = corner,
                                )) togetherWith
                                (fadeOut(tween(140)) +
                                    scaleOut(
                                        RelaySprings.bouncy(),
                                        targetScale = 0.55f,
                                        transformOrigin = corner,
                                    ))
                        }
                    },
                    label = "inboxClusterForm",
                ) { form ->
                    when (form) {
                        InboxClusterForm.FULL -> FullClusterRow(
                            onNewConversation = onNewConversation,
                            onSearchToggle = {
                                // Tapping the search button while the
                                // popup is open DISMISSES it (Garrett
                                // 2026-10-19) — the button is a toggle,
                                // not a one-way door; the close goes
                                // through the popup's dismiss signal so
                                // it plays the same animated exit as
                                // Cancel/back/scrim instead of vanishing.
                                if (searchOpen) {
                                    searchDismissSignal++
                                } else {
                                    searchOpen = true
                                }
                            },
                        )
                        InboxClusterForm.MINIMIZED ->
                            MinimizedConversationButton(
                                onClick = onNewConversation,
                            )
                    }
                }
            }
    }
}

/**
 * Conversation search as the shared [TopSearchPopup] — Garrett 2026-10-20:
 * the search button gets the same popup as the new-chat button. Same
 * card, same top-center anchor, same bouncy entrance, same bottom edge
 * parked above the keyboard.
 *
 * Results are the inbox's own `conversations` flow, which the view model
 * has ALREADY filtered by the selected category AND the query — search
 * and category combine, exactly as the old inline search bar behaved.
 * Idle state (blank query) lists the current category's conversations;
 * tapping one opens the chat (after the popup's exit animation, via
 * requestExit). The query is cleared by the caller on every exit path,
 * so a stale filter can never persist.
 */
@Composable
private fun ConversationSearchPopup(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<Conversation>,
    typingLabels: Map<Long, String>,
    selectedThreadId: Long?,
    dismissSignal: Int,
    onDismiss: () -> Unit,
    onOpenChat: (Conversation) -> Unit,
) {
    TopSearchPopup(
        title = "Search",
        query = query,
        onQueryChange = onQueryChange,
        placeholder = "Search conversations",
        onDismiss = onDismiss,
        dismissSignal = dismissSignal,
    ) { requestExit ->
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
        ) {
            items(
                items = results,
                key = { it.threadId },
                contentType = { "conversation" },
            ) { conversation ->
                SearchResultRow(
                    conversation = conversation,
                    typingLabel = typingLabels[conversation.threadId],
                    isSelected = conversation.threadId == selectedThreadId,
                    onClick = { requestExit { onOpenChat(conversation) } },
                )
            }
            if (results.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) "No conversations"
                        else "No conversations match \"$query\"",
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
 * One search result: the inbox row's content (avatar, name, time,
 * snippet, transport chip) without the list-only machinery — no swipe
 * actions and no shared-element morph inside a popup.
 */
@Composable
private fun SearchResultRow(
    conversation: Conversation,
    isSelected: Boolean,
    onClick: () -> Unit,
    typingLabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchResultAvatar(conversation)
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
                if (typingLabel != null) {
                    // Typing replaces the snippet (Garrett 2026-10-20, GM
                    // parity): "{FirstName} is typing..." in italic.
                    Text(
                        typingLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = FontStyle.Italic,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(
                        conversation.snippet.ifBlank { "No messages" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (conversation.unread) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TransportChip(conversation.transportLabel)
            }
        }
    }
}

/**
 * The inbox [Avatar] minus its shared-element morph and press scale —
 * inside the search popup both would fight the popup's own animation.
 * Same slot, same per-contact shapes, same group cluster.
 */
@Composable
private fun SearchResultAvatar(conversation: Conversation) {
    AvatarSlot {
        if (conversation.participants.size > 1) {
            GroupAvatarCluster(
                members = conversation.participants.filterNot { it.isUnknownSenderGhost() }.map {
                    ClusterMember(
                        photoUri = it.photoUri,
                        name = it.displayName.ifBlank { it.address },
                        address = it.address,
                    )
                },
                conversationKey = conversation.rcsConversationId
                    ?: "tel:${conversation.threadId}",
                boxSize = 60.dp,
            )
        } else {
            val shapeKey = conversation.address.ifBlank { conversation.displayName }
            val overlayShape = remember(shapeKey) {
                AvatarShapeAssigner.namedShapeFor(shapeKey).shape
            }
            ContactAvatar(
                photoUri = conversation.photoUri,
                name = conversation.displayName,
                shapeKey = shapeKey,
                size = 56.dp,
                shape = overlayShape,
            )
        }
    }
}

/**
 * The "Messages" header: a designed lockup, not a default top bar. An
 * expressive Pop-star mark + tight ExtraBold title, and the gear in a
 * tonal Cookie-shaped button with a bouncy press. Collapses 40sp → 20sp
 * on scroll. Transparent at every fraction — no separate polygon (the
 * #111 rule stands).
 */
@Composable
private fun CollapsingMessagesHeader(
    collapse: Float,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
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
                    end = 12.dp,
                    top = (10 - 4 * collapse).dp,
                    bottom = (2 + 2 * collapse).dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Brand mark: a Pop star in the primary container, shrinking
            // with the collapse. The glyph is a chat bubble — deliberately
            // NOT a pencil (Garrett 2026-10-19: the pencil already lives in
            // the New conversation pill; the header mark shouldn't repeat it).
            Box(
                modifier = Modifier
                    .size((34 - 12 * collapse).dp)
                    .clip(expressiveShape("Pop"))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ChatBubble,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size((18 - 6 * collapse).dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "Messages",
                style = MaterialTheme.typography.displayLarge,
                fontSize = (40 - 20 * collapse).sp,
                lineHeight = (44 - 22 * collapse).sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Gear in an expressive shaped tonal button — the header's
            // tactile moment instead of a default IconButton.
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val pressScale by animateFloatAsState(
                targetValue = if (pressed) 0.86f else 1f,
                animationSpec = RelaySprings.bouncy(),
                label = "gearPress",
            )
            Box(
                modifier = Modifier
                    .size((52 - 8 * collapse).dp)
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                    .clip(expressiveShape("Cookie7"))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onOpenSettings,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * The bottom cluster's FULL form: the new-conversation pill (cropped
 * width-wise to make room) + the expressive search button at the
 * bottom-right. Measures exactly [ClusterStateLogic.SLOT_HEIGHT_DP] —
 * the 64dp content row plus the 24dp bottom padding — the same slot
 * the MINIMIZED form measures, so the form morph never resizes the
 * cluster's layout (or the chrome band read from its top).
 */
@Composable
private fun FullClusterRow(
    onNewConversation: () -> Unit,
    onSearchToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = ClusterStateLogic.BOTTOM_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NewConversationPill(
            onClick = onNewConversation,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        // Search: a distinctive expressive shape (Sunny star) —
        // deliberately NOT a circle, per the expressive brief.
        // Press-pop: the button itself bounces when tapped, so
        // the tap feels connected to the search popup popping in.
        val searchInteraction = remember { MutableInteractionSource() }
        val searchPressed by searchInteraction.collectIsPressedAsState()
        val searchPop by animateFloatAsState(
            targetValue = if (searchPressed) 0.82f else 1f,
            animationSpec = RelaySprings.bouncy(),
            label = "searchBtnPop",
        )
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = searchPop
                    scaleY = searchPop
                }
                .clip(expressiveShape("Sunny"))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(
                    interactionSource = searchInteraction,
                    indication = null,
                    onClick = onSearchToggle,
                    role = Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search conversations",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * The bottom cluster's MINIMIZED form (Garrett 2026-10-20 — the Koda
 * mini-player model: scrolled down, the bar condenses to one small
 * circular option parked bottom-right): a single circular expressive
 * button carrying just the new-conversation action. The shape is
 * Cookie7, the scalloped circle from Relay's expressive vocabulary —
 * circular in style (the same shape the header's settings gear
 * wears), and distinct from search's Sunny star, which exists only
 * in the FULL form. Flat like the rest of the cluster: a
 * clipped, opaquely filled shape — no elevation, no shadow — in the
 * pill's own colors, because this button IS the pill, condensed; its
 * press language is the pill's gentle press-scale for the same
 * reason. It occupies the same slot as the FULL form (the 64dp
 * content row, the button centered against it at the end), so the
 * form morph never resizes the layout or the chrome band.
 */
@Composable
private fun MinimizedConversationButton(
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = ClusterStateLogic.BOTTOM_PADDING_DP.dp)
            .height(ClusterStateLogic.CONTENT_HEIGHT_DP.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.96f else 1f,
            animationSpec = RelaySprings.gentle(),
            label = "miniPillPress",
        )
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(expressiveShape("Cookie7"))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    role = Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "New conversation",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * Floating "New conversation" bar — a plain full-width, slightly rounded
 * rectangle (18dp corners). FLAT (Garrett 2026-10-20: no shadow effects
 * on the bottom cluster): zero tonal and shadow elevation — the opaque
 * primaryContainer fill alone separates it from the list scrolling
 * beneath. Gentle press-scale in the unified spring language; the
 * densest color in the scheme, per the expressive contrast rule.
 *
 * FIXED HEIGHT (Garrett 2026-10-19: the pill "keeps getting taller and
 * taller"): the layout height is a constant 64.dp — no padding-driven
 * intrinsic height that any state, recomposition, or animation can perturb.
 * The press scale is deliberately the no-overshoot gentle spring: an
 * underdamped bouncy scale can overshoot above 1.0 on release and read as
 * the button growing.
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
        animationSpec = RelaySprings.gentle(),
        label = "pillPress",
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        // Plain slightly-rounded rectangle — Garrett's call: no flourishes.
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
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
    isSelected: Boolean = false,
    muted: Boolean = false,
    typingLabel: String? = null,
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
        Box(modifier = Modifier.fillMaxWidth()) {
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
                    if (muted) {
                        // True-mute indicator (Garrett 2026-10-20): this
                        // conversation posts NO system notifications.
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.NotificationsOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
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
                    if (typingLabel != null) {
                        // Typing replaces the snippet (Garrett 2026-10-20,
                        // GM parity): "{FirstName} is typing..." in italic.
                        // Display-only — the unread dot and the row's
                        // position/read state are untouched.
                        Text(
                            typingLabel,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else {
                        Text(
                            conversation.snippet.ifBlank { "No messages" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (conversation.unread) FontWeight.Medium else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TransportChip(conversation.transportLabel)
                }
            }
        }
            // Selection overlay (Garrett 2026-10-19): a slightly
            // transparent expressive polygon OVER the selected chat's row
            // — part of the M3E language, not a flat highlight. Bouncy
            // alpha in/out as the selection moves between rows.
            val overlayAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = RelaySprings.bouncy(),
                label = "selectionOverlay",
            )
            if (overlayAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = overlayAlpha * 0.22f }
                        .clip(expressiveShape("Puffy"))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Avatar(conversation: Conversation, pressed: Boolean) {
    // Shape-as-state: the blob morphs tighter while pressed, with a bouncy
    // press scale — the avatar feels alive under the finger. The avatar
    // shared-morphs in BOTH directions (motionSharedAvatar): up into the
    // chat header on open, back into this row on exit — message content
    // carries no shared modifiers, preserving the #45 text-ghosting fix.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = RelaySprings.bouncy(),
        label = "avatarPress",
    )
    // The avatar sits centered in a uniform-width slot so the column
    // forms a straight edge regardless of each shape's width.
    AvatarSlot {
        if (conversation.participants.size > 1) {
            // Group row: the group's unique avatar cluster (spiral / grid /
            // zigzag, deterministic per conversation) instead of one avatar.
            // UNKNOWN_SENDER! ghosts are hidden from the stack (display
            // layer only — the data is untouched).
            GroupAvatarCluster(
                members = conversation.participants.filterNot { it.isUnknownSenderGhost() }.map {
                    ClusterMember(
                        photoUri = it.photoUri,
                        name = it.displayName.ifBlank { it.address },
                        address = it.address,
                    )
                },
                conversationKey = conversation.rcsConversationId
                    ?: "tel:${conversation.threadId}",
                boxSize = 60.dp,
                // Same shared-avatar morph as 1:1 rows (Garrett
                // 2026-10-20: ONE picture-movement animation for every
                // chat, group or not) — the whole cluster travels as one
                // element into the group header cluster, and back on exit.
                modifier = Modifier
                    .motionSharedAvatar(
                        SharedElementKeys.avatar(conversation.threadId),
                    )
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    },
            )
        } else {
            // Overlay-clip fix (Garrett 2026-10-19: the avatar "bugs" at its
            // bottom corners when opening a chat). motionSharedAvatar clips
            // the transition overlay to the given shape — it was hardcoded
            // to WavyBlobShape while the avatar actually renders the
            // per-contact rotation shape, so the clip and the content
            // disagreed at the corners. Pass the RESOLVED shape instead.
            val shapeKey = conversation.address.ifBlank { conversation.displayName }
            val overlayShape = remember(shapeKey) {
                AvatarShapeAssigner.namedShapeFor(shapeKey).shape
            }
            ContactAvatar(
                photoUri = conversation.photoUri,
                name = conversation.displayName,
                shapeKey = shapeKey,
                modifier = Modifier
                    .motionSharedAvatar(
                        SharedElementKeys.avatar(conversation.threadId),
                        shape = overlayShape,
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
