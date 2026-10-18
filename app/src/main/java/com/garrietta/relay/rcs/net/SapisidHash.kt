package com.garrietta.relay.rcs.net

import java.security.MessageDigest

/**
 * SAPISIDHASH authorization for Google cookie-authenticated RPCs.
 *
 * Google's cookie-authenticated endpoints (including the Messages
 * Registration/SignInGaia RPC) reject requests that carry cookies but no
 * proof of origin — cookies alone get an HTTP 401. The proof is the
 * `Authorization: SAPISIDHASH <ts>_<hash>` header, derived from the SAPISID
 * cookie:
 *
 *     hash = SHA1("<unix_ts> <sapisid> <origin>")
 *     Authorization: SAPISIDHASH <unix_ts>_<hash hex>
 *
 * This is the same mechanism Chromium itself uses to authorize cookie
 * requests (and the one the reference bridge applies to every
 * cookie-authenticated request). The origin is the Messages web origin.
 *
 * Diagnosed 2026-10-18 from a device 401: the SignInGaia request carried
 * the Cookie header but no SAPISIDHASH, and went out under a different
 * User-Agent than the WebView that established the session.
 */
object SapisidHash {
    /** Origin the SAPISIDHASH is bound to — the Messages web origin. */
    const val ORIGIN = "https://messages.google.com"

    /**
     * Builds the `Authorization` header value for [sapisid].
     * `nowSec` is injectable so the format is unit-testable.
     */
    fun authorizationValue(
        sapisid: String,
        origin: String = ORIGIN,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): String {
        val input = "$nowSec $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return "SAPISIDHASH ${nowSec}_${digest.toHex()}"
    }

    /** Extracts the SAPISID value from a `Cookie` header string, or null. */
    fun extractSapisid(cookieHeader: String): String? =
        cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") }
            ?.substringAfter("=")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
