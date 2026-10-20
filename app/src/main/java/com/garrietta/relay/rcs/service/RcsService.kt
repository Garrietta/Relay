package com.garrietta.relay.rcs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.garrietta.relay.MainActivity
import com.garrietta.relay.R

/**
 * Keeps the RCS long-poll alive in the background.
 *
 * Android 14+ kills a persistent long-poll within minutes under Doze unless it
 * runs as a foreground service. Declared with the remoteMessaging type (the
 * semantically correct type for a messaging connection). Shows a persistent
 * notification while connected — the UX price of server-grade RCS delivery
 * without cloud push infrastructure. A future FCM-based wake could drop it.
 */
class RcsService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Pure keep-alive: the RcsTransport owns the session; this service's
        // only job is to keep the process in the foreground so Doze doesn't
        // kill the long-poll. If we're not paired, there's nothing to keep alive.
        val paired = com.garrietta.relay.rcs.session.AuthStore(this).load()?.hasToken == true
        if (!paired) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        ensureChannel()
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Relay RCS")
            .setContentText("Connected — listening for RCS messages")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "RCS connection", NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "relay_rcs_connection"
        private const val NOTIF_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, RcsService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RcsService::class.java))
        }
    }
}
