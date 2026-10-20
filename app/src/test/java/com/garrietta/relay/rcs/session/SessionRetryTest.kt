package com.garrietta.relay.rcs.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The transient-retry classifier behind the 2026-10-20 sync resilience
 * fix: a momentary DNS failure for the Tachyon host surfaced as a hard
 * "Conversations sync failed". Network-class failures must be retried;
 * auth/protocol/decode failures and our own response timeout must not
 * be reclassified as network errors.
 */
class SessionRetryTest {

    @Test
    fun dnsFailure_isNetworkClass() {
        assertTrue(isNetworkClassFailure(UnknownHostException("instantmessaging-pa.clients6.google.com")))
    }

    @Test
    fun wrappedDnsFailure_isNetworkClass() {
        val wrapped = IOException("request failed", UnknownHostException("no address"))
        assertTrue(isNetworkClassFailure(wrapped))
    }

    @Test
    fun connectAndTimeout_areNetworkClass() {
        assertTrue(isNetworkClassFailure(ConnectException("connection refused")))
        assertTrue(isNetworkClassFailure(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun http5xxAnd429_areNetworkClass() {
        assertTrue(isNetworkClassFailure(RcsHttpException(500, "uplink HTTP 500")))
        assertTrue(isNetworkClassFailure(RcsHttpException(503, "uplink HTTP 503")))
        assertTrue(isNetworkClassFailure(RcsHttpException(429, "uplink HTTP 429")))
    }

    @Test
    fun http4xx_isNotNetworkClass() {
        assertFalse(isNetworkClassFailure(RcsHttpException(400, "uplink HTTP 400")))
        assertFalse(isNetworkClassFailure(RcsHttpException(401, "uplink HTTP 401")))
        assertFalse(isNetworkClassFailure(RcsHttpException(404, "uplink HTTP 404")))
    }

    @Test
    fun plainRcsAndRuntimeErrors_areNotNetworkClass() {
        assertFalse(isNetworkClassFailure(RcsException("not paired")))
        assertFalse(isNetworkClassFailure(RcsException("SendMessage status=3")))
        assertFalse(isNetworkClassFailure(IllegalStateException("decode failed")))
    }
}
