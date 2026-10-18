package com.garrietta.relay.rcs.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP layer for Relay's RCS transport — clean-room implementation.
 *
 * Talks to instantmessaging-pa.googleapis.com exactly like Messages for Web:
 * pairing endpoints take binary protobuf, steady-state endpoints take pblite
 * (application/json+protobuf). Long-polling for ReceiveMessages.
 */
object RelayApi {
    const val PA_HOST = "https://instantmessaging-pa.googleapis.com"
    const val RPC = "$PA_HOST/\$rpc/google.internal.communications.instantmessaging.v1"

    const val PATH_REGISTER_PHONE_RELAY = "$RPC.Pairing/RegisterPhoneRelay"
    const val PATH_REFRESH_PHONE_RELAY = "$RPC.Pairing/RefreshPhoneRelay"
    const val PATH_REVOKE_RELAY_PAIRING = "$RPC.Pairing/RevokeRelayPairing"
    const val PATH_RECEIVE_MESSAGES = "$RPC.Messaging/ReceiveMessages"
    const val PATH_SEND_MESSAGE = "$RPC.Messaging/SendMessage"
    const val PATH_ACK_MESSAGES = "$RPC.Messaging/AckMessages"

    /** Google's public web-client API key (hardcoded in the official web client too). */
    const val API_KEY = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8"

    const val CT_PROTO = "application/x-protobuf"
    const val CT_PBLITE = "application/json+protobuf"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun headers(contentType: String): Headers = Headers.Builder()
        .add("x-goog-api-key", API_KEY)
        .add("x-user-agent", "grpc-web-javascript/0.1")
        .add("User-Agent", UA)
        .add("sec-ch-ua", "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not-A.Brand\";v=\"99\"")
        .add("sec-ch-ua-mobile", "?1")
        .add("sec-ch-ua-platform", "\"Android\"")
        .add("Origin", "https://messages.google.com")
        .add("Referer", "https://messages.google.com/web/welcome")
        .add("Content-Type", contentType)
        .add("Accept", contentType)
        .build()

    fun client(longPoll: Boolean = false): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(if (longPoll) 90 else 30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

data class RpcResult(val code: Int, val body: ByteArray, val text: String) {
    fun isOk(): Boolean = code in 200..299
}

/** One-shot POST; returns raw bytes + text. */
suspend fun postRpc(
    client: OkHttpClient,
    url: String,
    contentType: String,
    body: ByteArray,
): RpcResult = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url(url)
        .headers(RelayApi.headers(contentType))
        .post(body.toRequestBody(contentType.toMediaType()))
        .build()
    client.newCall(req).execute().use { resp ->
        val bytes = resp.body!!.bytes()
        RpcResult(resp.code, bytes, bytes.toString(Charsets.UTF_8))
    }
}

/**
 * Cancellable POST: coroutine cancellation aborts the in-flight HTTP call
 * immediately (instead of waiting out the socket timeout). The pairing flow
 * uses this so backing out mid-pairing is prompt.
 */
suspend fun postRpcCancellable(
    client: OkHttpClient,
    url: String,
    contentType: String,
    body: ByteArray,
    onCall: (okhttp3.Call) -> Unit = {},
): RpcResult = withContext(Dispatchers.IO) {
    kotlinx.coroutines.suspendCancellableCoroutine<RpcResult> { cont ->
        val req = Request.Builder()
            .url(url)
            .headers(RelayApi.headers(contentType))
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        val call = client.newCall(req)
        onCall(call)
        cont.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { resp ->
                val bytes = resp.body!!.bytes()
                cont.resume(RpcResult(resp.code, bytes, bytes.toString(Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }
}
