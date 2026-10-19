package com.garrietta.relay

import android.app.Application
import android.content.Context
import com.garrietta.relay.data.repo.AppSeedStore
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatScrollStore
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.notifications.MessageNotifications
import com.garrietta.relay.ui.theme.AvatarShapeAssigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Per-conversation chat scroll positions (see ChatScrollStore). */
    val chatScrollStore: ChatScrollStore by lazy { ChatScrollStore(this) }

    /**
     * Application-scoped coroutine scope: survives Activity destruction.
     * The incoming-message notification collector MUST live here, not in
     * MainActivity.lifecycleScope — the old collector died with the
     * activity, so swiping the app away (or Android reclaiming it) silenced
     * all notifications even though the foreground service kept the
     * long-poll alive (Garrett 2026-10-19 evening: "still not getting
     * notifications").
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        // Incoming-message notifications: application-scoped so they fire
        // whether or not any Activity is alive (see appScope).
        startIncomingMessageNotifications()
    }

    /**
     * Incoming-message notifications (Garrett 2026-10-19: "i'm not getting
     * notifications from messages in the app"). Posts a MessagingStyle
     * notification for every INCOMING live RCS message:
     * - skipped for own outgoing/echo messages,
     * - skipped when the message's chat is currently on screen,
     * - skipped when unpaired (unpaired SMS is the default app's job),
     * - gated on the Settings → Texting toggle (default ON).
     * Runs in [appScope]: the old MainActivity-scoped collector died with
     * the activity, which is why notifications never arrived once the app
     * was swiped away. Every decision is recorded in
     * [MessageNotifications.diagnostics] for Settings → Diagnostics.
     */
    private fun startIncomingMessageNotifications() {
        appScope.launch {
            repository.observeRcsMessages().collect { msg ->
                MessageNotifications.recordIncomingObserved()
                if (msg.fromMe) {
                    MessageNotifications.recordSkipped("own outgoing/echo")
                    return@collect
                }
                if (!rcsTransport.isPairedNow) {
                    MessageNotifications.recordSkipped("not paired")
                    return@collect
                }
                if (!areMessageNotificationsEnabled()) {
                    MessageNotifications.recordSkipped("toggle off in Settings")
                    return@collect
                }
                val open = repository.openRcsConversation.value
                if (open != null && open == msg.conversationId) {
                    MessageNotifications.recordSkipped("chat open on screen")
                    return@collect
                }
                val name = repository.conversations.value
                    .firstOrNull { it.rcsConversationId == msg.conversationId }
                    ?.displayName?.ifBlank { null } ?: "New message"
                val body = msg.body.ifBlank {
                    if (msg.media != null) "\uD83D\uDCF7 Photo" else "New message"
                }
                MessageNotifications.showIncoming(this@RelayApp, name, body, msg.timestampMs)
            }
        }
    }
}

