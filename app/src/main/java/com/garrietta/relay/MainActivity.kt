package com.garrietta.relay

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.ThemeMode
import com.garrietta.relay.ui.NewConversationDialog
import com.garrietta.relay.ui.Screen
import com.garrietta.relay.ui.chat.ChatDetailsScreen
import com.garrietta.relay.ui.chat.ChatScreen
import com.garrietta.relay.ui.chat.ChatViewModel
import com.garrietta.relay.ui.inbox.InboxScreen
import com.garrietta.relay.ui.inbox.InboxViewModel
import com.garrietta.relay.ui.motion.LocalAnimatedVisibilityScope
import com.garrietta.relay.ui.motion.LocalSharedElementsEnabled
import com.garrietta.relay.ui.motion.LocalSharedTransitionScope
import com.garrietta.relay.ui.motion.MotionKind
import com.garrietta.relay.ui.motion.RelaySprings
import com.garrietta.relay.ui.motion.rememberMotionTransforms
import com.garrietta.relay.ui.settings.SettingsScreen
import com.garrietta.relay.ui.settings.SettingsViewModel
import com.garrietta.relay.ui.theme.RelayTheme
import com.garrietta.relay.rcs.ui.RcsPairingScreen
import com.garrietta.relay.ui.theme.WavyBlobShapeCalm
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app: RelayApp get() = application as RelayApp

    // Hoisted to activity level so onResume can refresh the default-SMS state
    // (the banner must disappear the moment the role is granted).
    private val settingsVm: SettingsViewModel by viewModels {
        SettingsViewModelFactory(applicationContext, (application as RelayApp).repository, (application as RelayApp).categoryStore, (application as RelayApp).aiSettingsStore)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* inbox refreshes on next resume; repository tolerates denial */ }

    // Animated window background: the theme's background color is painted here
    // so transitions never reveal a wrong-mode color. Theme changes animate
    // the color instead of snapping it (see applyWindowBackgroundForTheme).
    private var windowBgColor: Int = 0
    private var windowBgAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paint the window background BEFORE first composition so no frame can
        // ever show an unthemed color; re-apply (animated) on theme changes.
        applyWindowBackgroundForTheme(animated = false)
        lifecycleScope.launch {
            settingsVm.themeMode.collect {
                applyWindowBackgroundForTheme(animated = true)
                applySystemBarAppearance()
            }
        }
        enableEdgeToEdge()
        // After enableEdgeToEdge: its SystemBarStyle.auto detection follows the
        // SYSTEM theme, not the app's override — our explicit flags win.
        applySystemBarAppearance()
        requestMessagingPermissions()

        setContent {
            val themeMode by settingsVm.themeMode.collectAsState()
            // App-wide persistent contact color (Item 5): the last-viewed
            // contact's seed tints the whole app until a new photo replaces it.
            val appSeed by app.appSeedStore.seed.collectAsState()

            RelayTheme(themeMode = themeMode, seedColor = appSeed) {
                // One-time crash notice: if the last run died with an
                // uncaught exception (see CrashReporter — no logcat access
                // from Garrett's device), say so once on next launch.
                var crashNotice by remember {
                    mutableStateOf(CrashReporter.lastCrashSummary(this@MainActivity))
                }
                if (crashNotice != null) {
                    AlertDialog(
                        onDismissRequest = {
                            CrashReporter.clear(this@MainActivity)
                            crashNotice = null
                        },
                        title = { Text("Relay crashed last time") },
                        text = { Text(crashNotice!!) },
                        confirmButton = {
                            TextButton(onClick = {
                                CrashReporter.clear(this@MainActivity)
                                crashNotice = null
                            }) { Text("Dismiss") }
                        },
                    )
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Foldable adaptive layout: conversation list beside the
                    // chat when wide (unfolded Fold / tablet), single-pane
                    // backstack when narrow (folded phone).
                    if (maxWidth >= 720.dp) {
                        RelayTwoPane()
                    } else {
                        RelaySinglePane()
                    }
                }
            }
        }
    }

    /**
     * Single-pane navigation (folded phone): manual backstack with
     * direction-aware transitions — forward pushes slide in on the shared
     * axis, back pops mirror it.
     */
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun RelaySinglePane() {
        val backStack = remember { mutableStateListOf<Screen>(Screen.Inbox) }
        var navForward by remember { mutableStateOf(true) }
        // New-chat popup state (Garrett 2026-10-19 / 2026-10-20): the
        // popup is an OVERLAY over the still-composed inbox (composed at
        // the end of this function), never a navigation destination —
        // as an AnimatedContent destination the screen swap removed the
        // inbox from composition, so the popup's scrim dimmed only the
        // flat window background and the popup read as a gray sheet,
        // including the area a drag-to-dismiss uncovers. The per-open
        // nonce keys the dialog so every open is a genuinely fresh
        // composition: no animation/remember state leaks between opens
        // and the sheet height can never accumulate.
        var showNewConversation by remember { mutableStateOf(false) }
        var newConvNonce by remember { mutableStateOf(0L) }
        fun push(s: Screen) {
            navForward = true
            backStack.add(s)
        }
        fun pop() {
            if (backStack.size > 1) {
                navForward = false
                backStack.removeLast()
            }
        }
        // (The former 900ms navForward reset is gone, Garrett
        // 2026-10-20: it existed only to re-arm the direction-gated
        // shared-bounds registration after a pop. That gate was the
        // close-flash mechanism — registrations are unconditional now
        // (see motionSharedBounds), so the flag's only remaining job
        // is choosing the FORWARD/BACKWARD pane transform, which
        // push()/pop() set synchronously with the navigation itself.)
        BackHandler(enabled = backStack.size > 1) { pop() }

        // Container-transform navigation: conversation avatar morphs
        // into the chat header via shared bounds (see ui.motion).
        SharedTransitionLayout {
            val motionTransforms = rememberMotionTransforms()
            AnimatedContent(
                targetState = backStack.last(),
                transitionSpec = {
                    val incoming = targetState
                    val outgoing = initialState
                    if (incoming is Screen.ChatDetails || outgoing is Screen.ChatDetails) {
                        // Same pattern: the details page slides itself up
                        // (bouncy, like new-chat); the swap underneath fades.
                        fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    } else {
                        motionTransforms[if (navForward) MotionKind.FORWARD else MotionKind.BACKWARD]
                    }
                },
                label = "relay-nav",
            ) { screen ->
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalAnimatedVisibilityScope provides this@AnimatedContent,
                    // Shared-element morphs (container bounds + avatar)
                    // are registered unconditionally and run in BOTH
                    // directions — the exact reverse on exit (Garrett
                    // 2026-10-20). The avatar's landing target is
                    // guaranteed by the inbox scroll restore point: the
                    // list is back at its pre-chat position on the first
                    // pop frame, so the avatar lands on its row instead
                    // of flashing in the overlay (the 2026-10-19
                    // pop-flash root cause).
                ) {
                    when (screen) {
                        is Screen.Inbox -> {
                            val inboxVm: InboxViewModel = viewModel(
                                factory = InboxViewModelFactory(app.repository, app.categoryStore, app.muteStore)
                            )
                            InboxScreen(
                                viewModel = inboxVm,
                                userMessages = settingsVm.userMessages,
                                onOpenChat = {
                                    push(Screen.Chat(it.threadId, it.address, it.displayName, it.threadIds, it.transportLabel, it.rcsConversationId, it.isGroup))
                                },
                                onOpenSettings = { push(Screen.Settings) },
                                onNewConversation = { newConvNonce++; showNewConversation = true },
                            )
                        }
                        is Screen.Chat -> {
                            val chatVm: ChatViewModel = viewModel(
                                key = "chat_${screen.threadId}",
                                factory = ChatViewModelFactory(app.repository, app.appSeedStore, app.chatScrollStore, app.aiSettingsStore, app.transcriptStore, app, screen.threadId, screen.threadIds, screen.address, screen.rcsConversationId, screen.transportLabel),
                            )
                            ChatScreen(
                                viewModel = chatVm,
                                displayName = screen.displayName,
                                recipient = screen.address,
                                threadId = screen.threadId,
                                threadIds = screen.threadIds,
                                transportLabel = screen.transportLabel,
                                rcsConversationId = screen.rcsConversationId,
                                knownGroup = screen.isGroup,
                                onBack = ::pop,
                                // Header/avatar taps open the details page
                                // (Garrett 2026-10-19).
                                onOpenDetails = { focusAddress ->
                                    push(
                                        Screen.ChatDetails(
                                            threadId = screen.threadId,
                                            threadIds = screen.threadIds,
                                            address = screen.address,
                                            displayName = screen.displayName,
                                            transportLabel = screen.transportLabel,
                                            rcsConversationId = screen.rcsConversationId,
                                            isGroup = screen.isGroup,
                                            focusAddress = focusAddress,
                                        )
                                    )
                                },
                            )
                        }
                        is Screen.ChatDetails -> {
                            // Same ViewModel key as the chat screen: the
                            // details page reuses the live conversation data
                            // (participants, messages, media).
                            val detailsVm: ChatViewModel = viewModel(
                                key = "chat_${screen.threadId}",
                                factory = ChatViewModelFactory(app.repository, app.appSeedStore, app.chatScrollStore, app.aiSettingsStore, app.transcriptStore, app, screen.threadId, screen.threadIds, screen.address, screen.rcsConversationId, screen.transportLabel),
                            )
                            ChatDetailsScreen(
                                viewModel = detailsVm,
                                displayName = screen.displayName,
                                recipient = screen.address,
                                threadId = screen.threadId,
                                transportLabel = screen.transportLabel,
                                rcsConversationId = screen.rcsConversationId,
                                knownGroup = screen.isGroup,
                                focusAddress = screen.focusAddress,
                                onBack = ::pop,
                            )
                        }
                        is Screen.Settings -> {
                            SettingsScreen(
                                viewModel = settingsVm,
                                onBack = ::pop,
                                onOpenRcsPairing = { push(Screen.RcsPairing) },
                            )
                        }
                        is Screen.RcsPairing -> {
                            RcsPairingScreen(
                                transport = app.rcsTransport,
                                onBack = ::pop,
                            )
                        }
                    }
                }
            }
        }

        // New-conversation popup overlay (Garrett 2026-10-20): composed
        // AFTER the navigation host, over the still-composed inbox —
        // the identical host seam as the two-pane overlay below and as
        // the inbox search popup inside InboxScreen — so the shared
        // TopSearchPopup scrim dims the homepage itself, at rest and
        // throughout the drag-to-dismiss. Fresh composition per open
        // via the nonce key (see the state declaration above).
        if (showNewConversation) {
            key(newConvNonce) {
                NewConversationDialog(
                    onDismiss = { showNewConversation = false },
                    onStart = { number ->
                        showNewConversation = false
                        // threadId unknown until first message; use -1 placeholder.
                        push(Screen.Chat(-1L, number, number))
                    },
                )
            }
        }
    }

    /**
     * Two-pane layout (unfolded foldable / tablet): conversation list in its
     * own container on the left, selected chat in its own container on the
     * right with a smooth crossfade. The per-contact tint lives inside
     * ChatScreen's MaterialTheme, so it tints ONLY the detail container —
     * the list pane keeps the wallpaper scheme. Settings and the
     * new-conversation dialog overlay full-screen.
     */
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun RelayTwoPane() {
        var selected by remember { mutableStateOf<Conversation?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        var showNewConversation by remember { mutableStateOf(false) }
        // Same per-open freshness as single-pane (Garrett 2026-10-19): the
        // sheet must never accumulate height across opens.
        var newChatNonce by remember { mutableStateOf(0L) }
        var showRcsPairing by remember { mutableStateOf(false) }
        // Conversation details overlay (Garrett 2026-10-19): header/avatar
        // taps in the chat pane open it over the detail pane. The last
        // request is retained so the exit animation plays on content
        // instead of a blank surface.
        var detailsRequest by remember { mutableStateOf<Pair<Conversation, String?>?>(null) }
        var shownRequest by remember { mutableStateOf<Pair<Conversation, String?>?>(null) }
        if (detailsRequest != null) shownRequest = detailsRequest
        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = !showSettings && showRcsPairing) { showRcsPairing = false }
        BackHandler(enabled = !showSettings && detailsRequest != null) { detailsRequest = null }
        BackHandler(enabled = !showSettings && detailsRequest == null && selected != null) { selected = null }

        SharedTransitionLayout {
            val motionTransforms = rememberMotionTransforms()
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
            ) {
                // Draggable split (Garrett 2026-10-19): the divider between
                // the list pane and the chat pane slides freely — no locked
                // aspect ratio. The fraction persists across restarts.
                var listFraction by remember { mutableStateOf(app.paneFraction()) }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                ) {
                    val density = LocalDensity.current
                    val maxWidthPx = with(density) { maxWidth.toPx() }
                    Row(modifier = Modifier.fillMaxSize()) {
                        // List pane: its own rounded container.
                        Surface(
                            modifier = Modifier
                                .weight(listFraction)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                        val inboxVm: InboxViewModel = viewModel(
                            factory = InboxViewModelFactory(app.repository, app.categoryStore, app.muteStore)
                        )
                        InboxScreen(
                            viewModel = inboxVm,
                            userMessages = settingsVm.userMessages,
                            onOpenChat = {
                                selected = it
                                detailsRequest = null
                            },
                            onOpenSettings = { showSettings = true },
                            onNewConversation = { newChatNonce++; showNewConversation = true },
                            selectedThreadId = selected?.threadId,
                        )
                    }
                    // Draggable divider handle between the panes: a wide
                    // touch target with a slim visual grip.
                    val dragState = rememberDraggableState { dxPx ->
                        listFraction = (listFraction + dxPx / maxWidthPx)
                            .coerceIn(0.25f, 0.75f)
                    }
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .fillMaxHeight()
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Horizontal,
                                onDragStopped = { app.setPaneFraction(listFraction) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(48.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    // Detail pane: its own rounded container; crossfades
                    // between the placeholder and the selected chat — no hard
                    // cut, no bare-background gap.
                    Surface(
                        modifier = Modifier
                            .weight(1f - listFraction)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 1.dp,
                    ) {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = { motionTransforms[MotionKind.TOP_LEVEL] },
                            label = "detail-pane",
                        ) { convo ->
                            CompositionLocalProvider(
                                LocalAnimatedVisibilityScope provides this@AnimatedContent,
                                // No shared elements in the detail pane: the
                                // list rows register under the inbox's own
                                // internal category-swap scope, so a chat
                                // registered here is one-sided and renders
                                // in the transition overlay during the
                                // crossfade — the two-pane flash. Pure
                                // crossfade instead (Garrett 2026-10-20).
                                LocalSharedElementsEnabled provides false,
                            ) {
                                if (convo == null) {
                                    ConversationPlaceholder()
                                } else {
                                    val chatVm: ChatViewModel = viewModel(
                                        key = "chat_${convo.threadId}",
                                        factory = ChatViewModelFactory(app.repository, app.appSeedStore, app.chatScrollStore, app.aiSettingsStore, app.transcriptStore, app, convo.threadId, convo.threadIds, convo.address, convo.rcsConversationId, convo.transportLabel),
                                    )
                                    ChatScreen(
                                        viewModel = chatVm,
                                        displayName = convo.displayName,
                                        recipient = convo.address,
                                        threadId = convo.threadId,
                                        threadIds = convo.threadIds,
                                        transportLabel = convo.transportLabel,
                                        rcsConversationId = convo.rcsConversationId,
                                        knownGroup = convo.isGroup,
                                        onBack = { selected = null },
                                        onOpenDetails = { focusAddress ->
                                            detailsRequest = convo to focusAddress
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                } // BoxWithConstraints (draggable split)
            }
        }

        // Conversation details overlay for the two-pane detail pane
        // (Garrett 2026-10-19). Slides up over the chat, bouncy.
        AnimatedVisibility(
            visible = detailsRequest != null,
            enter = slideInVertically(RelaySprings.bouncy()) { it } +
                fadeIn(RelaySprings.bouncy()),
            exit = fadeOut(tween(180)),
        ) {
            val req = shownRequest
            if (req != null) {
                val (dc, focus) = req
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    val detailsVm: ChatViewModel = viewModel(
                        key = "chat_${dc.threadId}",
                        factory = ChatViewModelFactory(app.repository, app.appSeedStore, app.chatScrollStore, app.aiSettingsStore, app.transcriptStore, app, dc.threadId, dc.threadIds, dc.address, dc.rcsConversationId, dc.transportLabel),
                    )
                    ChatDetailsScreen(
                        viewModel = detailsVm,
                        displayName = dc.displayName,
                        recipient = dc.address,
                        threadId = dc.threadId,
                        transportLabel = dc.transportLabel,
                        rcsConversationId = dc.rcsConversationId,
                        knownGroup = dc.isGroup,
                        focusAddress = focus,
                        onBack = { detailsRequest = null },
                    )
                }
            }
        }

        if (showNewConversation) {
            key(newChatNonce) {
            NewConversationDialog(
                onDismiss = { showNewConversation = false },
                onStart = { number ->
                    showNewConversation = false
                    // Bind to the real thread once the first message sends
                    // (ChatViewModel rebinds on threadId -1).
                    selected = Conversation(
                        threadId = -1L,
                        address = number,
                        displayName = number,
                        snippet = "",
                        date = System.currentTimeMillis(),
                        unread = false,
                        messageCount = 0,
                        // Paired: the new conversation resolves to an RCS
                        // thread (ChatViewModel.rcsConversationFor) — label it
                        // RCS so the header and send path agree from the start.
                        transportLabel = if (app.rcsTransport.isPairedNow) "RCS" else "SMS",
                        photoUri = null,
                    )
                },
            )
            } // key(newChatNonce)
        }
        if (showSettings) {
            SettingsScreen(
                viewModel = settingsVm,
                onBack = { showSettings = false },
                onOpenRcsPairing = { showRcsPairing = true },
            )
        }
        if (showRcsPairing) {
            RcsPairingScreen(
                transport = app.rcsTransport,
                onBack = { showRcsPairing = false },
            )
        }
    }

    /**
     * Koda-style empty state for the two-pane detail area: wavy organic
     * emblem, expressive headline, quiet subhead.
     */
    @Composable
    private fun ConversationPlaceholder() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(WavyBlobShapeCalm)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(52.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Pick a conversation",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your chats open here, beside the list.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.repository.refresh()
    }

    /**
     * Effective dark/light from the user's theme setting (System follows the
     * current uiMode). Single source of truth for window background AND
     * system-bar appearance so they can never disagree.
     */
    private fun isEffectiveDarkTheme(): Boolean = when (settingsVm.themeMode.value) {
        ThemeMode.SYSTEM ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /**
     * Drives status/navigation-bar icon appearance from the effective theme:
     * light icons on dark theme, dark icons on light theme. Round 4 removed
     * the hardcoded windowLightStatusBar and nothing has driven these flags
     * since — leaving dark icons on the dark background (nearly invisible).
     * Re-applied on every theme change (see onCreate collector).
     */
    private fun applySystemBarAppearance() {
        val dark = isEffectiveDarkTheme()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    /**
     * Resolves the effective dark/light mode from the user's theme setting and
     * paints the window background to match, so transitions never reveal a
     * wrong-mode color. On theme changes the color ANIMATES instead of
     * snapping, so the switch feels intentional.
     * Uses the real dynamic scheme background on S+ so it matches Compose.
     */
    private fun applyWindowBackgroundForTheme(animated: Boolean) {
        val dark = isEffectiveDarkTheme()
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(this).background
            else dynamicLightColorScheme(this).background
        } else {
            if (dark) darkColorScheme().background else lightColorScheme().background
        }.toArgb()
        if (!animated || windowBgColor == 0 || windowBgColor == target) {
            windowBgAnimator?.cancel()
            window.setBackgroundDrawable(ColorDrawable(target))
            windowBgColor = target
            return
        }
        windowBgAnimator?.cancel()
        val from = windowBgColor
        windowBgAnimator = ValueAnimator.ofArgb(from, target).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                window.setBackgroundDrawable(ColorDrawable(anim.animatedValue as Int))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    windowBgColor = target
                }
            })
            start()
        }
    }

    private fun requestMessagingPermissions() {
        val needed = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

private class InboxViewModelFactory(
    private val repo: com.garrietta.relay.data.repo.UnifiedInboxRepository,
    private val categories: com.garrietta.relay.data.repo.CategoryStore,
    private val mutes: com.garrietta.relay.data.repo.MuteStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        InboxViewModel(repo, categories, mutes) as T
}

private class ChatViewModelFactory(
    private val repo: com.garrietta.relay.data.repo.UnifiedInboxRepository,
    private val appSeedStore: com.garrietta.relay.data.repo.AppSeedStore,
    private val scrollStore: com.garrietta.relay.data.repo.ChatScrollStore,
    private val aiSettingsStore: com.garrietta.relay.data.repo.AiSettingsStore,
    private val transcriptStore: com.garrietta.relay.data.repo.TranscriptStore,
    private val context: android.content.Context,
    private val threadId: Long,
    private val threadIds: List<Long> = listOf(threadId),
    private val address: String = "",
    private val rcsConversationId: String? = null,
    private val initialTransportLabel: String = "SMS",
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(repo, appSeedStore, scrollStore, aiSettingsStore, transcriptStore, context.applicationContext, threadId, threadIds, address, rcsConversationId, initialTransportLabel) as T
}

private class SettingsViewModelFactory(
    private val context: android.content.Context,
    private val repo: com.garrietta.relay.data.repo.UnifiedInboxRepository,
    private val categories: com.garrietta.relay.data.repo.CategoryStore,
    private val aiSettings: com.garrietta.relay.data.repo.AiSettingsStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(context.applicationContext, repo, categories, aiSettings) as T
}


