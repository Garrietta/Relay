package com.garrietta.relay.rcs.proto

import com.garrietta.relay.rcs.pblite.PValue
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.asList
import com.garrietta.relay.rcs.pblite.asMsg
import com.garrietta.relay.rcs.pblite.msg
import com.garrietta.relay.rcs.pblite.num
import com.garrietta.relay.rcs.pblite.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcModelsTest {

    /** Builds a binary-protobuf message node from field-number → value pairs. */
    private fun bmsg(vararg fields: Pair<Int, FieldVal>): ByteArray =
        ProtoCodec.encode(mapOf(*fields))

    @Test fun `list conversations request encodes as binary protobuf`() {
        val bytes = encodeListConversationsRequest(20)
        val m = ProtoCodec.decode(bytes)
        assertEquals(20L, m.firstVarint(2))
        assertEquals(Rpc.FOLDER_INBOX.toLong(), m.firstVarint(4))
    }

    @Test fun `list conversations request round-trips through decode`() {
        // The sealed inner must be binary protobuf, not pblite JSON: the
        // reference proto.Marshals the request, and pblite-JSON inners got
        // no server response (60s sync timeout, diagnosed 2026-10-18).
        val bytes = encodeListConversationsRequest(20)
        assertTrue(!String(bytes, Charsets.UTF_8).startsWith("["))
    }

    @Test fun `send message request shape`() {
        val body = encodeSendMessageRequest("conv1", "tmp-1", "hello", true)
        val m = ProtoCodec.decode(body)
        assertEquals("conv1", m.firstStr(2))
        assertEquals("tmp-1", m.firstStr(5))
        val payload = m.firstMsg(3)!!
        assertEquals("tmp-1", payload.firstStr(1))
        assertEquals("conv1", payload.firstStr(7))
        assertEquals(1L, m.firstVarint(6))
    }

    @Test fun `send message request messageInfo decodes to text`() {
        val body = encodeSendMessageRequest("conv1", "tmp-1", "hello", true)
        val payload = ProtoCodec.decode(body).firstMsg(3)!!
        val infos = payload.repMsgs(10)
        assertEquals(1, infos.size)
        assertEquals("hello", infos[0].firstMsg(2)!!.firstStr(1))
    }

    @Test fun `conversations response decodes from binary`() {
        val conv = bmsg(
            1 to FieldVal.Str("c1"),
            2 to FieldVal.Str("Alice"),
            5 to FieldVal.Varint(1700000000000L),
            6 to FieldVal.Varint(1),
        )
        val body = ProtoCodec.encode(mapOf(2 to FieldVal.Rep(listOf(FieldVal.Bytes(conv)))))
        val list = decodeConversationsResponse(body)
        assertEquals(1, list.size)
        assertEquals("c1", list[0].id)
        assertEquals("Alice", list[0].name)
        assertEquals(true, list[0].unread)
    }

    @Test fun `outgoing envelope has expected top-level fields`() {
        val rpcData = encodeOutgoingRpcData("req-1", Rpc.ACTION_SEND_MESSAGE, byteArrayOf(1, 2, 3), "sess")
        val body = buildOutgoingRpcPblite(
            mobile = DeviceInfo(123, "src", "Bugle"),
            requestId = "req-1",
            rpcDataBinary = rpcData,
            tachyonToken = byteArrayOf(9, 9),
            sessionId = "sess",
        )
        val m = Pblite.decode(body)
        val data = m.msg(2)!!
        assertEquals("req-1", data.str(1))
        assertEquals(19L, data.num(2))
        val auth = m.msg(3)!!
        assertEquals("req-1", auth.str(1))
        val mobile = m.msg(1)!!
        assertEquals(123L, mobile.num(1))
        assertEquals("Bugle", mobile.str(3))
    }

    @Test fun `long-poll body decodes heartbeat and data`() {
        val body = "[[null,null,[]],[null,[\"r1\",19,null,null,null,2]]]"
        val payloads = decodeLongPollBody(body)
        assertEquals(2, payloads.size)
        assertTrue(payloads[0] is PollPayload.Heartbeat)
        val d = payloads[1] as PollPayload.Data
        assertEquals("r1", d.message.responseId)
        assertEquals(19, d.message.bugleRoute)
    }

    @Test fun `message event decodes messages`() {
        // UpdateEvents{3: MessageEvent{2: [Message{1:id, 5:ts, 7:conv, 10:[MessageInfo{2:Content{1:text}}], 12:tmp}]}}
        val content = bmsg(1 to FieldVal.Str("hi there"))
        val msg = bmsg(
            1 to FieldVal.Str("m1"),
            5 to FieldVal.Varint(1700000000000L),
            7 to FieldVal.Str("c1"),
            10 to FieldVal.Rep(listOf(FieldVal.Bytes(bmsg(2 to FieldVal.Bytes(content))))),
            12 to FieldVal.Str("tmp-9"),
        )
        val body = bmsg(3 to FieldVal.Bytes(bmsg(2 to FieldVal.Rep(listOf(FieldVal.Bytes(msg))))))
        val ev = decodeUpdateEvents(ProtoCodec.decode(body))
        val msgs = (ev as UpdateEvent.Messages).messages
        assertEquals(1, msgs.size)
        assertEquals("hi there", msgs[0].body)
        assertEquals("m1", msgs[0].id)
        assertEquals("tmp-9", msgs[0].tmpId)
    }

    @Test fun `conversation decodes`() {
        val conv = bmsg(
            1 to FieldVal.Str("c1"),
            2 to FieldVal.Str("Alice"),
            4 to FieldVal.Bytes(bmsg(1 to FieldVal.Str("see you"), 2 to FieldVal.Varint(1))),
            5 to FieldVal.Varint(1700000000000L),
            6 to FieldVal.Varint(1),
            22 to FieldVal.Varint(2),
        )
        val c = decodeConversation(ProtoCodec.decode(conv))
        assertEquals("Alice", c.name)
        assertEquals("see you", c.snippet)
        assertEquals(true, c.unread)
        assertEquals(2, c.type)
    }

    @Test fun `ack request encodes acks`() {
        val body = encodeAckMessagesRequest("req-1", byteArrayOf(1), listOf("ack-1" to DeviceInfo(5, "s", "Bugle")))
        val m = Pblite.decode(body)
        val acks = (m.entries.first { it.key == 4 }.value as PValue.Arr).asList()
        assertEquals(1, acks.size)
        val ack = (acks[0] as PValue.Arr).asMsg()
        assertEquals("ack-1", ack.str(1))
        assertEquals(5L, ack.msg(2)!!.num(1))
    }

    // ---------- participant parsing (field 20/21) ----------

    private fun participantBytes(pid: String, number: String, name: String, isMe: Boolean) =
        bmsg(
            1 to FieldVal.Bytes(bmsg(2 to FieldVal.Str(number), 3 to FieldVal.Str(pid))),
            2 to FieldVal.Str(name),
            3 to FieldVal.Str(name),
            6 to FieldVal.Varint(if (isMe) 1 else 0),
        )

    @Test fun `conversation decodes participants and otherParticipantIds`() {
        val conv = bmsg(
            1 to FieldVal.Str("c1"),
            2 to FieldVal.Str("Alice, Bob"),
            10 to FieldVal.Varint(1),
            20 to FieldVal.Rep(
                listOf(
                    FieldVal.Bytes(participantBytes("p-me", "+15550001111", "Me", true)),
                    FieldVal.Bytes(participantBytes("p-alice", "+15550002222", "Alice", false)),
                )
            ),
            21 to FieldVal.Rep(listOf(FieldVal.Str("p-alice"))),
        )
        val c = decodeConversation(ProtoCodec.decode(conv))
        assertEquals(2, c.participants.size)
        val alice = c.participants.first { it.participantId == "p-alice" }
        assertEquals("+15550002222", alice.number)
        assertEquals("Alice", alice.fullName)
        assertEquals(false, alice.isMe)
        assertEquals(true, c.participants.first { it.isMe }.participantId == "p-me")
        assertEquals(listOf("p-alice"), c.otherParticipantIds)
    }

    @Test fun `message direction resolves from participant isMe`() {
        val parts = listOf(
            RcsParticipant("p-me", "+15550001111", "Me", "Me", true),
            RcsParticipant("p-alice", "+15550002222", "Alice", "Alice", false),
        )
        val mine = bmsg(1 to FieldVal.Str("m1"), 9 to FieldVal.Str("p-me"))
        val theirs = bmsg(1 to FieldVal.Str("m2"), 9 to FieldVal.Str("p-alice"))
        assertEquals(true, decodeRcsMessage(ProtoCodec.decode(mine), parts).fromMe)
        assertEquals(false, decodeRcsMessage(ProtoCodec.decode(theirs), parts).fromMe)
    }

    @Test fun `message direction falls back to status code`() {
        // No participants (push path): OUTGOING_* (<100) = mine, INCOMING_* (>=100) = theirs.
        val out = bmsg(1 to FieldVal.Str("m1"), 4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(2))))
        val inc = bmsg(1 to FieldVal.Str("m2"), 4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(108))))
        val unk = bmsg(1 to FieldVal.Str("m3"))
        assertEquals(true, decodeRcsMessage(ProtoCodec.decode(out)).fromMe)
        assertEquals(false, decodeRcsMessage(ProtoCodec.decode(inc)).fromMe)
        assertEquals(false, decodeRcsMessage(ProtoCodec.decode(unk)).fromMe)
    }

    @Test fun `message decodes participantId and transport type`() {
        val m = bmsg(
            1 to FieldVal.Str("m1"),
            9 to FieldVal.Str("p-alice"),
            11 to FieldVal.Varint(4),
        )
        val decoded = decodeRcsMessage(ProtoCodec.decode(m))
        assertEquals("p-alice", decoded.participantId)
        assertEquals(4, decoded.msgTransportType)
    }

    // Regression: 2026-10-18 "Couldn't open chat" — in Gaia/cookie mode the
    // server emits unencrypted-only binary messages BEFORE real action
    // responses. They must never satisfy an RPC waiter (their bytes are not
    // pblite JSON). Mirrors the reference's receiveResponse guard.
    @Test fun `weird pre-response suppressed in Gaia mode`() {
        val weird = RpcMessageData(
            sessionId = "req-123",
            timestamp = 0L,
            action = Rpc.ACTION_GET_OR_CREATE_CONVERSATION,
            unencryptedData = byteArrayOf(0x0A, 0x02, 0x08, 0x01),
            encryptedData = null,
        )
        assertTrue(isWeirdPreResponse(weird, hasCookies = true))
    }

    @Test fun `weird pre-response not suppressed without cookies`() {
        val weird = RpcMessageData(
            sessionId = "req-123",
            timestamp = 0L,
            action = Rpc.ACTION_GET_OR_CREATE_CONVERSATION,
            unencryptedData = byteArrayOf(0x0A),
            encryptedData = null,
        )
        assertTrue(!isWeirdPreResponse(weird, hasCookies = false))
    }

    @Test fun `pairing actions exempt from weird-message suppression`() {
        for (action in listOf(
            Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_INIT,
            Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_FINISHED,
        )) {
            val pairing = RpcMessageData(
                sessionId = "req-123",
                timestamp = 0L,
                action = action,
                unencryptedData = byteArrayOf(0x0A),
                encryptedData = null,
            )
            assertTrue(!isWeirdPreResponse(pairing, hasCookies = true))
        }
    }

    @Test fun `server microsecond timestamps normalize to milliseconds`() {
        // µs → ms; ms passes through; zero stays zero.
        assertEquals(1760000000000L, normalizeServerTimestamp(1760000000000000L))
        assertEquals(1760000000000L, normalizeServerTimestamp(1760000000000L))
        assertEquals(0L, normalizeServerTimestamp(0L))
    }

    @Test fun `decoded message timestamp is milliseconds so optimistic sends sort newest`() {
        // Device bug (Build #112): the server sends µs; optimistic bubbles
        // use System.currentTimeMillis(). Without normalization every
        // server message sorts "newer" and just-sent messages land at the
        // TOP of the reverseLayout thread.
        val msg = bmsg(
            1 to FieldVal.Str("m1"),
            5 to FieldVal.Varint(1760000000000000L),
        )
        val decoded = decodeRcsMessage(ProtoCodec.decode(msg))
        assertEquals(1760000000000L, decoded.timestampMs)
        val optimisticMs = 1760000000001L
        assertTrue(optimisticMs > decoded.timestampMs)
        val sorted = listOf(decoded.timestampMs, optimisticMs).sortedDescending()
        assertEquals(optimisticMs, sorted.first())
    }

    @Test fun `decoded conversation timestamp normalizes microseconds`() {
        val conv = bmsg(
            1 to FieldVal.Str("c1"),
            5 to FieldVal.Varint(1760000000000000L),
        )
        val c = decodeConversation(ProtoCodec.decode(conv))
        assertEquals(1760000000000L, c.timestampMs)
    }

    @Test fun `encrypted responses never suppressed`() {
        val real = RpcMessageData(
            sessionId = "req-123",
            timestamp = 0L,
            action = Rpc.ACTION_GET_OR_CREATE_CONVERSATION,
            unencryptedData = null,
            encryptedData = byteArrayOf(0x01, 0x02, 0x03),
        )
        assertTrue(!isWeirdPreResponse(real, hasCookies = true))
    }

    // ---------- participant cleaning (GIRTH ghost) ----------

    private fun part(
        number: String,
        fullName: String = "",
        firstName: String = "",
        isMe: Boolean = false,
    ) = RcsParticipant(
        participantId = "p/$number",
        number = number,
        firstName = firstName,
        fullName = fullName,
        isMe = isMe,
    )

    @Test fun `cleanRcsParticipantList drops the GIRTH ghost`() {
        // GIRTH as the server sent it: Alec, Trenton, plus an unflagged self
        // entry carrying his real number with an "Unknown" name (the ghost
        // that survived the #114 blank-number-only filter).
        val selfNumber = "+17653419588"
        val parts = listOf(
            part("+13175550101", fullName = "Alec"),
            part("+13175550102", fullName = "Trenton"),
            part(selfNumber, fullName = "Unknown", isMe = false),
        )
        val cleaned = cleanRcsParticipantList(parts, setOf(selfNumber))
        assertEquals(
            listOf("+13175550101", "+13175550102"),
            cleaned.map { it.number },
        )
    }

    @Test fun `cleanRcsParticipantList catches self in different formats`() {
        // Server lists him as 7653419588 (no country code), ownAddress()
        // resolved +17653419588 — last-10 match must still drop him.
        val parts = listOf(
            part("7653419588", fullName = "", isMe = false),
            part("+13175550101", fullName = "Alec"),
        )
        val cleaned = cleanRcsParticipantList(parts, setOf("+17653419588"))
        assertEquals(listOf("+13175550101"), cleaned.map { it.number })
    }

    @Test fun `cleanRcsParticipantList drops isMe and blank ghosts and dedupes`() {
        val parts = listOf(
            part("+13175550101", fullName = "Alec"),
            part("+13175550101", fullName = "Alec"), // duplicate
            part("+19998887777", fullName = "Me", isMe = true),
            part("", fullName = ""), // blank ghost
            part("", fullName = "Unknown"), // named ghost, no number
        )
        val cleaned = cleanRcsParticipantList(parts, emptySet())
        assertEquals(listOf("+13175550101"), cleaned.map { it.number })
    }

    @Test fun `displayNameOrNumber never renders Unknown`() {
        assertEquals("15551234567", part("15551234567", fullName = "Unknown").displayNameOrNumber())
        assertEquals("15551234567", part("15551234567", fullName = "unknown").displayNameOrNumber())
        assertEquals("15551234567", part("15551234567").displayNameOrNumber())
        assertEquals("Alec", part("15551234567", fullName = "Alec").displayNameOrNumber())
    }
}
