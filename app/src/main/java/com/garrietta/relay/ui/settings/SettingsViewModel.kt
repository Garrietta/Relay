package com.garrietta.relay.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
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
    val unknownEnabled: StateFlow<Boolean> = categoryStore.unknownEnabled

    fun setUnknownEnabled(on: Boolean) = categoryStore.setUnknownEnabled(on)

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

    /**
     * Notification pipeline state for the Diagnostics card: runtime
     * permission, channel importance, last incoming observed, posted
     * count + last post, last skip reason. Garrett 2026-10-19 evening.
     */
    fun notificationDiagnostics(): String =
        com.garrietta.relay.notifications.MessageNotifications.diagnostics(
            context.applicationContext,
        )

    /** Tachyon token expiry (epoch ms) for Diagnostics; null when unpaired. */
    fun tachyonExpiryMs(): Long? = repository.rcsTransport.tachyonExpiryMs()

    /** Server-side conversation count (the paired inbox's source list). */
    fun rcsConversationCount(): Int =
        repository.rcsTransport.currentRcsConversations().size

    /**
     * Duplicate inbox entries unified by the participant-set merge at
     * the last refresh (Garrett's hard rule, 2026-10-20) — Diagnostics
     * shows it as mergedDuplicates=N so the merge is verifiable.
     */
    fun mergedDuplicatesCount(): Int = repository.mergedDuplicatesCount()

    data class AppInfo(
        val versionName: String,
        val versionCode: Long,
        /** "debug" or "release" — the two have separate install identities. */
        val buildType: String,
        val packageName: String,
        val androidRelease: String,
        val apiLevel: Int,
        val device: String,
        val firstInstallMs: Long,
        val lastUpdateMs: Long,
    )

    /** Static app/device facts for the Diagnostics screen. All from the OS. */
    fun appInfo(): AppInfo {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            pi.versionCode.toLong()
        }
        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return AppInfo(
            versionName = pi.versionName ?: "?",
            versionCode = code,
            buildType = if (debuggable) "debug" else "release",
            packageName = context.packageName,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            apiLevel = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            firstInstallMs = pi.firstInstallTime,
            lastUpdateMs = pi.lastUpdateTime,
        )
    }

    data class StorageStats(
        val mediaFiles: Int,
        val mediaBytes: Long,
        val historyFiles: Int,
        val historyBytes: Long,
    )

    /**
     * On-disk cache sizes for Diagnostics: the RCS media cache
     * (cacheDir/rcs_media/v3) and the chat-history LRU (filesDir/chat_history).
     * Blocking file walk — call off the main thread.
     */
    fun storageStats(): StorageStats {
        fun walk(dir: java.io.File): Pair<Int, Long> {
            if (!dir.exists()) return 0 to 0L
            var files = 0
            var bytes = 0L
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    files++
                    bytes += f.length()
                }
            }
            return files to bytes
        }
        val media = walk(java.io.File(context.cacheDir, "rcs_media/v3"))
        val history = walk(java.io.File(context.filesDir, "chat_history"))
        return StorageStats(media.first, media.second, history.first, history.second)
    }
}
