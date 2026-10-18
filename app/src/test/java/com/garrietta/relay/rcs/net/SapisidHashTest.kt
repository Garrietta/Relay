package com.garrietta.relay.rcs.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-10-18 device 401: SignInGaia carried the
 * Cookie header but no SAPISIDHASH Authorization header, and the RPC went
 * out under a different User-Agent than the WebView that established the
 * session. Google rejected the session.
 *
 * The SAPISIDHASH format mirrors the reference bridge (SHA1 of
 * "<unix_ts> <sapisid> <origin>", sent as
 * "Authorization: SAPISIDHASH <ts>_<hex>"); the known vector below pins it.
 */
class SapisidHashTest {

    @Test
    fun authorizationValue_matchesKnownVector() {
        val v = SapisidHash.authorizationValue(
            sapisid = "test_sapisid_value_123",
            origin = "https://messages.google.com",
            nowSec = 1700000000L,
        )
        assertEquals(
            "SAPISIDHASH 1700000000_90539fd19842255b443a323d61d4061d5dafafde",
            v,
        )
    }

    @Test
    fun authorizationValue_matchesIndependentSha1Vector() {
        // Expected value computed INDEPENDENTLY of this codebase:
        //   printf '%s' "1700000000 test_sapisid_value https://messages.google.com" | sha1sum
        // → 1eb72f0f142e5a3a28ee7b5538c879058c0029ee
        // This pins the byte-exact formula: Unix SECONDS (not millis),
        // single spaces, lowercase hex, origin exactly
        // https://messages.google.com. A wrong formula here = HTTP 401.
        val v = SapisidHash.authorizationValue(
            sapisid = "test_sapisid_value",
            origin = "https://messages.google.com",
            nowSec = 1700000000L,
        )
        assertEquals(
            "SAPISIDHASH 1700000000_1eb72f0f142e5a3a28ee7b5538c879058c0029ee",
            v,
        )
    }

    @Test
    fun authorizationValue_hasChromiumFormat() {
        val v = SapisidHash.authorizationValue("abc", nowSec = 1L)
        assertTrue(v.matches(Regex("SAPISIDHASH \\d+_[0-9a-f]{40}")))
    }

    @Test
    fun authorizationValue_changesWithTimestamp() {
        val a = SapisidHash.authorizationValue("abc", nowSec = 100L)
        val b = SapisidHash.authorizationValue("abc", nowSec = 101L)
        assertTrue(a != b)
    }

    @Test
    fun extractSapisid_findsCookie() {
        assertEquals(
            "xyz789",
            SapisidHash.extractSapisid("SID=a; SAPISID=xyz789; HSID=b"),
        )
    }

    @Test
    fun extractSapisid_returnsNullWhenAbsent() {
        assertNull(SapisidHash.extractSapisid("SID=a; HSID=b"))
    }

    @Test
    fun defaultOrigin_isMessagesWeb() {
        assertEquals("https://messages.google.com", SapisidHash.ORIGIN)
    }

    @Test
    fun webViewUa_isSingleSourced() {
        // The sign-in WebView and the Gaia RPC must share one UA string —
        // a drift here caused a session rejection once already.
        assertTrue(RelayApi.WEBVIEW_UA.contains("Chrome/"))
        assertTrue(!RelayApi.WEBVIEW_UA.contains("; wv"))
    }
}
