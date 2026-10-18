package com.garrietta.relay.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.ThemeMode
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsPairingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val PREFS = "relay_settings"
private const val KEY_THEME = "theme_mode"
private const val TAG = "RelaySettings"

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
     * User-visible messages (snackbar): the old code swallowed every failure
     * in runCatching, so a broken intent looked like "does nothing".
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private fun notify(msg: String) {
        _userMessages.tryEmit(msg)
    }

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
     *
     * Round 2: failures are now surfaced instead of swallowed. We verify the
     * intent resolves via PackageManager first (needs the <queries> entries in
     * the manifest on API 30+); if it doesn't resolve, we fall back to the
     * system default-apps settings screen so the user can always switch
     * manually. State refreshes via onResume().
     */
    fun requestDefaultSmsRole(activity: Activity) {
        Log.d(TAG, "requestDefaultSmsRole: tap received")
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
        }
        val pm: PackageManager = activity.packageManager
        val primaryResolves = intent.resolveActivity(pm) != null
        Log.d(TAG, "requestDefaultSmsRole: ACTION_CHANGE_DEFAULT resolves=$primaryResolves")
        if (!primaryResolves) {
            Log.w(TAG, "ACTION_CHANGE_DEFAULT did not resolve; falling back to system settings")
            notify("Couldn't open the SMS picker; opening system settings instead.")
            openDefaultAppsSettings(activity)
            return
        }
        try {
            activity.startActivity(intent)
            Log.d(TAG, "requestDefaultSmsRole: system picker launched")
        } catch (e: Exception) {
            Log.e(TAG, "requestDefaultSmsRole: ACTION_CHANGE_DEFAULT failed", e)
            notify("Couldn't open the SMS picker (${e.javaClass.simpleName}); opening system settings instead.")
            openDefaultAppsSettings(activity)
        }
    }

    private fun openDefaultAppsSettings(activity: Activity) {
        val fallback = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        if (fallback.resolveActivity(activity.packageManager) == null) {
            Log.e(TAG, "No default-apps settings activity found")
            notify("Couldn't open system settings on this device.")
            return
        }
        try {
            activity.startActivity(fallback)
            notify("Pick Relay as the SMS app in system settings.")
        } catch (e: Exception) {
            Log.e(TAG, "openDefaultAppsSettings failed", e)
            notify("Couldn't open system settings: ${e.message}")
        }
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
