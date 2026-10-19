package com.garrietta.relay.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.RelayApp
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.ThemeMode
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsPairingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PREFS = "relay_settings"
private const val KEY_THEME = "theme_mode"
private const val TAG = "RelaySettings"

class SettingsViewModel(
    private val context: Context,
    private val repository: UnifiedInboxRepository,
    private val categoryStore: CategoryStore,
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    val rcsPairingState: StateFlow<RcsPairingState> =
        repository.rcsTransport.pairingState
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RcsPairingState.NOT_PAIRED)

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    /**
     * User-visible messages (snackbar): the old code swallowed every failure
     * in runCatching, so a broken intent looked like "does nothing".
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private fun notify(msg: String) {
        _userMessages.tryEmit(msg)
    }

    fun openNotificationSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
        ).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    // ---- Message notifications toggle (Relay's own incoming buzz) ----

    private val app: RelayApp get() = context.applicationContext as RelayApp

    private val _notificationsEnabled =
        MutableStateFlow(app.areMessageNotificationsEnabled())
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled

    fun setNotificationsEnabled(enabled: Boolean) {
        app.setMessageNotificationsEnabled(enabled)
        _notificationsEnabled.value = enabled
    }

    // ---- Categories ----

    val categories: StateFlow<List<ChatCategory>> = categoryStore.categories
    val assignments: StateFlow<Map<String, String>> = categoryStore.assignments

    /** All conversations, for the per-category assignment UI. */
    val conversations: StateFlow<List<Conversation>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCategory(name: String) {
        if (categoryStore.addCategory(name) == null) {
            notify("Enter a category name first")
        }
    }

    fun renameCategory(id: String, name: String) = categoryStore.renameCategory(id, name)

    fun deleteCategory(id: String) {
        categoryStore.deleteCategory(id)
        notify("Category deleted")
    }

    fun moveCategory(id: String, delta: Int) = categoryStore.moveCategory(id, delta)

    /** Assigns a chat to a category (null = unassign). One category per chat. */
    fun assignChat(conversation: Conversation, categoryId: String?) {
        val key = CategoryStore.conversationKey(
            conversation.rcsConversationId, conversation.threadId,
        )
        categoryStore.assignChat(key, categoryId)
    }

    fun categoryFor(conversation: Conversation): String? =
        categoryStore.categoryFor(
            CategoryStore.conversationKey(conversation.rcsConversationId, conversation.threadId),
        )

    /**
     * Live RCS transport state for the Diagnostics card. Polled (not a
     * Flow) — the screen refreshes it on a short ticker while visible.
     */
    fun transportDiagnostics(): String =
        repository.rcsTransport.transportDiagnostics()
}
