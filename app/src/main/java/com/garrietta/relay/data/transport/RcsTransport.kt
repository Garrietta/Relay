package com.garrietta.relay.data.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * RCS transport — STUB.
 *
 * The plan (decided 2026-10-17): a direct in-app client of the Google Messages
 * web protocol (Kotlin port of mautrix/gmessages' libgm), no Matrix server.
 * Until the protocol study lands, this transport reports unavailable and every
 * send returns [SendResult.Unavailable]. The interface seam is stable, so the
 * UI and [com.garrietta.relay.data.repo.UnifiedInboxRepository] need no changes
 * when the real implementation arrives.
 */
class RcsTransport : Transport {

    override val id: String = "rcs"
    override val displayName: String = "RCS"

    private val _available = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _available

    /** Human-readable pairing state for the Settings screen. */
    val pairingState: Flow<RcsPairingState> = MutableStateFlow(RcsPairingState.NOT_PAIRED)

    override fun observeIncoming(): Flow<IncomingMessage> = emptyFlow()

    override suspend fun sendMessage(recipient: String, body: String): SendResult =
        SendResult.Unavailable("RCS is not paired yet — coming soon")
}

enum class RcsPairingState {
    NOT_PAIRED,
    PAIRING,
    PAIRED,
}
