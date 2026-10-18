package com.garrietta.relay.rcs.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the 2026-10-18 device 404: SignInGaia must target
 * instantmessaging-pa.clients6.google.com (per mautrix
 * pkg/libgm/util/paths.go and the live web client config at
 * messages.google.com/web/config) — NOT bare clients6.google.com, which
 * 404s on the Registration path.
 */
class RelayApiTest {

    @Test
    fun signInGaiaUrl_matchesMautrix() {
        assertEquals(
            "https://instantmessaging-pa.clients6.google.com/" +
                "\$rpc/google.internal.communications.instantmessaging.v1.Registration/SignInGaia",
            RelayApi.PATH_SIGN_IN_GAIA,
        )
    }

    @Test
    fun gaiaHost_keepsInstantmessagingPaSubdomain() {
        assertTrue(RelayApi.PA_HOST_GAIA.startsWith("https://instantmessaging-pa."))
    }
}
