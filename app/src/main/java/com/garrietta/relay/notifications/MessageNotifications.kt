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
        // Android 13+: posting without the runtime grant throws. Fail silent
        // (the permission is requested in MainActivity); never crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
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
    }
}
