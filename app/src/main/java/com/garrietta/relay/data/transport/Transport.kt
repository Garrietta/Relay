package com.garrietta.relay.data.transport

import kotlinx.coroutines.flow.Flow

/**
 * The seam every message transport implements. SMS/MMS is live today;
 * [RcsTransport] is a stub behind the same interface until the RCS protocol
 * study lands.
 */
interface Transport {
    /** Stable id: "sms", "rcs". */
    val id: String

    /** Human label for UI, e.g. "SMS", "RCS". */
    val displayName: String

    /** Whether this transport can currently send/receive. */
    val isAvailable: Flow<Boolean>

    /** Hot flow of messages arriving on this transport. */
    fun observeIncoming(): Flow<IncomingMessage>

    /** Sends a text message. Returns per-part status. */
    suspend fun sendMessage(recipient: String, body: String): SendResult
}

data class IncomingMessage(
    val transportId: String,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isMms: Boolean,
)

sealed interface SendResult {
    data object Sent : SendResult
    data class Failed(val reason: String) : SendResult
    /** Transport exists but cannot send right now (e.g. RCS not paired). */
    data class Unavailable(val reason: String) : SendResult
}
