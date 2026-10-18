package com.garrietta.relay

import android.app.Application
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.notifications.MessageNotifications

/**
 * Manual dependency graph (no DI framework for v1 — the graph is tiny).
 * Transports are app-scoped so receivers can emit into [SmsTransport.incoming].
 */
class RelayApp : Application() {

    val smsTransport: SmsTransport by lazy { SmsTransport(this) }
    val rcsTransport: RcsTransport by lazy { RcsTransport(this) }

    val repository: UnifiedInboxRepository by lazy {
        UnifiedInboxRepository(this, smsTransport, rcsTransport)
    }

    override fun onCreate() {
        super.onCreate()
        MessageNotifications.ensureChannel(this)
    }
}

