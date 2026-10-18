package com.garrietta.relay.rcs.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * microG pairing chain — off-device testable parts.
 *
 * Covers: multilogin JSON parsing (XSSI prefix, OK/INVALID_TOKENS/other),
 * userinfo sub extraction, Cookie-header formatting, account-picker logic.
 * The live links (AccountManager token mint, real multilogin POST) can only
 * be proven on Garrett's phone — the device test IS the experiment.
 */
class MicrogPairingTest {

    private val okBody = ")]}',\n" +
        "{\"status\":\"OK\",\"cookies\":[" +
        "{\"name\":\"SID\",\"value\":\"sid-val\",\"domain\":\".google.com\"}," +
        "{\"name\":\"HSID\",\"value\":\"hsid-val\",\"domain\":\".google.com\"}," +
        "{\"name\":\"SAPISID\",\"value\":\"sapisid-val\",\"domain\":\".google.com\"}" +
        "]}"

    @Test fun `multilogin OK with XSSI prefix parses cookies`() {
        val cookies = MicrogPairing.parseMultiloginCookies(okBody)
        assertEquals(3, cookies.size)
        assertEquals("sid-val", cookies["SID"])
        assertEquals("hsid-val", cookies["HSID"])
        assertEquals("sapisid-val", cookies["SAPISID"])
    }

    @Test fun `multilogin OK without XSSI prefix parses cookies`() {
        val body = "{\"status\":\"OK\",\"cookies\":[{\"name\":\"SID\",\"value\":\"x\"}]}"
        assertEquals(mapOf("SID" to "x"), MicrogPairing.parseMultiloginCookies(body))
    }

    @Test fun `multilogin INVALID_TOKENS throws with verbatim signal`() {
        val body = ")]}',\n{\"status\":\"INVALID_TOKENS\",\"cookies\":[]}"
        try {
            MicrogPairing.parseMultiloginCookies(body)
            fail("should have thrown")
        } catch (e: MicrogPairingException) {
            assertTrue(e.message!!.contains("INVALID_TOKENS"))
            assertTrue(e.message!!.contains("NOT viable"))
        }
    }

    @Test fun `multilogin other status throws`() {
        val body = "{\"status\":\"RETRY\"}"
        try {
            MicrogPairing.parseMultiloginCookies(body)
            fail("should have thrown")
        } catch (e: MicrogPairingException) {
            assertTrue(e.message!!.contains("RETRY"))
        }
    }

    @Test fun `userinfo sub extracted`() {
        val body = "{\"sub\":\"1234567890\",\"name\":\"Garrett\",\"email\":\"g@example.com\"}"
        assertEquals("1234567890", MicrogPairing.parseUserinfoSub(body))
    }

    @Test fun `userinfo blank sub throws`() {
        try {
            MicrogPairing.parseUserinfoSub("{\"sub\":\"\"}")
            fail("should have thrown")
        } catch (e: MicrogPairingException) {
            // expected
        }
    }

    @Test fun `cookie header formatted like CookieManager output`() {
        val header = MicrogPairing.formatCookieHeader(
            mapOf("SID" to "a", "HSID" to "b", "SAPISID" to "c")
        )
        assertEquals("SID=a; HSID=b; SAPISID=c", header)
    }

    @Test fun `single account is chosen automatically`() {
        val acc = MicrogPairing.MicrogAccount("g@example.com")
        assertEquals(acc, MicrogPairing.chooseSingleAccount(listOf(acc)))
    }

    @Test fun `zero or many accounts need UI`() {
        assertNull(MicrogPairing.chooseSingleAccount(emptyList()))
        assertNull(
            MicrogPairing.chooseSingleAccount(
                listOf(
                    MicrogPairing.MicrogAccount("a@example.com"),
                    MicrogPairing.MicrogAccount("b@example.com"),
                )
            )
        )
    }

    @Test fun `stripXssi handles all shapes`() {
        assertEquals("{}", MicrogPairing.stripXssi(")]}',\n{}"))
        assertEquals("{}", MicrogPairing.stripXssi("{}"))
        assertEquals("{\"a\":1}", MicrogPairing.stripXssi("  )]}',\n{\"a\":1}"))
    }
}
