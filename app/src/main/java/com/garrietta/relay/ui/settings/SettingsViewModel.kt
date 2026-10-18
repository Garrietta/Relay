package com.garrietta.relay.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.ThemeMode
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsPairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PREFS = "relay_settings"
private const val KEY_THEME = "theme_mode"

class SettingsViewModel(
    private val context: Context,
    private val repository: UnifiedInboxRepository,
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

    fun isDefaultSmsApp(): Boolean = _isDefaultSmsApp.value

    /**
     * Root cause of the "Set default does nothing" report: this used to be a
     * snapshot Boolean computed once at composition. After the user confirmed
     * the system dialog and returned, the banner never disappeared, so tapping
     * again looked broken. Now it's a StateFlow refreshed in onResume.
     */
    private val _isDefaultSmsApp = MutableStateFlow(checkDefaultSmsApp())
    val isDefaultSmsAppFlow: StateFlow<Boolean> = _isDefaultSmsApp.asStateFlow()

    fun refreshDefaultSmsState() {
        _isDefaultSmsApp.value = checkDefaultSmsApp()
    }

    private fun checkDefaultSmsApp(): Boolean {
        val def = Telephony.Sms.getDefaultSmsPackage(context)
        return def == context.packageName
    }

    /**
     * Request the default-SMS role via ACTION_CHANGE_DEFAULT — the documented,
     * universal mechanism (works on all API levels).
     * Must be called with an Activity (not an application context) so the
     * system role-request dialog can attach correctly.
     */
    fun requestDefaultSmsRole(activity: Activity) {
        // ACTION_CHANGE_DEFAULT is the documented, universal way to request
        // the default-SMS role (works on all API levels; RoleManager.requestRole
        // is not a public API for this). State refreshes via onResume().
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
        }
        runCatching { activity.startActivity(intent) }
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
}
