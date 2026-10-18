package com.garrietta.relay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garrietta.relay.ui.NewConversationDialog
import com.garrietta.relay.ui.Screen
import com.garrietta.relay.ui.chat.ChatScreen
import com.garrietta.relay.ui.chat.ChatViewModel
import com.garrietta.relay.ui.inbox.InboxScreen
import com.garrietta.relay.ui.inbox.InboxViewModel
import com.garrietta.relay.ui.settings.SettingsScreen
import com.garrietta.relay.ui.settings.SettingsViewModel
import com.garrietta.relay.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {

    private val app: RelayApp get() = application as RelayApp

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* inbox refreshes on next resume; repository tolerates denial */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMessagingPermissions()

        setContent {
            val settingsVm: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(this, app.repository)
            )
            val themeMode by settingsVm.themeMode.collectAsState()

            RelayTheme(themeMode = themeMode) {
                val backStack = remember { mutableStateListOf<Screen>(Screen.Inbox) }
                fun push(s: Screen) = backStack.add(s)
                fun pop() {
                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                }
                BackHandler(enabled = backStack.size > 1) { pop() }

                when (val screen = backStack.last()) {
                    is Screen.Inbox -> {
                        val inboxVm: InboxViewModel = viewModel(
                            factory = InboxViewModelFactory(app.repository)
                        )
                        InboxScreen(
                            viewModel = inboxVm,
                            isDefaultSmsApp = settingsVm.isDefaultSmsApp(),
                            onRequestDefaultSms = settingsVm::requestDefaultSmsRole,
                            onOpenChat = {
                                push(Screen.Chat(it.threadId, it.address, it.displayName))
                            },
                            onOpenSettings = { push(Screen.Settings) },
                            onNewConversation = { push(Screen.NewConversation) },
                        )
                    }
                    is Screen.Chat -> {
                        val chatVm: ChatViewModel = viewModel(
                            key = "chat_${screen.threadId}",
                            factory = ChatViewModelFactory(app.repository, screen.threadId),
                        )
                        ChatScreen(
                            viewModel = chatVm,
                            displayName = screen.displayName,
                            recipient = screen.address,
                            onBack = ::pop,
                        )
                    }
                    is Screen.Settings -> {
                        SettingsScreen(viewModel = settingsVm, onBack = ::pop)
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

    override fun onResume() {
        super.onResume()
        app.repository.refresh()
    }

    private fun requestMessagingPermissions() {
        val needed = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_CONTACTS,
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
    private val threadId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(repo, threadId) as T
}

private class SettingsViewModelFactory(
    private val context: android.content.Context,
    private val repo: com.garrietta.relay.data.repo.UnifiedInboxRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(context.applicationContext, repo) as T
}
