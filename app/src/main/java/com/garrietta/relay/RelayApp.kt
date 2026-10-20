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
import kotlinx.coroutines.delay
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
     *
     * TWO feeds drive the single [handleIncomingRcsMessage] decision point:
     * 1. the long-poll push flow (session.incoming), and
     * 2. a 45s poll fallback (Garrett 2026-10-19 evening: Diagnostics showed
     *    lastIncoming=never — the push flow never emits on his device while
     *    the inbox works fine via refresh polling, so the push path alone
     *    can never notify). The poll detects newly-arrived incoming messages
     *    from conversation timestamps. Exactly-once via [notifiedMessageIds].
     */
    private fun startIncomingMessageNotifications() {
        // Push path: fires when the long-poll delivers UpdateEvent.Messages.
        appScope.launch {
            repository.observeRcsMessages().collect { msg ->
                handleIncomingRcsMessage(msg)
            }
        }
        // Poll fallback: the push flow is silent on Garrett's device, so
        // detect arrivals from the conversation list instead. Baseline first
        // (no notifications for pre-existing messages), then poll.
        appScope.launch {
            baselineNotificationState()
            while (true) {
                delay(45_000)
                runCatching { pollForIncomingNotifications() }
            }
        }
    }

    /** Message IDs already passed through the notifier (push + poll dedupe). */
    private val notifiedMessageIds = mutableSetOf<String>()

    /** Per-conversation latest timestamp seen by the poll fallback. */
    private val notifierSeenTs = mutableMapOf<String, Long>()

    /**
     * Shared per-message notification decision + posting. Both the push flow
     * and the poll fallback feed this; [notifiedMessageIds] keeps it
     * exactly-once. [MessageNotifications.recordIncomingObserved] fires for
     * every observed incoming (even skipped/dupes) so Diagnostics shows
     * traffic.
     */
    private suspend fun handleIncomingRcsMessage(
        msg: com.garrietta.relay.rcs.proto.RcsMessage,
    ) {
        MessageNotifications.recordIncomingObserved()
        if (!notifiedMessageIds.add(msg.id)) return
        // LRU history: every new message bumps its conversation to the
        // front of the 50-chat active set (Garrett 2026-10-19).
        repository.touchHistory(msg.conversationId)
        if (notifiedMessageIds.size > 500) {
            // Bound the dedupe set: drop the oldest half.
            notifiedMessageIds.drop(250).forEach { notifiedMessageIds.remove(it) }
        }
        if (msg.fromMe) {
            MessageNotifications.recordSkipped("own outgoing/echo")
            return
        }
        if (!rcsTransport.isPairedNow) {
            MessageNotifications.recordSkipped("not paired")
            return
        }
        if (!areMessageNotificationsEnabled()) {
            MessageNotifications.recordSkipped("toggle off in Settings")
            return
        }
        val open = repository.openRcsConversation.value
        if (open != null && open == msg.conversationId) {
            MessageNotifications.recordSkipped("chat open on screen")
            return
        }
        val name = repository.conversations.value
            .firstOrNull { it.rcsConversationId == msg.conversationId }
            ?.displayName?.ifBlank { null } ?: "New message"
        val body = msg.body.ifBlank {
            if (msg.media != null) "\uD83D\uDCF7 Photo" else "New message"
        }
        // Garrett 2026-10-19: notifications carry the sender's contact photo
        // (like Google Messages). The sender is the message's participant in
        // a group, else the non-self participant in a 1:1.
        val senderNumber = runCatching {
            rcsTransport.currentRcsConversations()
                .firstOrNull { it.id == msg.conversationId }
                ?.participants
                ?.let { parts ->
                    (parts.firstOrNull { it.participantId.isNotEmpty() && it.participantId == msg.participantId }
                        ?: parts.firstOrNull { !it.isMe })
                        ?.number?.ifBlank { null }
                }
        }.getOrNull()
        val photoUri = senderNumber?.let { runCatching { repository.resolvePhotoUri(it) }.getOrNull() }
        MessageNotifications.showIncoming(this@RelayApp, name, body, msg.timestampMs, photoUri)
        // Garrett 2026-10-19: a posted notification FORCES the RCS refresh
        // (the same one the Diagnostics Refresh button does) so the inbox
        // and chat panes pick the message up — notification and UI stay
        // in sync instead of the notification arriving alone.
        repository.refresh()
    }

    /** First poll baseline: everything currently on the server is "seen". */
    private suspend fun baselineNotificationState() {
        if (!rcsTransport.isPairedNow) return
        rcsTransport.refreshConversations()
        for (conv in rcsTransport.currentRcsConversations()) {
            notifierSeenTs[conv.id] = conv.timestampMs
        }
    }

    /**
     * Poll fallback: detect newly-arrived INCOMING messages from conversation
     * timestamps and route them through [handleIncomingRcsMessage]. A
     * timestamp advance alone isn't enough (our own outgoing messages advance
     * it too) — the latest message is loaded and checked with [fromMe].
     */
    private suspend fun pollForIncomingNotifications() {
        if (!rcsTransport.isPairedNow) return
        rcsTransport.refreshConversations()
        for (conv in rcsTransport.currentRcsConversations()) {
            val lastSeen = notifierSeenTs[conv.id]
            if (lastSeen == null) {
                // Conversation we haven't baselined (created while away) —
                // baseline without notifying for its history.
                notifierSeenTs[conv.id] = conv.timestampMs
                continue
            }
            if (conv.timestampMs <= lastSeen) continue
            notifierSeenTs[conv.id] = conv.timestampMs
            // Latest activity — load recent messages and notify for every new
            // INCOMING one (oldest first). A timestamp advance alone isn't
            // enough: our own outgoing messages advance it too, and several
            // messages can land between polls.
            val recent = runCatching {
                rcsTransport.rcsMessages(conv.id, conv.participants, 5)
            }.getOrDefault(emptyList())
            val newIncoming = recent
                .filter { !it.fromMe && it.timestampMs > lastSeen }
                .sortedBy { it.timestampMs }
            if (newIncoming.isEmpty()) {
                MessageNotifications.recordIncomingObserved()
                MessageNotifications.recordSkipped("own outgoing/echo")
            } else {
                newIncoming.forEach { handleIncomingRcsMessage(it) }
            }
        }
    }
}

