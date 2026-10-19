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

    @Test fun `list conversations request encodes`() {
        assertEquals("[null,20,null,1]", encodeListConversationsRequest(20))
    }

    @Test fun `send message request shape`() {
        val body = encodeSendMessageRequest("conv1", "tmp-1", "hello", true)
        val m = Pblite.decode(body)
        assertEquals("conv1", m.str(2))
        assertEquals("tmp-1", m.str(5))
        val payload = m.msg(3)!!
        assertEquals("tmp-1", payload.str(1))
        assertEquals("conv1", payload.str(7))
    }

    @Test fun `send message request messageInfo decodes to text`() {
        val body = encodeSendMessageRequest("conv1", "tmp-1", "hello", true)
        val payload = Pblite.decode(body).msg(3)!!
        val infos = ((payload.entries.first { it.key == 10 }.value) as PValue.Arr).asList()
        assertEquals(1, infos.size)
        val info = (infos[0] as PValue.Arr).asMsg()
        assertEquals("hello", info.msg(2)!!.str(1))
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
        val msg = msgArr(
            1 to PValue.Str("m1"),
            5 to PValue.Num(1700000000000L),
            7 to PValue.Str("c1"),
            10 to PValue.Arr(listOf(msgArr(2 to msgArr(1 to PValue.Str("hi there"))))),
            12 to PValue.Str("tmp-9"),
        )
        val body = Pblite.encode(mapOf(3 to msgArr(2 to PValue.Arr(listOf(msg)))))
        val ev = decodeUpdateEvents(Pblite.decode(body))
        val msgs = (ev as UpdateEvent.Messages).messages
        assertEquals(1, msgs.size)
        assertEquals("hi there", msgs[0].body)
        assertEquals("m1", msgs[0].id)
        assertEquals("tmp-9", msgs[0].tmpId)
    }

    @Test fun `conversation decodes`() {
        val conv = msgArr(
            1 to PValue.Str("c1"),
            2 to PValue.Str("Alice"),
            4 to msgArr(1 to PValue.Str("see you"), 2 to PValue.Num(1)),
            5 to PValue.Num(1700000000000L),
            6 to PValue.Bool(true),
            22 to PValue.Num(2),
        )
        val c = decodeConversation(conv.asMsg())
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

    private fun participantArr(pid: String, number: String, name: String, isMe: Boolean) =
        msgArr(
            1 to msgArr(2 to PValue.Str(number), 3 to PValue.Str(pid)),
            2 to PValue.Str(name),
            3 to PValue.Str(name),
            6 to PValue.Bool(isMe),
        )

    @Test fun `conversation decodes participants and otherParticipantIds`() {
        val conv = msgArr(
            1 to PValue.Str("c1"),
            2 to PValue.Str("Alice, Bob"),
            10 to PValue.Bool(true),
            20 to PValue.Arr(
                listOf(
                    participantArr("p-me", "+15550001111", "Me", true),
                    participantArr("p-alice", "+15550002222", "Alice", false),
                )
            ),
            21 to PValue.Arr(listOf(PValue.Str("p-alice"))),
        )
        val c = decodeConversation(conv.asMsg())
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
        val mine = msgArr(1 to PValue.Str("m1"), 9 to PValue.Str("p-me"))
        val theirs = msgArr(1 to PValue.Str("m2"), 9 to PValue.Str("p-alice"))
        assertEquals(true, decodeRcsMessage(mine.asMsg(), parts).fromMe)
        assertEquals(false, decodeRcsMessage(theirs.asMsg(), parts).fromMe)
    }

    @Test fun `message direction falls back to status code`() {
        // No participants (push path): OUTGOING_* (<100) = mine, INCOMING_* (>=100) = theirs.
        val out = msgArr(1 to PValue.Str("m1"), 4 to msgArr(2 to PValue.Num(2)))
        val inc = msgArr(1 to PValue.Str("m2"), 4 to msgArr(2 to PValue.Num(108)))
        val unk = msgArr(1 to PValue.Str("m3"))
        assertEquals(true, decodeRcsMessage(out.asMsg()).fromMe)
        assertEquals(false, decodeRcsMessage(inc.asMsg()).fromMe)
        assertEquals(false, decodeRcsMessage(unk.asMsg()).fromMe)
    }

    @Test fun `message decodes participantId and transport type`() {
        val m = msgArr(
            1 to PValue.Str("m1"),
            9 to PValue.Str("p-alice"),
            11 to PValue.Num(4),
        )
        val decoded = decodeRcsMessage(m.asMsg())
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
}
