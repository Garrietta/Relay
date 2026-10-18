package com.garrietta.relay.rcs.pblite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the pblite codec. Expected strings are hand-derived from the
 * pblite format rules (field numbers → 0-indexed JSON array positions, absent
 * fields → null, trailing nulls trimmed, bytes → base64). These run on CI via
 * testDebugUnitTest.
 */
class PbliteTest {

    @Test fun `empty message encodes to empty array`() {
        assertEquals("[]", Pblite.encode(emptyMap()))
    }

    @Test fun `single string field`() {
        assertEquals("[\"hello\"]", Pblite.encode(mapOf(1 to PValue.Str("hello"))))
    }

    @Test fun `gaps become nulls`() {
        assertEquals(
            "[\"a\",null,42]",
            Pblite.encode(mapOf(1 to PValue.Str("a"), 3 to PValue.Num(42))),
        )
    }

    @Test fun `trailing nulls are trimmed`() {
        // Field 5 present would force size 5; absent fields 2-4 are nulls, no trailing null.
        assertEquals(
            "[\"a\",null,null,null,\"z\"]",
            Pblite.encode(mapOf(1 to PValue.Str("a"), 5 to PValue.Str("z"))),
        )
    }

    @Test fun `bytes are base64`() {
        assertEquals(
            "[null,\"AQID\"]",
            Pblite.encode(mapOf(2 to PValue.Bytes(byteArrayOf(1, 2, 3)))),
        )
    }

    @Test fun `booleans round-trip`() {
        assertEquals("[true]", Pblite.encode(mapOf(1 to PValue.Bool(true))))
    }

    @Test fun `nested message is nested array`() {
        val inner = PValue.Arr(listOf(PValue.Str("x"), PValue.Num(7)))
        assertEquals("[[\"x\",7]]", Pblite.encode(mapOf(1 to inner)))
    }

    @Test fun `repeated field is array of values`() {
        val rep = PValue.Arr(listOf(PValue.Str("a"), PValue.Str("b")))
        assertEquals("[null,[\"a\",\"b\"]]", Pblite.encode(mapOf(2 to rep)))
    }

    @Test fun `decode drops nulls and indexes from one`() {
        val m = Pblite.decode("[\"a\",null,42]")
        assertEquals(PValue.Str("a"), m[1])
        assertEquals(PValue.Num(42), m[3])
        assertEquals(2, m.size)
    }

    @Test fun `decode nested arrays as neutral nodes`() {
        val m = Pblite.decode("[[\"x\",7]]")
        val inner = (m[1] as PValue.Arr).asMsg()
        assertEquals(PValue.Str("x"), inner[1])
        assertEquals(PValue.Num(7), inner[2])
    }

    @Test fun `round-trip complex message`() {
        val fields = mapOf(
            1 to PValue.Str("req-123"),
            3 to PValue.Str("Bugle"),
            6 to PValue.Bytes(byteArrayOf(9, 8, 7, 6)),
            7 to PValue.Arr(
                listOf(
                    PValue.PNull, PValue.PNull,
                    PValue.Num(2026), PValue.Num(9), PValue.Num(2),
                    PValue.PNull, PValue.Num(4), PValue.PNull, PValue.Num(6),
                ),
            ),
        )
        val encoded = Pblite.encode(fields)
        assertEquals(
            "[\"req-123\",null,\"Bugle\",null,null,\"CQgHBg==\",[null,null,2026,9,2,null,4,null,6]]",
            encoded,
        )
        val decoded = Pblite.decode(encoded)
        assertEquals(PValue.Str("req-123"), decoded[1])
        assertEquals(PValue.Str("Bugle"), decoded[3])
        // schema-less decode returns base64 payloads as Str; typed reader decodes them
        assertTrue(decoded.bytes(6)!!.contentEquals(byteArrayOf(9, 8, 7, 6)))
        val cfg = (decoded[7] as PValue.Arr).asMsg()
        assertEquals(PValue.Num(2026L), cfg[3])
        assertEquals(PValue.Num(9L), cfg[4])
        assertEquals(PValue.Num(2L), cfg[5])
        assertEquals(PValue.Num(4L), cfg[7])
        assertEquals(PValue.Num(6L), cfg[9])
    }

    @Test fun `typed readers`() {
        val m = Pblite.decode("[\"req\",null,\"AQID\"]")
        assertEquals("req", m.str(1))
        assertEquals(null, m.str(2))
        assertTrue(m.bytes(3)!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun `malformed input throws`() {
        try {
            Pblite.decode("{\"a\":1}")
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
