package com.garrietta.relay.rcs.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-decode tests for the two payloads behind Garrett's 2026-10-20
 * inbox parity asks: TypingData's User payload (the typist's number —
 * the inbox typing label names the typist, groups included) and
 * LatestMessage's fromMe/displayName (the "You: " / "{FirstName}: "
 * snippet prefixes). Round-trips through the real ProtoCodec.
 */
class TypingLatestDecodeTest {

    private fun typingEventsBytes(typingData: Map<Int, FieldVal>): ByteArray =
        ProtoCodec.encode(
            mapOf(4 to FieldVal.Msg(mapOf(2 to FieldVal.Msg(typingData))))
        )

    @Test
    fun typingEventDecodesTypistNumber() {
        val bytes = typingEventsBytes(
            mapOf(
                1 to FieldVal.Str("conv-1"),
                2 to FieldVal.Msg(
                    mapOf(
                        1 to FieldVal.Varint(987654321L),
                        2 to FieldVal.Str("+17655550123"),
                    )
                ),
                3 to FieldVal.Varint(1),
            )
        )
        val typing = decodeUpdateEvents(ProtoCodec.decode(bytes)) as UpdateEvent.Typing
        assertEquals("conv-1", typing.conversationId)
        assertTrue(typing.typing)
        assertEquals("+17655550123", typing.typistNumber)
    }

    @Test
    fun typingEventStoppedWithoutUserHasNoNumber() {
        val bytes = typingEventsBytes(
            mapOf(
                1 to FieldVal.Str("conv-2"),
                3 to FieldVal.Varint(0),
            )
        )
        val typing = decodeUpdateEvents(ProtoCodec.decode(bytes)) as UpdateEvent.Typing
        assertEquals("conv-2", typing.conversationId)
        assertFalse(typing.typing)
        assertNull(typing.typistNumber)
    }

    @Test
    fun conversationDecodesLatestMessageSender() {
        val bytes = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Str("conv-3"),
                2 to FieldVal.Str("GIRTH"),
                4 to FieldVal.Msg(
                    mapOf(
                        1 to FieldVal.Str("Good boy"),
                        2 to FieldVal.Varint(0),
                        4 to FieldVal.Str("Trenton Meier"),
                    )
                ),
            )
        )
        val conv = decodeConversation(ProtoCodec.decode(bytes))
        assertEquals("Good boy", conv.snippet)
        assertFalse(conv.latestFromMe)
        assertEquals("Trenton Meier", conv.latestSenderName)
    }

    @Test
    fun conversationDecodesLatestFromMe() {
        val bytes = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Str("conv-4"),
                4 to FieldVal.Msg(
                    mapOf(
                        1 to FieldVal.Str("I'll be there"),
                        2 to FieldVal.Varint(1),
                    )
                ),
            )
        )
        val conv = decodeConversation(ProtoCodec.decode(bytes))
        assertEquals("I'll be there", conv.snippet)
        assertTrue(conv.latestFromMe)
        assertEquals("", conv.latestSenderName)
    }
}
