package com.garrietta.relay.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.garrietta.relay.RelayApp
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Notification actions (Garrett 2026-10-19 22:00): Mark as read + inline
 * Reply on incoming-message notifications, Google Messages style.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? RelayApp ?: return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        when (intent.action) {
            ACTION_MARK_READ -> {
                app.appScope.launch {
                    runCatching { app.repository.markRcsRead(conversationId) }
                    cancel(context, notifId)
                }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                app.appScope.launch {
                    runCatching {
                        app.repository.sendRcs(conversationId, text, UUID.randomUUID().toString())
                    }
                    cancel(context, notifId)
                }
            }
        }
    }

    private fun cancel(context: Context, notifId: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notifId)
    }

    companion object {
        const val ACTION_MARK_READ = "com.garrietta.relay.action.MARK_READ"
        const val ACTION_REPLY = "com.garrietta.relay.action.REPLY"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "relay_reply_text"

        /** Explicit intent for one conversation + action (never implicit). */
        fun intent(
            context: Context,
            action: String,
            conversationId: String,
            notifId: Int,
        ): Intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_NOTIF_ID, notifId)
    }
}
