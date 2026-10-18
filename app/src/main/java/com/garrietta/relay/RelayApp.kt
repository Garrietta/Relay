package com.garrietta.relay

import android.app.Application
import com.garrietta.relay.data.repo.AppSeedStore
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.notifications.MessageNotifications
import com.garrietta.relay.ui.theme.AvatarShapeAssigner

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

    /** App-wide persistent contact color seed (see AppSeedStore). */
    val appSeedStore: AppSeedStore by lazy { AppSeedStore(this) }

    override fun onCreate() {
        super.onCreate()
        // Crash capture FIRST — before anything else can throw — so a crash
        // anywhere in startup still lands in the report Garrett can see in
        // Settings (no logcat access from his device).
        CrashReporter.install(this)
        MessageNotifications.ensureChannel(this)
        // Fresh avatar-shape variety on every cold start (Garrett: shapes
        // "maybe even changing shape on each startup").
        AvatarShapeAssigner.reshuffle()
        // RCS: when paired, make sure the session is up on cold start.
        // RcsTransport is lazy — touching it runs init, which starts the
        // session when a pairing exists — and the foreground service keeps
        // the long-poll alive under Doze.
        if (rcsTransport.isPairedNow) {
            com.garrietta.relay.rcs.service.RcsService.start(this)
        }
    }
}

