package com.garrietta.relay.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.garrietta.relay.RelayApp
import com.garrietta.relay.notifications.MessageNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives WAP_PUSH_DELIVER for MMS (only sent to the default SMS app).
 *
 * v1 scope: the receiver is registered (a default-SMS-app manifest
 * requirement) and triggers an inbox refresh + notification. Full MMS
 * auto-download (notification PDU parse → HTTP fetch → PDU parse → provider
 * write) is a TODO — existing MMS messages already in the provider are read
 * and displayed normally.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val app = context.applicationContext as? RelayApp ?: return
        Log.i("Relay", "MMS delivery intent received; full auto-download is TODO")

        scope.launch {
            // TODO: parse the notification PDU (intent "data" extra), fetch the
            // MMS content, and write it to content://mms. For now, refresh so
            // anything the system wrote becomes visible, and notify the user.
            app.repository.refresh()
            MessageNotifications.showIncoming(
                context,
                "MMS",
                "New multimedia message received",
                System.currentTimeMillis(),
            )
        }
    }
}
