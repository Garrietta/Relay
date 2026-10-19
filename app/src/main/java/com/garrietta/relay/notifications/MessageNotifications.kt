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
import androidx.core.content.ContextCompat
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

    fun showIncoming(context: Context, sender: String, body: String, timestamp: Long) {
        // Android 13+: posting without the runtime grant throws. Record the
        // block (visible in Diagnostics) and no-op; never crash.
        if (!isPermissionGranted(context)) {
            recordSkipped("POST_NOTIFICATIONS denied")
            return
        }
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(
                NotificationCompat.MessagingStyle(sender)
                    .addMessage(body, timestamp, sender)
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(sender.hashCode(), notification)
        lastPostedAt.set(System.currentTimeMillis())
        lastPostedSender.set(sender)
        postedCount.incrementAndGet()
    }
}
