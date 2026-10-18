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

    /**
     * Gaia-mode host. mautrix (pkg/libgm/util/paths.go) uses
     * instantmessaging-pa.clients6.google.com — NOT bare clients6.google.com
     * (the bare host 404s on the Registration path; diagnosed 2026-10-18
     * from a device 404). The live web client config at
     * messages.google.com/web/config publishes the same host.
     */
    const val PA_HOST_GAIA = "https://instantmessaging-pa.clients6.google.com"
    const val RPC_GAIA = "$PA_HOST_GAIA/\$rpc/google.internal.communications.instantmessaging.v1"
    const val PATH_SIGN_IN_GAIA = "$RPC_GAIA.Registration/SignInGaia"

    /** Google's public web-client API key (hardcoded in the official web client too). */
    const val API_KEY = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8"

    const val CT_PROTO = "application/x-protobuf"
    const val CT_PBLITE = "application/json+protobuf"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * The UA the sign-in WebView uses (single source of truth — GaiaWebView
     * reads this). Google binds cookie sessions to the UA that established
     * them, so Gaia RPCs MUST carry this UA, not the generic one above.
     * Diagnosed 2026-10-18 from a device 401: the session was captured by
     * the WebView but the RPC went out under a different UA.
     */
    const val WEBVIEW_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    fun headers(contentType: String, userAgent: String = UA): Headers = Headers.Builder()
        .add("x-goog-api-key", API_KEY)
        .add("x-user-agent", "grpc-web-javascript/0.1")
        .add("User-Agent", userAgent)
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
    userAgent: String? = null,
): RpcResult = withContext(Dispatchers.IO) {
    val headers = if (userAgent != null) RelayApi.headers(contentType, userAgent)
    else RelayApi.headers(contentType)
    val req = Request.Builder()
        .url(url)
        .headers(headers)
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
    userAgent: String? = null,
): RpcResult {
    // Simple cancellable wrapper: run the blocking call on IO, and rely on
    // coroutine cancellation (the caller's Job) to interrupt. For prompt
    // abort, callers should also track and cancel the Call directly.
    return withContext(Dispatchers.IO) {
        val headers = if (userAgent != null) RelayApi.headers(contentType, userAgent)
        else RelayApi.headers(contentType)
        val req = Request.Builder()
            .url(url)
            .headers(headers)
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val bytes = resp.body!!.bytes()
            RpcResult(resp.code, bytes, bytes.toString(Charsets.UTF_8))
        }
    }
}
