package com.garrietta.relay.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.garrietta.relay.MainActivity
import com.garrietta.relay.R

/**
 * Incoming-message notifications for Relay itself. (The old comment here
 * said "the default SMS app owns notifying" — true for Telephony SMS, but
 * Garrett explicitly asked for Relay to notify on incoming messages, so
 * live RCS arrivals post here. The collector in MainActivity skips the
 * currently-open chat and respects the Settings toggle.)
 */
object MessageNotifications {
    private const val CHANNEL_ID = "relay_messages"

    /**
     * Deep-link extras on the content intent (Garrett 2026-10-20): the
     * tapped notification names its conversation — by SERVER id, the
     * one identity every surface agrees on — and the message it was
     * posted for. MainActivity turns these into a
     * [NotificationOpenRequest]; without them the tap could only bring
     * the app forward, which is the bug he reported.
     */
    const val EXTRA_OPEN_CONVERSATION_ID = "com.garrietta.relay.extra.OPEN_CONVERSATION_ID"
    const val EXTRA_OPEN_MESSAGE_ID = "com.garrietta.relay.extra.OPEN_MESSAGE_ID"

    /** The notification id Relay posts under for [conversationId]. */
    fun notificationIdFor(conversationId: String): Int = conversationId.hashCode()

    /**
     * Clears this conversation's posted notification (Garrett
     * 2026-10-20): opening the chat — by ANY path, row tap or
     * notification tap — retires its shade entry. (A tapped
     * notification also auto-cancels itself; this covers the manual
     * opens that used to leave it sitting in the shade as unread.)
     */
    fun cancelForConversation(context: Context, conversationId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(notificationIdFor(conversationId))
    }

    // Pipeline diagnostics (Garrett 2026-10-19 evening: "still not getting
    // notifications" — surfaced in Settings → Diagnostics so the failure
    // point is visible without logcat).
    private val lastIncomingAt = java.util.concurrent.atomic.AtomicLong(0)
    private val lastPostedAt = java.util.concurrent.atomic.AtomicLong(0)
    private val lastPostedSender = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val postedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastSkipReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastSkipAt = java.util.concurrent.atomic.AtomicLong(0)

    /** Called for every live incoming message, before any skip decision. */
    fun recordIncomingObserved() {
        lastIncomingAt.set(System.currentTimeMillis())
    }

    /** Called when a message is deliberately not notified. */
    fun recordSkipped(reason: String) {
        lastSkipReason.set(reason)
        lastSkipAt.set(System.currentTimeMillis())
    }

    /** True when the runtime POST_NOTIFICATIONS grant is held (Android 13+). */
    fun isPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** The channel's current importance, or null when the channel is missing. */
    fun channelImportance(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return null
        return manager.getNotificationChannel(CHANNEL_ID)?.importance
    }

    private fun ageAgo(at: Long): String {
        if (at == 0L) return "never"
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            else -> "${s / 3600}h ago"
        }
    }

    /** One-line pipeline state for Settings → Diagnostics. */
    fun diagnostics(context: Context): String {
        val perm = if (isPermissionGranted(context)) "granted" else "DENIED"
        val imp = channelImportance(context)?.toString() ?: "no channel"
        return "perm=$perm, channelImportance=$imp, " +
            "lastIncoming=${ageAgo(lastIncomingAt.get())}, " +
            "posted=${postedCount.get()} (last ${ageAgo(lastPostedAt.get())}" +
            (lastPostedSender.get()?.let { " from $it" } ?: "") + "), " +
            "lastSkip=${lastSkipReason.get() ?: "none"} (${ageAgo(lastSkipAt.get())})"
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Incoming text messages" }
        )
    }

    /**
     * Posts an incoming-message notification, Google Messages style
     * (Garrett 2026-10-19 22:00): for GROUP chats the MessagingStyle
     * message carries the SENDER as a Person (name + photo), with the
     * conversation title as the group header — not the generic icon +
     * conversation-name-as-sender the old build showed. The sender's photo
     * is clipped to their per-contact expressive M3E shape
     * ([NotificationAvatar]); Mark as read + inline Reply actions included.
     */
    fun showIncoming(
        context: Context,
        conversationId: String,
        messageId: String,
        conversationTitle: String,
        senderName: String,
        senderNumber: String,
        body: String,
        timestamp: Long,
        photoUri: String? = null,
        isGroup: Boolean = false,
    ) {
        // Android 13+: posting without the runtime grant throws. Record the
        // block (visible in Diagnostics) and no-op; never crash.
        if (!isPermissionGranted(context)) {
            recordSkipped("POST_NOTIFICATIONS denied")
            return
        }
        ensureChannel(context)
        // The tap must land IN this conversation, on this message
        // (Garrett 2026-10-20): the intent carries the server
        // conversation id + the message's server id as extras, and the
        // PendingIntent identity is per-conversation — the request
        // code is the conversation id's hash, so different chats'
        // taps can never cross (FLAG_UPDATE_CURRENT refreshes the
        // extras in place, so the SAME chat's pending intent always
        // names its LATEST notified message).
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_OPEN_MESSAGE_ID, messageId)
        }
        val pending = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Expressive-shape sender avatar (never the generic person icon):
        // photo clipped to the contact's M3E shape, or the initial-letter
        // avatar in the same shape when there's no photo.
        val avatar = NotificationAvatar.shapedAvatar(
            context,
            photoUri,
            shapeKey = senderNumber.ifBlank { senderName },
            displayName = senderName,
        )
        val sender = Person.Builder()
            .setName(senderName)
            .apply { avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()
        val style = NotificationCompat.MessagingStyle(sender)
            .addMessage(body, timestamp, sender)
        if (isGroup) style.conversationTitle = conversationTitle
        val notifId = conversationId.hashCode()
        // Mark as read.
        val markReadPending = PendingIntent.getBroadcast(
            context,
            notifId,
            NotificationActionReceiver.intent(
                context,
                NotificationActionReceiver.ACTION_MARK_READ,
                conversationId, notifId,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inline reply (RemoteInput needs a MUTABLE PendingIntent).
        val replyPending = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            NotificationActionReceiver.intent(
                context,
                NotificationActionReceiver.ACTION_REPLY,
                conversationId, notifId,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Reply",
            replyPending,
        ).addRemoteInput(
            RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
                .setLabel("Reply")
                .build(),
        ).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .apply { avatar?.let { setLargeIcon(it) } }
            .setStyle(style)
            .setContentIntent(pending)
            .addAction(
                R.drawable.ic_notification,
                "Mark as read",
                markReadPending,
            )
            .addAction(replyAction)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notifId, notification)
        lastPostedAt.set(System.currentTimeMillis())
        lastPostedSender.set(if (isGroup) "$senderName ($conversationTitle)" else senderName)
        postedCount.incrementAndGet()
    }

}
