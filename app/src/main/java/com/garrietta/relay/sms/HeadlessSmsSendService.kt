package com.garrietta.relay.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.garrietta.relay.RelayApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Headless quick-response service (default SMS app requirement).
 * Lets the phone app reply to calls with a text through Relay.
 */
class HeadlessSmsSendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val app = applicationContext as? RelayApp
        if (!to.isNullOrBlank() && !body.isNullOrBlank() && app != null) {
            scope.launch {
                app.smsTransport.sendMessage(to, body)
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
