package com.garrietta.relay.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.garrietta.relay.RelayApp
import com.garrietta.relay.data.transport.IncomingMessage
import com.garrietta.relay.notifications.MessageNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives SMS_DELIVER (only sent to the default SMS app). Parses PDUs,
 * writes the message to the SMS provider (a default-app responsibility),
 * emits it on the SMS transport, and posts a notification.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val app = context.applicationContext as? RelayApp ?: return

        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.getStringExtra("format")
        val parts = pdus.mapNotNull { pdu ->
            try {
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } catch (_: Exception) {
                null
            }
        }
        if (parts.isEmpty()) return

        val address = parts.firstNotNullOfOrNull { it.displayOriginatingAddress }.orEmpty()
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val timestamp = parts.firstNotNullOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        if (address.isBlank() || body.isBlank()) return

        scope.launch {
            writeToProvider(context, address, body, timestamp)
            app.smsTransport.incoming.emit(
                IncomingMessage(
                    transportId = "sms",
                    address = address,
                    body = body,
                    timestamp = timestamp,
                    isMms = false,
                )
            )
            app.repository.refresh()
            val name = app.repository.resolveDisplayName(address)
            MessageNotifications.showIncoming(context, name.ifBlank { address }, body, timestamp)
        }
    }

    private fun writeToProvider(context: Context, address: String, body: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put("address", address)
                put("body", body)
                put("date", timestamp)
                put("type", 1) // MESSAGE_TYPE_INBOX
                put("read", 0)
            }
            context.contentResolver.insert(Uri.parse("content://sms/inbox"), values)
        } catch (e: Exception) {
            Log.w("Relay", "Failed to write incoming SMS to provider", e)
        }
    }
}
