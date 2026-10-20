package com.garrietta.relay.notifications

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
        when (intent.action) {
            ACTION_MARK_READ -> {
                app.appScope.launch {
                    runCatching { app.repository.markRcsRead(conversationId) }
                    cancel(context, conversationId)
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
                    // A sent reply means the conversation is read:
                    // retire the notification AND its missed-message
                    // backlog, same as Mark as read (Garrett
                    // 2026-10-20 — the backlog clears exactly where
                    // the notification is cancelled).
                    cancel(context, conversationId)
                }
            }
        }
    }

    /**
     * Retires the conversation's notification through the ONE seam
     * that also clears its missed-message backlog —
     * [MessageNotifications.cancelForConversation] cancels the same
     * id this receiver used to cancel directly
     * (notificationIdFor(conversationId) == the EXTRA_NOTIF_ID the
     * actions were built with).
     */
    private fun cancel(context: Context, conversationId: String) {
        MessageNotifications.cancelForConversation(context, conversationId)
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
