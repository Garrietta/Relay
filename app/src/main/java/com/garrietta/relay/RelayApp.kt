package com.garrietta.relay

import android.app.Application
import android.content.Context
import com.garrietta.relay.data.repo.AppSeedStore
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.notifications.MessageNotifications
import com.garrietta.relay.ui.theme.AvatarShapeAssigner

/**
 * Manual dependency graph (no DI framework for v1 — the graph is tiny).
 * Transports are app-scoped so receivers can emit into [SmsTransport.incoming].
 */
class RelayApp : Application() {

    val smsTransport: SmsTransport by lazy { SmsTransport(this) }
    val rcsTransport: RcsTransport by lazy { RcsTransport(this) }

    val repository: UnifiedInboxRepository by lazy {
        UnifiedInboxRepository(this, smsTransport, rcsTransport)
    }

    /** App-wide persistent contact color seed (see AppSeedStore). */
    val appSeedStore: AppSeedStore by lazy { AppSeedStore(this) }

    /** User-defined inbox categories + chat assignments (see CategoryStore). */
    val categoryStore: CategoryStore by lazy { CategoryStore(this) }

    private val appPrefs by lazy {
        getSharedPreferences("relay_app", Context.MODE_PRIVATE)
    }

    /**
     * Relay's own incoming-message notifications (Settings → Texting).
     * Default ON — Garrett explicitly asked for notifications from the app
     * (2026-10-19); the old code never posted any ("the default SMS app
     * owns notifying"), so nothing ever buzzed.
     */
    fun areMessageNotificationsEnabled(): Boolean =
        appPrefs.getBoolean(KEY_MSG_NOTIFICATIONS, true)

    fun setMessageNotificationsEnabled(enabled: Boolean) {
        appPrefs.edit().putBoolean(KEY_MSG_NOTIFICATIONS, enabled).apply()
    }

    /**
     * Unfolded two-pane split: fraction of the width given to the
     * conversation list (rest goes to the chat pane). Draggable via the
     * divider handle; persisted. Default 0.4 (the old 1f / 1.5f weights).
     */
    fun paneFraction(): Float =
        appPrefs.getFloat(KEY_PANE_FRACTION, 0.4f).coerceIn(0.25f, 0.75f)

    fun setPaneFraction(fraction: Float) {
        appPrefs.edit().putFloat(KEY_PANE_FRACTION, fraction.coerceIn(0.25f, 0.75f)).apply()
    }

    companion object {
        private const val KEY_MSG_NOTIFICATIONS = "msg_notifications_enabled"
        private const val KEY_PANE_FRACTION = "pane_fraction"
    }

    override fun onCreate() {
        super.onCreate()
        // Crash capture FIRST — before anything else can throw — so a crash
        // anywhere in startup still lands in the report Garrett can see in
        // Settings (no logcat access from his device).
        CrashReporter.install(this)
        MessageNotifications.ensureChannel(this)
        // Fresh avatar-shape variety on every cold start (Garrett: shapes
        // "maybe even changing shape on each startup").
        AvatarShapeAssigner.reshuffle()
        // RCS: when paired, make sure the session is up on cold start.
        // RcsTransport is lazy — touching it runs init, which starts the
        // session when a pairing exists — and the foreground service keeps
        // the long-poll alive under Doze.
        if (rcsTransport.isPairedNow) {
            com.garrietta.relay.rcs.service.RcsService.start(this)
        }
    }
}

