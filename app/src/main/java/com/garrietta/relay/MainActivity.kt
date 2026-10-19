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
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
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
import com.garrietta.relay.ui.chat.ChatScreen
import com.garrietta.relay.ui.chat.ChatViewModel
import com.garrietta.relay.ui.inbox.InboxScreen
import com.garrietta.relay.ui.inbox.InboxViewModel
import com.garrietta.relay.ui.motion.LocalAnimatedVisibilityScope
import com.garrietta.relay.ui.motion.LocalNavForward
import com.garrietta.relay.ui.motion.LocalSharedTransitionScope
import com.garrietta.relay.ui.motion.MotionKind
import com.garrietta.relay.ui.motion.rememberMotionTransforms
import com.garrietta.relay.ui.settings.SettingsScreen
import com.garrietta.relay.ui.settings.SettingsViewModel
import com.garrietta.relay.ui.theme.RelayTheme
import com.garrietta.relay.rcs.ui.RcsPairingScreen
import com.garrietta.relay.ui.theme.WavyBlobShapeCalm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app: RelayApp get() = application as RelayApp

    // Hoisted to activity level so onResume can refresh the default-SMS state
    // (the banner must disappear the moment the role is granted).
    private val settingsVm: SettingsViewModel by viewModels {
        SettingsViewModelFactory(applicationContext, (application as RelayApp).repository)
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
        // #45 regression fix: after a pop transition finishes, restore
        // navForward to true. If we leave it false, the settled inbox keeps
        // its shared-element modifiers INACTIVE (LocalNavForward=false), so
        // the next push's outgoing row never registers its shared bounds —
        // silently killing the avatar morph. The delay exceeds BACKWARD's
        // 400ms total; any new navigation cancels it via the backStack key.
        LaunchedEffect(backStack.last()) {
            if (!navForward) {
                delay(550)
                navForward = true
            }
        }
        BackHandler(enabled = backStack.size > 1) { pop() }

        // Container-transform navigation: conversation avatar morphs
        // into the chat header via shared bounds (see ui.motion).
        SharedTransitionLayout {
            val motionTransforms = rememberMotionTransforms()
            AnimatedContent(
                targetState = backStack.last(),
                transitionSpec = {
                    motionTransforms[if (navForward) MotionKind.FORWARD else MotionKind.BACKWARD]
                },
                label = "relay-nav",
            ) { screen ->
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalAnimatedVisibilityScope provides this@AnimatedContent,
                    // Container shared-element morphs run on push only. On pop
                    // the shared bounds are disabled so the exiting chat fades
                    // and shrinks within its own bounds — nothing from the chat
                    // may draw above the incoming inbox (e.g. thread text
                    // ghosting over the "New conversation" pill). The avatar
                    // is exempt via motionSharedAvatar: it reverse-morphs
                    // down into its row on pop too.
                    LocalNavForward provides navForward,
                ) {
                    when (screen) {
                        is Screen.Inbox -> {
                            val inboxVm: InboxViewModel = viewModel(
                                factory = InboxViewModelFactory(app.repository)
                            )
                            InboxScreen(
                                viewModel = inboxVm,
                                userMessages = settingsVm.userMessages,
                                onOpenChat = {
                                    push(Screen.Chat(it.threadId, it.address, it.displayName, it.threadIds, it.transportLabel, it.rcsConversationId))
                                },
                                onOpenSettings = { push(Screen.Settings) },
                                onNewConversation = { push(Screen.NewConversation) },
                            )
                        }
                        is Screen.Chat -> {
                            val chatVm: ChatViewModel = viewModel(
                                key = "chat_${screen.threadId}",
                                factory = ChatViewModelFactory(app.repository, app.appSeedStore, screen.threadId, screen.threadIds, screen.address, screen.rcsConversationId),
                            )
                            ChatScreen(
                                viewModel = chatVm,
                                displayName = screen.displayName,
                                recipient = screen.address,
                                threadId = screen.threadId,
                                threadIds = screen.threadIds,
                                transportLabel = screen.transportLabel,
                                rcsConversationId = screen.rcsConversationId,
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
                        is Screen.NewConversation -> {
                            NewConversationDialog(
                                onDismiss = ::pop,
                                onStart = { number ->
                                    pop()
                                    // threadId unknown until first message; use -1 placeholder.
                                    push(Screen.Chat(-1L, number, number))
                                },
                            )
                        }
                    }
                }
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
        var showRcsPairing by remember { mutableStateOf(false) }
        BackHandler(enabled = showSettings) { showSettings = false }
        BackHandler(enabled = !showSettings && showRcsPairing) { showRcsPairing = false }
        BackHandler(enabled = !showSettings && selected != null) { selected = null }

        SharedTransitionLayout {
            val motionTransforms = rememberMotionTransforms()
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // List pane: its own rounded container.
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val inboxVm: InboxViewModel = viewModel(
                            factory = InboxViewModelFactory(app.repository)
                        )
                        InboxScreen(
                            viewModel = inboxVm,
                            userMessages = settingsVm.userMessages,
                            onOpenChat = { selected = it },
                            onOpenSettings = { showSettings = true },
                            onNewConversation = { showNewConversation = true },
                        )
                    }
                    // Detail pane: its own rounded container; crossfades
                    // between the placeholder and the selected chat — no hard
                    // cut, no bare-background gap.
                    Surface(
                        modifier = Modifier
                            .weight(1.5f)
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
                            ) {
                                if (convo == null) {
                                    ConversationPlaceholder()
                                } else {
                                    val chatVm: ChatViewModel = viewModel(
                                        key = "chat_${convo.threadId}",
                                        factory = ChatViewModelFactory(app.repository, app.appSeedStore, convo.threadId, convo.threadIds, convo.address, convo.rcsConversationId),
                                    )
                                    ChatScreen(
                                        viewModel = chatVm,
                                        displayName = convo.displayName,
                                        recipient = convo.address,
                                        threadId = convo.threadId,
                                        threadIds = convo.threadIds,
                                        transportLabel = convo.transportLabel,
                                        rcsConversationId = convo.rcsConversationId,
                                        onBack = { selected = null },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showNewConversation) {
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
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        InboxViewModel(repo) as T
}

private class ChatViewModelFactory(
    private val repo: com.garrietta.relay.data.repo.UnifiedInboxRepository,
    private val appSeedStore: com.garrietta.relay.data.repo.AppSeedStore,
    private val threadId: Long,
    private val threadIds: List<Long> = listOf(threadId),
    private val address: String = "",
    private val rcsConversationId: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(repo, appSeedStore, threadId, threadIds, address, rcsConversationId) as T
}

private class SettingsViewModelFactory(
    private val context: android.content.Context,
    private val repo: com.garrietta.relay.data.repo.UnifiedInboxRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(context.applicationContext, repo) as T
}


