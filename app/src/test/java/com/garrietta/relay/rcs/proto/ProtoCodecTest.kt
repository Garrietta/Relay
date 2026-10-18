package com.garrietta.relay.rcs.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the binary protobuf codec. Expected byte strings are
 * hand-computed from protobuf wire-format rules (tag = field<<3 | wireType).
 */
class ProtoCodecTest {

    private fun hex(vararg b: Int): ByteArray = b.map { it.toByte() }.toByteArray()

    @Test fun `URLData-shaped message encodes to exact bytes`() {
        val bytes = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Bytes(byteArrayOf(1, 2)),
                2 to FieldVal.Bytes(byteArrayOf(3)),
                3 to FieldVal.Bytes(byteArrayOf(4, 5, 6)),
            ),
        )
        assertArrayEquals(
            hex(0x0A, 0x02, 0x01, 0x02, 0x12, 0x01, 0x03, 0x1A, 0x03, 0x04, 0x05, 0x06),
            bytes,
        )
    }

    @Test fun `varint encodes correctly`() {
        // field 1, varint 300 -> tag 0x08, 300 = 0xAC 0x02
        val bytes = ProtoCodec.encode(mapOf(1 to FieldVal.Varint(300)))
        assertArrayEquals(hex(0x08, 0xAC, 0x02), bytes)
    }

    @Test fun `string field encodes as length-delimited utf8`() {
        val bytes = ProtoCodec.encode(mapOf(3 to FieldVal.Str("Bugle")))
        // tag (3<<3|2)=0x1A, len 5, "Bugle"
        assertArrayEquals(hex(0x1A, 0x05, 0x42, 0x75, 0x67, 0x6C, 0x65), bytes)
    }

    @Test fun `nested message round-trips`() {
        val bytes = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Str("req-1"),
                7 to FieldVal.Msg(mapOf(3 to FieldVal.Varint(2026))),
            ),
        )
        val decoded = ProtoCodec.decode(bytes)
        assertEquals("req-1", decoded.firstStr(1))
        val inner = decoded.firstMsg(7)!!
        assertEquals(2026L, inner.firstVarint(3))
    }

    @Test fun `repeated fields decode as lists`() {
        val bytes = ProtoCodec.encode(
            mapOf(4 to FieldVal.Rep(listOf(FieldVal.Str("a"), FieldVal.Str("b")))),
        )
        val decoded = ProtoCodec.decode(bytes)
        val items = decoded[4]!!
        assertEquals(2, items.size)
        assertEquals("a", (items[0] as FieldVal.Bytes).v.toString(Charsets.UTF_8))
        assertEquals("b", (items[1] as FieldVal.Bytes).v.toString(Charsets.UTF_8))
    }

    @Test fun `unknown wire types are skipped`() {
        // field 2 fixed32 (wire type 5): tag 0x15, 4 bytes payload; then field 1 varint
        val bytes = hex(0x15, 0x01, 0x02, 0x03, 0x04, 0x08, 0x01)
        val decoded = ProtoCodec.decode(bytes)
        assertEquals(1L, decoded.firstVarint(1))
        assertTrue(decoded[2] == null)
    }

    @Test fun `empty message encodes to empty bytes`() {
        assertArrayEquals(byteArrayOf(), ProtoCodec.encode(emptyMap()))
        assertTrue(ProtoCodec.decode(byteArrayOf()).isEmpty())
    }
}
