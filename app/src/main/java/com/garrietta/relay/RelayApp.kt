package com.garrietta.relay

import android.app.Application
import android.content.Context
import com.garrietta.relay.data.model.allRcsIds
import com.garrietta.relay.data.repo.AppSeedStore
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatScrollStore
import com.garrietta.relay.data.repo.ChatThemeStore
import com.garrietta.relay.data.repo.MuteStore
import com.garrietta.relay.data.repo.StickerRecentsStore
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

    /** Per-conversation true mute for notifications (see MuteStore). */
    val muteStore: MuteStore by lazy { MuteStore(this) }

    /** Per-conversation chat themes — local presentation (see ChatThemeStore). */
    val chatThemeStore: ChatThemeStore by lazy { ChatThemeStore(this) }

    /** Recently used stickers, most recent first (see StickerRecentsStore). */
    val stickerRecentsStore: StickerRecentsStore by lazy { StickerRecentsStore(this) }

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
        // GIF support (Garrett 2026-10-19: "gifs should be usable and
        // visible too"): register Coil's animated-GIF decoders on the
        // app-wide ImageLoader so received GIFs animate inline in bubbles
        // AND in the fullscreen viewer. ImageDecoderDecoder (API 28+)
        // animates via ImageDecoder; GifDecoder covers API 26-27
        // (minSdk 26). coil-gif is in app/build.gradle.kts.
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(coil.decode.ImageDecoderDecoder.Factory())
                    } else {
                        add(coil.decode.GifDecoder.Factory())
                    }
                }
                .build(),
        )
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
     * - skipped when the conversation is muted in its details page —
     *   TRUE mute (Garrett 2026-10-20): nothing posts at all, no
     *   heads-up, no sound, while the inbox still updates normally.
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
        // TRUE per-chat mute (Garrett 2026-10-20): this is the single
        // suppression choke point — both the push flow and the 45s poll
        // fallback feed this function, so a muted conversation can never
        // post through either path. The key for an RCS conversation IS
        // its server conversation id (CategoryStore.conversationKey).
        // The mute key for an RCS conversation IS its own server
        // conversation id — one conversation, one key, no cross-
        // conversation resolution (the merge fan-out was removed
        // 2026-10-20).
        if (muteStore.isMuted(msg.conversationId)) {
            MessageNotifications.recordSkipped("muted")
            return
        }
        val open = repository.openRcsConversation.value
        if (open != null && msg.conversationId == open) {
            MessageNotifications.recordSkipped("chat open on screen")
            return
        }
                val convTitle = repository.conversations.value
            .firstOrNull { msg.conversationId in it.allRcsIds() }
            ?.displayName?.ifBlank { null } ?: "New message"
        val body = msg.body.ifBlank {
            when {
                msg.media != null -> "\uD83D\uDCF7 Photo"
                // A bare location pin (no text part rode along): name it.
                msg.location != null -> "\uD83D\uDCCD " +
                    (msg.location?.name?.ifBlank { null } ?: "Location")
                else -> "New message"
            }
        }
        // Group notifications identify the SENDER (Garrett 2026-10-19
        // 22:00, Google Messages parity): the message's participant (via
        // participantId) is resolved to their contact name + photo; the
        // conversation title is the group header, not the sender. The old
        // code passed the conversation title AS the sender, so a group
        // notification just showed "GIRTH / Good boy" with a generic icon.
        val senderInfo = runCatching {
            val parts = rcsTransport.currentRcsConversations()
                .firstOrNull { it.id == msg.conversationId }
                ?.participants.orEmpty()
            val sender = parts.firstOrNull {
                it.participantId.isNotEmpty() && it.participantId == msg.participantId
            } ?: parts.firstOrNull { !it.isMe }
            val number = sender?.number?.ifBlank { null }
            val isGroup = parts.count { !it.isMe } > 1
            val contactName = number?.let {
                runCatching { repository.resolveDisplayName(it) }.getOrNull()
            }?.takeIf { it.isNotBlank() && it != number }
            val name = contactName
                ?: sender?.fullName?.ifBlank { null }
                ?: number
                ?: "Unknown sender"
            Triple(name, number ?: "", isGroup)
        }.getOrNull() ?: Triple("Unknown sender", "", false)
        val (senderName, senderNumber, isGroup) = senderInfo
        val photoUri = senderNumber.ifBlank { null }?.let {
            runCatching { repository.resolvePhotoUri(it) }.getOrNull()
        }
        MessageNotifications.showIncoming(
            context = this@RelayApp,
            conversationId = msg.conversationId,
            messageId = msg.id,
            conversationTitle = convTitle,
            senderName = senderName,
            senderNumber = senderNumber,
            body = body,
            timestamp = msg.timestampMs,
            photoUri = photoUri,
            isGroup = isGroup,
        )
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

