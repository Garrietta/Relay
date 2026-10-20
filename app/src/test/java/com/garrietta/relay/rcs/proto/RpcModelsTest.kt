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

    // ---------- single participant derivation (deriveRcsMembership) ----------

    private val me = RcsParticipant("p-me", "+15550001111", "Me", "Me", true)
    private val dad = RcsParticipant("p-dad", "+15550002222", "Dad", "Dad", false)
    private val grace = RcsParticipant("p-grace", "+15550003333", "Grace", "Grace Ambrose", false)
    private val selfNums = setOf("+15550001111")

    @Test fun `field 21 present drops a stale field-20 member it excludes`() {
        // Field 21 — the server's own membership answer — names only
        // Dad: the stale Grace entry drops, and membership (one other)
        // overrules the server isGroup flag in the same payload.
        val m = deriveRcsMembership(
            listOf(me, dad, grace), listOf("p-dad"), selfNums,
            serverIsGroup = true, conversationName = "Dad",
        )
        assertEquals(listOf("p-dad"), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
        assertEquals(listOf("p-dad"), m.claimedOthers.map { it.participantId })
    }

    @Test fun `field 21 present keeps every member it names`() {
        // The real Dad+Grace group: field 21 names both, both survive,
        // and the conversation classifies as a group.
        val m = deriveRcsMembership(
            listOf(me, dad, grace), listOf("p-dad", "p-grace"), selfNums,
            serverIsGroup = true, conversationName = "Dad, Grace Ambrose",
        )
        assertEquals(listOf("p-dad", "p-grace"), m.others.map { it.participantId })
        assertEquals(true, m.isGroup)
    }

    @Test fun `field 21 present never resurrects self`() {
        // Self is cleaned BEFORE the field-21 filter, so even a payload
        // that (wrongly) listed the self id in field 21 cannot put
        // Garrett in his own chat's others.
        val m = deriveRcsMembership(
            listOf(me, dad), listOf("p-me", "p-dad"), selfNums,
            serverIsGroup = true, conversationName = "Dad",
        )
        assertEquals(listOf("p-dad"), m.others.map { it.participantId })
    }

    @Test fun `participant decode reads the SmallInfo type`() {
        // conversations.Participant field 1 is SmallInfo{1:type,
        // 2:number, 3:participantID}: the type must survive decode —
        // the Diagnostics membership dump prints it to learn what
        // field 21 selects for (Garrett 2026-10-20, Game Night).
        val bytes = bmsg(
            1 to FieldVal.Bytes(
                bmsg(
                    1 to FieldVal.Varint(2),
                    2 to FieldVal.Str("+17653182838"),
                    3 to FieldVal.Str("25"),
                ),
            ),
            2 to FieldVal.Str("Dad"),
            3 to FieldVal.Str("Dad"),
            6 to FieldVal.Varint(0),
        )
        val p = decodeRcsParticipant(ProtoCodec.decode(bytes))
        assertEquals("25", p.participantId)
        assertEquals("+17653182838", p.number)
        assertEquals(2, p.infoType)
        assertEquals(false, p.isMe)
        // No SmallInfo at all: the type defaults to 0, never a crash.
        val bare = decodeRcsParticipant(ProtoCodec.decode(bmsg()))
        assertEquals(0, bare.infoType)
    }

    // Fixtures from Garrett's actual Diagnostics membership dump
    // (2026-10-20): participant ids and numbers as the server shipped
    // them. 824 = Garrett (isMe), 822 = his unflagged self duplicate.
    private val me824 = RcsParticipant("824", "+17653419588", "Garrett", "Garrett Marietta", true)
    private val dupe822 = RcsParticipant("822", "+17653419588", "Garrett", "Garrett Marietta", false)
    private val dad25 = RcsParticipant("25", "+17653182838", "Dad", "Dad", false)
    private val grace74 = RcsParticipant("74", "+18122017121", "Grace", "Grace Ambrose", false)
    private val haley67 = RcsParticipant("67", "+18125937679", "Haley", "Haley Marietta", false)
    private val realSelfNums = setOf("+17653419588")

    @Test fun `dad exact shape - no field 21, not a group, stale Grace collapses by name`() {
        // Conversation id=34 "Dad" [ANOMALY NO-F21]: field 21 ABSENT,
        // isGroup(10)=false, field 20 = [824 me, 25 Dad, 74 Grace
        // (stale), 822 me-dupe]. The server name "Dad" points at the
        // peer: others collapse to Dad alone, NOT a group — while the
        // claim (what the detector measures) stays [Dad, Grace].
        val m = deriveRcsMembership(
            listOf(me824, dad25, grace74, dupe822), emptyList(), realSelfNums,
            serverIsGroup = false, conversationName = "Dad",
        )
        assertEquals(listOf("25"), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
        assertEquals(listOf("25", "74"), m.claimedOthers.map { it.participantId })
    }

    @Test fun `group 46 shape - field 21 filters the stale Grace entry`() {
        // Conversation id=46 "Dad, Haley Marietta": field 20 includes
        // Grace (74) but field 21 = [25, 67] excludes her — regression
        // guard on the present-branch.
        val m = deriveRcsMembership(
            listOf(me824, dad25, haley67, grace74, dupe822),
            listOf("25", "67"), realSelfNums,
            serverIsGroup = true, conversationName = "Dad, Haley Marietta",
        )
        assertEquals(listOf("25", "67"), m.others.map { it.participantId })
        assertEquals(true, m.isGroup)
    }

    @Test fun `no field 21 with server group flag keeps the whole cleaned list`() {
        // Field 21 absent but field 10 says group: group-ness is the
        // flag alone, and the fallback list stands whole.
        val m = deriveRcsMembership(
            listOf(me, dad, grace), emptyList(), selfNums,
            serverIsGroup = true, conversationName = "Dad, Grace Ambrose",
        )
        assertEquals(listOf("p-dad", "p-grace"), m.others.map { it.participantId })
        assertEquals(true, m.isGroup)
    }

    @Test fun `no field 21 clean one-to-one stays a single peer`() {
        // The Mom shape from the dump: [824 me, 100 Mom, 822 dupe],
        // isGroup=false — one cleaned other, no collapse needed.
        val mom100 = RcsParticipant("100", "+17653182837", "Mom", "Mom", false)
        val m = deriveRcsMembership(
            listOf(me824, mom100, dupe822), emptyList(), realSelfNums,
            serverIsGroup = false, conversationName = "Mom",
        )
        assertEquals(listOf("100"), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
    }

    @Test fun `no field 21 self chat has no others and is not a group`() {
        // The self-chat shape from the dump (id=84): every field-20
        // entry is Garrett (one flagged, two unflagged duplicates).
        val self27 = RcsParticipant("27", "+17653419588", "Garrett", "Garrett Marietta", false)
        val m = deriveRcsMembership(
            listOf(me824, self27, dupe822), emptyList(), realSelfNums,
            serverIsGroup = false, conversationName = "Garrett Marietta",
        )
        assertEquals(emptyList<String>(), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
    }

    @Test fun `no field 21 not a group with unmatchable name keeps first other provisionally`() {
        // The conversation name matches nobody: the row must show
        // something — the first cleaned other — and it is STILL not
        // a group; the claim stays whole for the anomaly detector.
        val m = deriveRcsMembership(
            listOf(me, dad, grace), emptyList(), selfNums,
            serverIsGroup = false, conversationName = "House Stuff",
        )
        assertEquals(listOf("p-dad"), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
        assertEquals(listOf("p-dad", "p-grace"), m.claimedOthers.map { it.participantId })
    }

    @Test fun `no field 21 ambiguous name match resolves nothing`() {
        // Two entries carry the conversation's name: no single peer
        // can be named, so the provisional first cleaned other stands.
        val dad2 = RcsParticipant("p-dad-2", "+15550004444", "Dad", "Dad", false)
        val m = deriveRcsMembership(
            listOf(me, dad, dad2), emptyList(), selfNums,
            serverIsGroup = false, conversationName = "Dad",
        )
        assertEquals(listOf("p-dad"), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
    }

    @Test fun `no field 21 bare number conversation name matches by number`() {
        // A bare-number 1:1 whose stale entry sorts FIRST: the number
        // match, not list order, picks the peer.
        val m = deriveRcsMembership(
            listOf(me824, grace74, dad25), emptyList(), realSelfNums,
            serverIsGroup = false, conversationName = "+1 (765) 318-2838",
        )
        assertEquals(listOf("25"), m.others.map { it.participantId })
        assertEquals(false, m.isGroup)
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

    // ---------- mapping-time direction: ENTRY-LEVEL (Garrett 2026-10-20) ----------
    // Direction is decided by the sender ENTRY (the participant-table row
    // the message's participantID names), never by matching the PERSON
    // behind the entry. A self conversation holds two entries for the
    // same number — the isMe-flagged sender-side entry and the unflagged
    // recipient-side entry — and its messages are legitimately BOTH
    // directions. The retired number-match guard flattened them all to
    // outgoing; these fixtures pin the entry-level rule.

    /** The repository's mapping decision for one decoded wire message. */
    private fun decisionFor(
        wire: ByteArray,
        parts: List<RcsParticipant>,
    ): MessageDirection {
        val decoded = decodeRcsMessage(ProtoCodec.decode(wire), parts)
        val sender = parts.firstOrNull { it.participantId == decoded.participantId }
        return resolveMessageDirection(sender, decoded.fromMe, decoded.status)
    }

    @Test fun `self conversation directions follow the sender entry both ways`() {
        val myNumber = "+17655550100"
        val parts = listOf(
            RcsParticipant("p-me-sender", myNumber, "Garrett", "Garrett Marietta", true),
            RcsParticipant("p-me-recipient", myNumber, "Garrett", "Garrett Marietta", false),
        )
        // Alternating sender entries, same number on both rows: the
        // ENTRY decides, so the directions alternate too.
        val sent1 = bmsg(1 to FieldVal.Str("s1"), 9 to FieldVal.Str("p-me-sender"))
        val received1 = bmsg(1 to FieldVal.Str("r1"), 9 to FieldVal.Str("p-me-recipient"))
        val sent2 = bmsg(1 to FieldVal.Str("s2"), 9 to FieldVal.Str("p-me-sender"))
        val received2 = bmsg(1 to FieldVal.Str("r2"), 9 to FieldVal.Str("p-me-recipient"))
        assertEquals(true, decisionFor(sent1, parts).outgoing)
        assertEquals(false, decisionFor(received1, parts).outgoing)
        assertEquals(true, decisionFor(sent2, parts).outgoing)
        assertEquals(false, decisionFor(received2, parts).outgoing)
        // No metadata contradiction in a healthy self chat.
        assertEquals(false, decisionFor(sent1, parts).contradiction)
        assertEquals(false, decisionFor(received1, parts).contradiction)
    }

    @Test fun `one to one directions follow the sender entry`() {
        val parts = listOf(
            RcsParticipant("p-me", "+17655550100", "Garrett", "Garrett Marietta", true),
            RcsParticipant("p-dad", "+17655550200", "Dad", "Dad", false),
        )
        val mine = bmsg(
            1 to FieldVal.Str("m1"), 9 to FieldVal.Str("p-me"),
            4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(2))),
        )
        val dads = bmsg(
            1 to FieldVal.Str("m2"), 9 to FieldVal.Str("p-dad"),
            4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(108))),
        )
        assertEquals(true, decisionFor(mine, parts).outgoing)
        assertEquals(false, decisionFor(mine, parts).contradiction)
        assertEquals(false, decisionFor(dads, parts).outgoing)
        assertEquals(false, decisionFor(dads, parts).contradiction)
    }

    @Test fun `flagged entry with an incoming status marker is a counted contradiction`() {
        val parts = listOf(
            RcsParticipant("p-me", "+17655550100", "Garrett", "Garrett Marietta", true),
            RcsParticipant("p-dad", "+17655550200", "Dad", "Dad", false),
        )
        // Entry says self; status marker says INCOMING_* (108). The entry
        // determination wins (outgoing) and the clash is reported.
        val clash = bmsg(
            1 to FieldVal.Str("m1"), 9 to FieldVal.Str("p-me"),
            4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(108))),
        )
        val d = decisionFor(clash, parts)
        assertEquals(true, d.outgoing)
        assertTrue(d.contradiction)
    }

    @Test fun `unresolved sender entry keeps the wire direction`() {
        val parts = listOf(
            RcsParticipant("p-me", "+17655550100", "Garrett", "Garrett Marietta", true),
        )
        // participantID names no entry in this table: the wire direction
        // (MessageStatus fallback at decode) stands, both ways.
        val out = bmsg(
            1 to FieldVal.Str("m1"), 9 to FieldVal.Str("p-gone"),
            4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(2))),
        )
        val inc = bmsg(
            1 to FieldVal.Str("m2"), 9 to FieldVal.Str("p-gone"),
            4 to FieldVal.Bytes(bmsg(2 to FieldVal.Varint(108))),
        )
        assertEquals(true, decisionFor(out, parts).outgoing)
        assertEquals(false, decisionFor(inc, parts).outgoing)
        assertEquals(false, decisionFor(out, parts).contradiction)
        assertEquals(false, decisionFor(inc, parts).contradiction)
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

    // ---- Location sharing (Garrett 2026-10-20, feature queue 6/7) ----

    private val link = "https://maps.google.com/?q=40.7128,-74.006"

    @Test fun `send location request is native payload first with link companion`() {
        val req = ProtoCodec.decode(
            encodeSendLocationMessageRequest(
                "conv-1", "tmp-9", 40.7128, -74.006, "Central Park", link,
            ),
        )
        assertEquals("conv-1", req.firstStr(2))
        assertEquals("tmp-9", req.firstStr(5))
        assertEquals(1L, req.firstVarint(6))
        val payload = req.firstMsg(3)!!
        assertEquals("tmp-9", payload.firstStr(1))
        assertEquals("conv-1", payload.firstStr(7))
        // Payload field 6 mirrors the link text, like a media caption.
        assertEquals(link, payload.firstMsg(6)?.firstMsg(1)?.firstStr(1))
        val infos = payload.repMsgs(10)
        assertEquals(2, infos.size)
        // First info: the native location payload (field 5), exact coords.
        val loc = decodeLocationContent(infos[0].firstMsg(5)!!)!!
        assertEquals(40.7128, loc.latitude, 0.0)
        assertEquals(-74.006, loc.longitude, 0.0)
        assertEquals("Central Park", loc.name)
        // Second info: the human-fallback link as message text
        // (MessageContent field 1; decodeMessageInfo itself is private).
        assertEquals(link, infos[1].firstMsg(2)!!.firstStr(1))
    }

    @Test fun `send location request omits a blank name`() {
        val req = ProtoCodec.decode(
            encodeSendLocationMessageRequest("conv-1", "tmp-9", 40.7128, -74.006, "", link),
        )
        val infos = req.firstMsg(3)!!.repMsgs(10)
        val loc = decodeLocationContent(infos[0].firstMsg(5)!!)!!
        assertEquals("", loc.name)
    }

    @Test fun `incoming location message decodes its pin`() {
        val locationContent = mapOf(
            1 to FieldVal.Msg(
                mapOf(
                    1 to FieldVal.Fixed64(40.7128.toRawBits()),
                    2 to FieldVal.Fixed64((-74.006).toRawBits()),
                ),
            ),
            2 to FieldVal.Str("Central Park"),
        )
        val bytes = bmsg(
            1 to FieldVal.Str("srv-7"),
            10 to FieldVal.Rep(
                listOf(FieldVal.Msg(mapOf(5 to FieldVal.Msg(locationContent)))),
            ),
        )
        val decoded = decodeRcsMessage(ProtoCodec.decode(bytes))
        assertEquals(RcsLocation(40.7128, -74.006, "Central Park"), decoded.location)
        // A bare pin has no text part; the bubble renders from the pin.
        assertEquals("", decoded.body)
    }

    @Test fun `decodeLocationContent accepts a flat double pair`() {
        val loc = decodeLocationContent(
            mapOf(
                1 to listOf(FieldVal.Fixed64(40.7128.toRawBits())),
                2 to listOf(FieldVal.Fixed64((-74.006).toRawBits())),
            ),
        )!!
        assertEquals(40.7128, loc.latitude, 0.0)
        assertEquals(-74.006, loc.longitude, 0.0)
    }

    @Test fun `decodeLocationContent accepts an E7 varint pair`() {
        // Hand-built as a DECODED map: nested messages arrive as Bytes.
        val latLngBytes = ProtoCodec.encode(
            mapOf(
                1 to FieldVal.Varint(407_128_000),
                2 to FieldVal.Varint(-740_060_000),
            ),
        )
        val loc = decodeLocationContent(
            mapOf(1 to listOf(FieldVal.Bytes(latLngBytes))),
        )!!
        assertEquals(40.7128, loc.latitude, 0.0)
        assertEquals(-74.006, loc.longitude, 0.0)
    }

    @Test fun `decodeLocationContent rejects out-of-range coordinates`() {
        assertEquals(
            null,
            decodeLocationContent(
                mapOf(
                    1 to listOf(FieldVal.Fixed64(123.0.toRawBits())),
                    2 to listOf(FieldVal.Fixed64(0.0.toRawBits())),
                ),
            ),
        )
    }
}
