package com.garrietta.relay.data.transport

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * SMS/MMS transport over public Android APIs.
 *
 * Sending: [SmsManager] (multipart-aware). Writing sent messages to the SMS
 * provider is best-effort — only the default SMS app may write there, and
 * Relay is deliberately NOT the default (Google Messages keeps that role).
 *
 * Receiving: incoming SMS/MMS arrives in Google Messages (the default app);
 * Relay reads the provider read-only (READ_SMS) as the unpaired fallback.
 * When RCS-paired, the server sync is the single source of truth instead.
 */
class SmsTransport(private val context: Context) : Transport {

    override val id: String = "sms"
    override val displayName: String = "SMS"
    override val isAvailable: Flow<Boolean> = flowOf(true)

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 16)
    val incoming: MutableSharedFlow<IncomingMessage> = _incoming

    override fun observeIncoming(): Flow<IncomingMessage> = _incoming.asSharedFlow()

    override suspend fun sendMessage(recipient: String, body: String): SendResult =
        withContext(Dispatchers.IO) {
            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                } ?: return@withContext SendResult.Failed("SmsManager unavailable")

                val parts = smsManager.divideMessage(body)
                if (parts.size <= 1) {
                    smsManager.sendTextMessage(recipient, null, body, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
                }
                writeSentToProvider(recipient, body)
                SendResult.Sent
            } catch (e: SecurityException) {
                SendResult.Failed("SEND_SMS permission missing")
            } catch (e: IllegalArgumentException) {
                SendResult.Failed("Invalid recipient or message")
            } catch (e: Exception) {
                SendResult.Failed(e.message ?: "Send failed")
            }
        }

    private fun writeSentToProvider(recipient: String, body: String) {
        try {
            val values = ContentValues().apply {
                put("address", recipient)
                put("body", body)
                put("date", System.currentTimeMillis())
                put("type", 2) // MESSAGE_TYPE_SENT
                put("read", 1)
            }
            context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
        } catch (_: Exception) {
            // Best effort: sending already succeeded.
        }
    }
}
