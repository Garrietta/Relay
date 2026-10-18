package com.garrietta.relay.ui.settings

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

    /**
     * Source of truth for "is Relay the default SMS app".
     * API 29+: RoleManager.isRoleHeld(ROLE_SMS) — the role system's own answer.
     * Below 29: Telephony.Sms.getDefaultSmsPackage() fallback.
     * Either one saying yes hides the banner/row (belt and suspenders against
     * any device where the two disagree).
     */
    private fun checkDefaultSmsApp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm?.isRoleHeld(RoleManager.ROLE_SMS) == true) return true
        }
        return try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the role-request intent for the Activity Result API
     * (ActivityResultContracts.StartActivityForResult).
     *
     * API 29+: RoleManager.createRequestRoleIntent(ROLE_SMS) — the modern,
     * result-bearing mechanism (RESULT_OK = granted, RESULT_CANCELED = not).
     * Below 29: the legacy ACTION_CHANGE_DEFAULT intent.
     * Returns null when the RoleManager path is unavailable — the caller then
     * cascades to openDefaultAppsSettings().
     */
    fun smsRoleRequestIntent(activity: Activity): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm == null) {
                Log.w(TAG, "smsRoleRequestIntent: RoleManager unavailable; cascading")
                return null
            }
            if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                Log.w(TAG, "smsRoleRequestIntent: ROLE_SMS not available; cascading")
                return null
            }
            Log.d(TAG, "smsRoleRequestIntent: RoleManager.createRequestRoleIntent(ROLE_SMS)")
            return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
        }
        Log.d(TAG, "smsRoleRequestIntent: legacy ACTION_CHANGE_DEFAULT (API < 29)")
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
        }
    }

    /**
     * Called from the Activity Result launcher when the role-request flow
     * resolves. Refreshes state immediately (no waiting for onResume) and
     * cascades visibly when the grant didn't happen.
     */
    fun onSmsRoleResult(activity: Activity, granted: Boolean) {
        refreshDefaultSmsState()
        if (granted) {
            Log.d(TAG, "onSmsRoleResult: SMS role GRANTED")
            notify("Relay is now your default texting app.")
        } else {
            Log.w(TAG, "onSmsRoleResult: not granted (RESULT_CANCELED); cascading to system settings")
            notify("That didn't switch it — opening system settings so you can pick Relay manually.")
            openDefaultAppsSettings(activity)
        }
    }

    /**
     * Cascading fallbacks — every tap must produce something visible:
     *  1. RoleManager request intent (primary path, launched via Activity Result).
     *  2. System default-apps settings screen (manual switch).
     *  3. This app's own App info page (tap "SMS app" there).
     * Every step is user-visible (snackbar) or logged (RelaySettings tag).
     */
    fun openDefaultAppsSettings(activity: Activity) {
        val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        if (fallback.resolveActivity(activity.packageManager) == null) {
            Log.e(TAG, "No default-apps settings activity found; falling to app details")
            notify("Couldn't open system settings; opening app info instead.")
            openAppDetails(activity)
            return
        }
        try {
            activity.startActivity(fallback)
            Log.d(TAG, "openDefaultAppsSettings: system default-apps screen launched")
            notify("Pick Relay as the SMS app in system settings.")
        } catch (e: Exception) {
            Log.e(TAG, "openDefaultAppsSettings failed; falling to app details", e)
            notify("Couldn't open system settings; opening app info instead.")
            openAppDetails(activity)
        }
    }

    private fun openAppDetails(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            Log.e(TAG, "No app-details settings activity found; out of fallbacks")
            notify("Couldn't open settings. Please set Relay as the SMS app manually in system Settings.")
            return
        }
        try {
            activity.startActivity(intent)
            Log.d(TAG, "openAppDetails: app info screen launched")
            notify("In App info, tap \"SMS app\" and choose Relay.")
        } catch (e: Exception) {
            Log.e(TAG, "openAppDetails failed; out of fallbacks", e)
            notify("Couldn't open settings. Please set Relay as the SMS app manually in system Settings.")
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
