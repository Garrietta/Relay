package com.garrietta.relay.rcs.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the FULL outgoing header set of the SignInGaia RPC (2026-10-18,
 * exact-header-replication round). The device 401 persisted after the
 * endpoint fix + SAPISIDHASH + UA alignment because the request was still
 * missing the browser-faithful headers the official web client sends
 * (sec-fetch-*, accept-language, accept: */*, referer) and carried a
 * sec-ch-ua claiming a different Chrome version than the User-Agent.
 *
 * Values of secret headers (cookie, authorization) are asserted by
 * presence/format only — never by value.
 */
class SignInGaiaHeadersTest {

    private val cookies = "SID=aaa; SAPISID=bbb; HSID=ccc; SSID=ddd"

    @Test
    fun signInGaiaHeaders_carriesFullBrowserFaithfulSet() {
        val h = RelayApi.signInGaiaHeaders(cookies, nowSec = 1700000000L)
        val names = h.names()
        val expected = setOf(
            "sec-ch-ua",
            "x-user-agent",
            "x-goog-api-key",
            "content-type",
            "sec-ch-ua-mobile",
            "user-agent",
            "sec-ch-ua-platform",
            "accept",
            "origin",
            "sec-fetch-site",
            "sec-fetch-mode",
            "sec-fetch-dest",
            "referer",
            "accept-language",
            "cookie",
            "authorization",
        )
        for (name in expected) {
            assertTrue("missing header: $name", name in names)
        }
    }

    @Test
    fun signInGaiaHeaders_browserHeadersMatchWebClient() {
        val h = RelayApi.signInGaiaHeaders(cookies, nowSec = 1700000000L)
        assertEquals("grpc-web-javascript/0.1", h["x-user-agent"])
        assertEquals(RelayApi.API_KEY, h["x-goog-api-key"])
        assertEquals(RelayApi.CT_PBLITE, h["content-type"])
        assertEquals("?1", h["sec-ch-ua-mobile"])
        assertEquals("\"Android\"", h["sec-ch-ua-platform"])
        assertEquals("*/*", h["accept"])
        assertEquals("https://messages.google.com", h["origin"])
        assertEquals("cross-site", h["sec-fetch-site"])
        assertEquals("cors", h["sec-fetch-mode"])
        assertEquals("empty", h["sec-fetch-dest"])
        assertEquals("https://messages.google.com/", h["referer"])
        assertEquals("en-US,en;q=0.9", h["accept-language"])
    }

    @Test
    fun signInGaiaHeaders_userAgentIsTheWebViewUa() {
        val h = RelayApi.signInGaiaHeaders(cookies, nowSec = 1700000000L)
        // The session was established by OUR WebView — its actual UA goes
        // out, never a hardcoded different one.
        assertEquals(RelayApi.WEBVIEW_UA, h["user-agent"])
    }

    @Test
    fun signInGaiaHeaders_secChUaVersionMatchesUserAgent() {
        val h = RelayApi.signInGaiaHeaders(cookies, nowSec = 1700000000L)
        val secChUa = h["sec-ch-ua"]!!
        // sec-ch-ua must claim the SAME Chrome major as the User-Agent —
        // a v=152 claim next to a Chrome/131 UA looks forged.
        assertTrue("sec-ch-ua=$secChUa", secChUa.contains("\"Google Chrome\";v=\"131\""))
        assertTrue("sec-ch-ua=$secChUa", secChUa.contains("\"Chromium\";v=\"131\""))
        assertTrue("sec-ch-ua must not claim a different version", !secChUa.contains("v=\"152\""))
        assertTrue("sec-ch-ua must not claim a different version", !secChUa.contains("v=\"120\""))
    }

    @Test
    fun chromeMajorVersion_parsesWebViewUa() {
        assertEquals(131, RelayApi.chromeMajorVersion(RelayApi.WEBVIEW_UA))
        assertEquals(152, RelayApi.chromeMajorVersion("Mozilla/5.0 Chrome/152.0.0.0 Safari/537.36"))
        assertNull(RelayApi.chromeMajorVersion("no-chrome-here"))
    }

    @Test
    fun signInGaiaHeaders_authorizationIsSapisidHash() {
        val h = RelayApi.signInGaiaHeaders(cookies, nowSec = 1700000000L)
        val auth = h["authorization"]
        assertNotNull(auth)
        // Presence + format only — the value derives from the secret SAPISID.
        assertTrue("auth=$auth", auth!!.startsWith("SAPISIDHASH 1700000000_"))
        assertTrue("auth=$auth", auth.matches(Regex("SAPISIDHASH \\d+_[0-9a-f]{40}")))
    }

    @Test
    fun signInGaiaHeaders_cookiePresentButValueNotAsserted() {
        val h = RelayApi.signInGaiaHeaders(cookies, nowSec = 1700000000L)
        assertNotNull(h["cookie"])
    }

    @Test
    fun signInGaiaHeaders_noSapisidMeansNoAuthorization() {
        val h = RelayApi.signInGaiaHeaders("SID=aaa; HSID=ccc", nowSec = 1700000000L)
        assertNull(h["authorization"])
        assertNotNull(h["cookie"])
    }
}
