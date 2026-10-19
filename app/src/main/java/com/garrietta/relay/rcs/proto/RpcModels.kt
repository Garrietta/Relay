package com.garrietta.relay.rcs.proto

import com.garrietta.relay.rcs.pblite.PValue
import com.garrietta.relay.rcs.pblite.Pblite
import com.garrietta.relay.rcs.pblite.asList
import com.garrietta.relay.rcs.pblite.asMsg
import com.garrietta.relay.rcs.pblite.bool
import com.garrietta.relay.rcs.pblite.bytes
import com.garrietta.relay.rcs.pblite.msg
import com.garrietta.relay.rcs.pblite.num
import com.garrietta.relay.rcs.pblite.rep
import com.garrietta.relay.rcs.pblite.str

/**
 * RPC/messaging message models — clean-room Kotlin with hand-written pblite
 * mappers. Field numbers are protocol facts (rpc.proto, client.proto,
 * conversations.proto, events.proto in the reference implementation).
 */
object Rpc {
    // rpc.BugleRoute
    const val ROUTE_DATA_EVENT = 19
    const val ROUTE_PAIR_EVENT = 14
    const val ROUTE_GAIA_EVENT = 7

    // rpc.MessageType
    const val MSG_TYPE_BUGLE = 2
    const val MSG_TYPE_GAIA_2 = 20

    // rpc.ActionType (subset used by the MVP)
    const val ACTION_LIST_CONVERSATIONS = 1
    const val ACTION_LIST_MESSAGES = 2
    const val ACTION_SEND_MESSAGE = 3
    const val ACTION_GET_OR_CREATE_CONVERSATION = 9
    const val ACTION_MESSAGE_READ = 10
    const val ACTION_CREATE_GAIA_PAIRING_CLIENT_INIT = 44
    const val ACTION_CREATE_GAIA_PAIRING_CLIENT_FINISHED = 45

    // client.ListConversationsRequest.Folder
    const val FOLDER_INBOX = 1

    // conversations.ConversationType
    const val CONV_TYPE_RCS = 2

    /** Human-readable action name for diagnostics. */
    fun actionName(action: Int): String = when (action) {
        ACTION_LIST_CONVERSATIONS -> "LIST_CONVERSATIONS"
        ACTION_LIST_MESSAGES -> "LIST_MESSAGES"
        ACTION_SEND_MESSAGE -> "SEND_MESSAGE"
        ACTION_GET_OR_CREATE_CONVERSATION -> "GET_OR_CREATE_CONVERSATION"
        ACTION_MESSAGE_READ -> "MESSAGE_READ"
        ACTION_CREATE_GAIA_PAIRING_CLIENT_INIT -> "GAIA_CLIENT_INIT"
        ACTION_CREATE_GAIA_PAIRING_CLIENT_FINISHED -> "GAIA_CLIENT_FINISHED"
        else -> "ACTION_$action"
    }
}

/** Builds a positional pblite array node from field-number → value pairs. */
fun msgArr(vararg fields: Pair<Int, PValue>): PValue.Arr {
    val max = fields.maxOfOrNull { it.first } ?: 0
    val m = fields.toMap()
    return PValue.Arr((1..max).map { m[it] ?: PValue.PNull })
}

// ---------- app-layer models ----------

data class RcsConversation(
    val id: String,
    val name: String,
    val snippet: String,
    val timestampMs: Long,
    val unread: Boolean,
    val isGroup: Boolean,
    val type: Int,
    val pinned: Boolean,
    /** conversations.Conversation.participants (field 20). */
    val participants: List<RcsParticipant> = emptyList(),
    /**
     * conversations.Conversation.otherParticipants (field 21): participant
     * IDs excluding me — the server's own answer, no heuristics needed.
     */
    val otherParticipantIds: List<String> = emptyList(),
)

/**
 * conversations.Participant: ID=1 (SmallInfo{1:type, 2:number,
 * 3:participantID}), firstName=2, fullName=3, isMe=6.
 */
data class RcsParticipant(
    val participantId: String,
    val number: String,
    val firstName: String,
    val fullName: String,
    val isMe: Boolean,
)

fun decodeRcsParticipant(m: Map<Int, List<FieldVal>>): RcsParticipant {
    val id = m.firstMsg(1)
    return RcsParticipant(
        participantId = id?.firstStr(3).orEmpty(),
        number = id?.firstStr(2).orEmpty(),
        firstName = m.firstStr(2).orEmpty(),
        fullName = m.firstStr(3).orEmpty(),
        isMe = m.firstBool(6) ?: false,
    )
}

data class RcsMessage(
    val id: String,
    val conversationId: String,
    val body: String,
    val timestampMs: Long,
    val fromMe: Boolean,
    val status: Int,
    val tmpId: String,
    /** conversations.Message.participantID (field 9): the sender. */
    val participantId: String = "",
    /** conversations.Message.type (field 11): 1=sms, 2/3=mms, 4=rcs. */
    val msgTransportType: Int = 0,
)

// ---------- outgoing envelope ----------

/** Binary OutgoingRPCData{1:requestID, 2:action, 5:encryptedProtoData, 6:sessionID}. */
fun encodeOutgoingRpcData(
    requestId: String,
    action: Int,
    sealedPayload: ByteArray,
    sessionId: String,
): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Str(requestId),
        2 to FieldVal.Varint(action.toLong()),
        5 to FieldVal.Bytes(sealedPayload),
        6 to FieldVal.Str(sessionId),
    ),
)

/** Builds the full uplink HTTP body: pblite OutgoingRPCMessage.
 * Documented assumption: destRegistrationIDs is empty for the MVP. */
fun buildOutgoingRpcPblite(
    mobile: DeviceInfo,
    requestId: String,
    rpcDataBinary: ByteArray,
    tachyonToken: ByteArray,
    sessionId: String,
    ttl: Long = 0L,
): String {    val mobileArr = msgArr(
        1 to PValue.Num(mobile.userId),
        2 to PValue.Str(mobile.sourceId),
        3 to PValue.Str(mobile.network),
    )
    val cfg = ConfigVersion.CURRENT
    val cfgArr = msgArr(
        3 to PValue.Num(cfg.year.toLong()),
        4 to PValue.Num(cfg.month.toLong()),
        5 to PValue.Num(cfg.day.toLong()),
        7 to PValue.Num(cfg.v1.toLong()),
        9 to PValue.Num(cfg.v2.toLong()),
    )
    val dataArr = msgArr(
        1 to PValue.Str(requestId),
        2 to PValue.Num(Rpc.ROUTE_DATA_EVENT.toLong()),
        12 to PValue.Bytes(rpcDataBinary),
        23 to msgArr(
            1 to PValue.Arr(emptyList()),
            2 to PValue.Num(Rpc.MSG_TYPE_BUGLE.toLong()),
        ),
    )
    val authArr = msgArr(
        1 to PValue.Str(requestId),
        6 to PValue.Bytes(tachyonToken),
        7 to cfgArr,
    )
    // Top-level OutgoingRPCMessage{1:mobile, 2:data, 3:auth, 5:TTL, 9:destRegistrationIDs}
    return Pblite.encode(
        mapOf(
            1 to mobileArr,
            2 to dataArr,
            3 to authArr,
            5 to PValue.Num(ttl),
            // field 9 intentionally absent (empty repeated)
        ),
    )
}

/**
 * Builds the uplink HTTP body for a Gaia pairing message (pblite
 * OutgoingRPCMessage), mirroring the reference buildMessage with
 * DontEncrypt=true.
 *
 * Differences from [buildOutgoingRpcPblite]: the message type is caller-chosen
 * (GAIA_2=20 for CLIENT_INIT, BUGLE=2 for CLIENT_FINISHED), the RPC data goes
 * in unencryptedProtoData (field 3, not the sealed field 5), TTL is the
 * pairing TTL in microseconds, and destRegistrationIDs carries the chosen
 * phone's UUID (pblite_binary: each UUID string → base64 of its UTF-8 bytes,
 * per the reference pblite serializer).
 */
fun buildGaiaOutgoingPblite(
    mobile: DeviceInfo,
    requestId: String,
    rpcDataBinary: ByteArray,
    tachyonToken: ByteArray,
    sessionId: String,
    ttlMicros: Long,
    messageType: Int,
    destRegIds: List<String>,
): String {
    val mobileArr = msgArr(
        1 to PValue.Num(mobile.userId),
        2 to PValue.Str(mobile.sourceId),
        3 to PValue.Str(mobile.network),
    )
    val cfg = ConfigVersion.CURRENT
    val cfgArr = msgArr(
        3 to PValue.Num(cfg.year.toLong()),
        4 to PValue.Num(cfg.month.toLong()),
        5 to PValue.Num(cfg.day.toLong()),
        7 to PValue.Num(cfg.v1.toLong()),
        9 to PValue.Num(cfg.v2.toLong()),
    )
    val dataArr = msgArr(
        1 to PValue.Str(requestId),
        2 to PValue.Num(Rpc.ROUTE_DATA_EVENT.toLong()),
        12 to PValue.Bytes(rpcDataBinary),
        23 to msgArr(
            1 to PValue.Arr(emptyList()),
            2 to PValue.Num(messageType.toLong()),
        ),
    )
    val authArr = msgArr(
        1 to PValue.Str(requestId),
        6 to PValue.Bytes(tachyonToken),
        7 to cfgArr,
    )
    val destArr = PValue.Arr(
        destRegIds.map { id ->
            PValue.Str(java.util.Base64.getEncoder().encodeToString(id.toByteArray(Charsets.UTF_8)))
        },
    )
    return Pblite.encode(
        mapOf(
            1 to mobileArr,
            2 to dataArr,
            3 to authArr,
            5 to PValue.Num(ttlMicros),
            9 to destArr,
        ),
    )
}

/** Binary OutgoingRPCData{1:requestID, 2:action, 3:unencryptedProtoData, 6:sessionID}. */
fun encodeOutgoingRpcDataUnencrypted(
    requestId: String,
    action: Int,
    unencryptedPayload: ByteArray,
    sessionId: String,
): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Str(requestId),
        2 to FieldVal.Varint(action.toLong()),
        3 to FieldVal.Bytes(unencryptedPayload),
        6 to FieldVal.Str(sessionId),
    ),
)

// ---------- action payloads (binary protobuf request bodies, sealed before sending) ----------

/**
 * client.ListConversationsRequest{2:count, 4:folder} as BINARY protobuf.
 *
 * The reference proto.Marshals the request message before encrypting — the
 * sealed inner payload is binary protobuf, NOT pblite JSON. We shipped
 * pblite-JSON inners; the server couldn't parse them and never responded
 * (the 60s "Conversations sync failed" timeout, diagnosed 2026-10-18).
 */
fun encodeListConversationsRequest(count: Long, folder: Int = Rpc.FOLDER_INBOX): ByteArray =
    ProtoCodec.encode(
        mapOf(
            2 to FieldVal.Varint(count),
            4 to FieldVal.Varint(folder.toLong()),
        ),
    )

/** client.ListMessagesRequest{2:conversationID, 3:count} as binary protobuf. */
fun encodeListMessagesRequest(conversationId: String, count: Long): ByteArray =
    ProtoCodec.encode(
        mapOf(
            2 to FieldVal.Str(conversationId),
            3 to FieldVal.Varint(count),
        ),
    )

/**
 * client.SendMessageRequest{2:conversationID, 3:messagePayload, 5:tmpID, 6:forceRCS}
 * as binary protobuf.
 * messagePayload = MessagePayload{1:tmpID, 6:content{1:MessageContent{1:text}},
 * 7:conversationID, 10:[MessageInfo{2:MessageContent{1:text}}]}.
 */
fun encodeSendMessageRequest(
    conversationId: String,
    tmpId: String,
    text: String,
    forceRcs: Boolean = true,
): ByteArray {
    val content = mapOf(1 to FieldVal.Str(text))
    val payload = mapOf(
        1 to FieldVal.Str(tmpId),
        6 to FieldVal.Msg(mapOf(1 to FieldVal.Msg(content))),
        7 to FieldVal.Str(conversationId),
        10 to FieldVal.Rep(listOf(FieldVal.Msg(mapOf(2 to FieldVal.Msg(content))))),
    )
    return ProtoCodec.encode(
        mapOf(
            2 to FieldVal.Str(conversationId),
            3 to FieldVal.Msg(payload),
            5 to FieldVal.Str(tmpId),
            6 to FieldVal.Varint(if (forceRcs) 1 else 0),
        ),
    )
}

/** client.MessageReadRequest{2:conversationID, 3:messageID} as binary protobuf. */
fun encodeMessageReadRequest(conversationId: String, messageId: String): ByteArray =
    ProtoCodec.encode(
        mapOf(
            2 to FieldVal.Str(conversationId),
            3 to FieldVal.Str(messageId),
        ),
    )

/** client.ReceiveMessagesRequest{1:auth, 4:unknown} as pblite (long-poll). */
fun encodeReceiveMessagesRequest(requestId: String, tachyonToken: ByteArray): String {
    val cfg = ConfigVersion.CURRENT
    val auth = msgArr(
        1 to PValue.Str(requestId),
        // Field 3 (network): the reference sends "GDitto" for Google-account
        // sessions (AuthData.AuthNetwork()); omitting it is a deviation.
        3 to PValue.Str("GDitto"),
        6 to PValue.Bytes(tachyonToken),
        7 to msgArr(
            3 to PValue.Num(cfg.year.toLong()),
            4 to PValue.Num(cfg.month.toLong()),
            5 to PValue.Num(cfg.day.toLong()),
            7 to PValue.Num(cfg.v1.toLong()),
            9 to PValue.Num(cfg.v2.toLong()),
        ),
    )
    // unknown field 4: UnknownEmptyObject2{2: UnknownEmptyObject1{}} — sent as observed
    val unknown = msgArr(2 to PValue.Arr(emptyList()))
    return Pblite.encode(mapOf(1 to auth, 4 to unknown))
}

/** client.AckMessageRequest{1:authData, 2:emptyArr, 4:acks[]} as pblite. */
fun encodeAckMessagesRequest(
    requestId: String,
    tachyonToken: ByteArray,
    acks: List<Pair<String, DeviceInfo>>,
): String {
    val cfg = ConfigVersion.CURRENT
    val auth = msgArr(
        1 to PValue.Str(requestId),
        6 to PValue.Bytes(tachyonToken),
        7 to msgArr(
            3 to PValue.Num(cfg.year.toLong()),
            4 to PValue.Num(cfg.month.toLong()),
            5 to PValue.Num(cfg.day.toLong()),
            7 to PValue.Num(cfg.v1.toLong()),
            9 to PValue.Num(cfg.v2.toLong()),
        ),
    )
    val ackArrs = acks.map { (ackRequestId, device) ->
        msgArr(
            1 to PValue.Str(ackRequestId),
            2 to msgArr(
                1 to PValue.Num(device.userId),
                2 to PValue.Str(device.sourceId),
                3 to PValue.Str(device.network),
            ),
        )
    }
    return Pblite.encode(
        mapOf(
            1 to auth,
            2 to PValue.Arr(emptyList()),
            4 to PValue.Arr(ackArrs),
        ),
    )
}

// ---------- incoming (decode) ----------

sealed interface PollPayload {
    data class Data(val message: IncomingRpcMessage) : PollPayload
    data object Heartbeat : PollPayload
    data class Ack(val count: Int) : PollPayload
    data object StartRead : PollPayload
    data object Unknown : PollPayload
}

data class IncomingRpcMessage(
    val responseId: String,
    val bugleRoute: Int,
    val messageType: Int,
    val messageData: ByteArray, // binary-encoded inner payload (RPCMessageData / RPCPairData)
)

/** Decodes one LongPollingPayload{2:data, 3:heartbeat, 4:ack, 5:startRead}. */
fun decodePollPayload(m: Map<Int, PValue>): PollPayload {
    m.msg(2)?.let { d ->
        val route = d.num(2)?.toInt() ?: 0
        return PollPayload.Data(
            IncomingRpcMessage(
                responseId = d.str(1).orEmpty(),
                bugleRoute = route,
                messageType = d.num(5)?.toInt() ?: 0,
                messageData = d.bytes(12) ?: byteArrayOf(),
            ),
        )
    }
    if (m.msg(3) != null) return PollPayload.Heartbeat
    m.msg(4)?.let { return PollPayload.Ack(it.num(1)?.toInt() ?: 0) }
    if (m.msg(5) != null) return PollPayload.StartRead
    return PollPayload.Unknown
}

/** Decodes the long-poll HTTP body: [[payload], [payload], ...]. */
fun decodeLongPollBody(body: String): List<PollPayload> {
    val top = Pblite.decodeAsList(body)
    return top.map { el ->
        val m = (el as? PValue.Arr)?.asMsg() ?: emptyMap()
        decodePollPayload(m)
    }
}

/** rpc.RPCMessageData{1:sessionID, 3:timestamp, 4:action, 5:unencryptedData, 8:encryptedData}. */
data class RpcMessageData(
    val sessionId: String,
    val timestamp: Long,
    val action: Int,
    val unencryptedData: ByteArray?,
    val encryptedData: ByteArray?,
)

fun decodeRpcMessageData(bytes: ByteArray): RpcMessageData {
    val m = ProtoCodec.decode(bytes)
    return RpcMessageData(
        sessionId = m.firstStr(1).orEmpty(),
        timestamp = m.firstVarint(3) ?: 0L,
        action = m.firstVarint(4)?.toInt() ?: 0,
        unencryptedData = m.firstBytes(5),
        encryptedData = m.firstBytes(8),
    )
}

/**
 * Reference (session_handler.go receiveResponse): when the client is
 * cookie-authenticated (Gaia mode), the server emits unencrypted-only
 * messages BEFORE real action responses — "very hacky way to ignore weird
 * messages that come before real responses". These must never satisfy an
 * RPC waiter: their binary protobuf payload is not pblite JSON. The Gaia
 * pairing actions (44/45) are exempt — unencrypted by design.
 */
fun isWeirdPreResponse(rpcData: RpcMessageData, hasCookies: Boolean): Boolean =
    hasCookies &&
        rpcData.action != Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_INIT &&
        rpcData.action != Rpc.ACTION_CREATE_GAIA_PAIRING_CLIENT_FINISHED &&
        rpcData.encryptedData == null &&
        rpcData.unencryptedData != null

// ---------- conversations & messages (decode, binary protobuf) ----------

/** Repeated nested messages at field [n]. */
fun Map<Int, List<FieldVal>>.repMsgs(n: Int): List<Map<Int, List<FieldVal>>> =
    this[n]?.mapNotNull { (it as? FieldVal.Bytes)?.let { b -> ProtoCodec.decode(b.v) } }
        ?: emptyList()

/** Repeated strings at field [n]. */
fun Map<Int, List<FieldVal>>.repStrs(n: Int): List<String> =
    this[n]?.mapNotNull {
        when (it) {
            is FieldVal.Str -> it.v
            is FieldVal.Bytes -> it.v.toString(Charsets.UTF_8)
            else -> null
        }
    } ?: emptyList()

/** Bool (varint 0/1) at field [n]. */
fun Map<Int, List<FieldVal>>.firstBool(n: Int): Boolean? =
    (this[n]?.firstOrNull() as? FieldVal.Varint)?.v?.let { it != 0L }

private fun decodeMessageInfo(m: Map<Int, List<FieldVal>>): String? {
    // MessageInfo oneof{2:messageContent, 3:mediaContent, 5:locationContent}
    m.firstMsg(2)?.firstStr(1)?.let { return it }
    return null
}

fun decodeRcsMessage(
    m: Map<Int, List<FieldVal>>,
    participants: List<RcsParticipant> = emptyList(),
    fallbackFromMe: Boolean = false,
): RcsMessage {
    val infos = m.repMsgs(10).mapNotNull(::decodeMessageInfo)
    return RcsMessage(
        id = m.firstStr(1).orEmpty(),
        conversationId = m.firstStr(7).orEmpty(),
        body = infos.firstOrNull().orEmpty(),
        timestampMs = m.firstVarint(5) ?: 0L,
        fromMe = resolveFromMe(m, participants, fallbackFromMe),
        status = m.firstMsg(4)?.firstVarint(2)?.toInt() ?: 0,
        tmpId = m.firstStr(12).orEmpty(),
        participantId = m.firstStr(9).orEmpty(),
        msgTransportType = m.firstVarint(11)?.toInt() ?: 0,
    )
}

/**
 * Message direction. Primary: conversations.Message.participantID (field 9)
 * matched against the conversation's participants — Participant.isMe
 * (field 6) is the server's own answer. Fallback when participants are
 * unavailable (push events): the MessageStatus code — OUTGOING_* values are
 * <100 and nonzero (2=delivered, 11=displayed), INCOMING_* are >=100
 * (108=delivered, 109=displayed).
 */
fun resolveFromMe(
    m: Map<Int, List<FieldVal>>,
    participants: List<RcsParticipant>,
    fallback: Boolean,
): Boolean {
    val pid = m.firstStr(9)
    if (!pid.isNullOrEmpty()) {
        participants.firstOrNull { it.participantId == pid }?.let { return it.isMe }
    }
    val status = m.firstMsg(4)?.firstVarint(2)?.toInt() ?: 0
    if (status != 0) return status < 100
    return fallback
}

/** events.MessageEvent{2:[Message]} → messages. */
fun decodeMessageEvent(
    m: Map<Int, List<FieldVal>>,
    participants: List<RcsParticipant> = emptyList(),
): List<RcsMessage> =
    m.repMsgs(2).map { decodeRcsMessage(it, participants) }

/** events.UpdateEvents oneof{2:conversationEvent, 3:messageEvent, 4:typingEvent}. */
sealed interface UpdateEvent {
    data class Messages(val messages: List<RcsMessage>) : UpdateEvent
    data class Conversations(val conversations: List<RcsConversation>) : UpdateEvent
    data class Typing(val conversationId: String, val typing: Boolean) : UpdateEvent
    data object Other : UpdateEvent
}

fun decodeUpdateEvents(m: Map<Int, List<FieldVal>>): UpdateEvent {
    m.firstMsg(3)?.let { return UpdateEvent.Messages(decodeMessageEvent(it)) }
    m.firstMsg(2)?.let { ce ->
        return UpdateEvent.Conversations(ce.repMsgs(2).map(::decodeConversation))
    }
    m.firstMsg(4)?.let { te ->
        val d = te.firstMsg(2)
        return UpdateEvent.Typing(
            conversationId = d?.firstStr(1).orEmpty(),
            typing = d?.firstVarint(3)?.toInt() == 1,
        )
    }
    return UpdateEvent.Other
}

fun decodeConversation(m: Map<Int, List<FieldVal>>): RcsConversation {
    val latest = m.firstMsg(4)
    return RcsConversation(
        id = m.firstStr(1).orEmpty(),
        name = m.firstStr(2).orEmpty(),
        snippet = latest?.firstStr(1).orEmpty(),
        timestampMs = m.firstVarint(5) ?: 0L,
        unread = m.firstBool(6) ?: false,
        isGroup = m.firstBool(10) ?: false,
        type = m.firstVarint(22)?.toInt() ?: 0,
        pinned = m.firstBool(26) ?: false,
        participants = m.repMsgs(20).map(::decodeRcsParticipant),
        otherParticipantIds = m.repStrs(21),
    )
}

/** client.ListConversationsResponse{2:[Conversation], 5:cursor?} → conversations. */
fun decodeConversationsResponse(bytes: ByteArray): List<RcsConversation> {
    val m = ProtoCodec.decode(bytes)
    return m.repMsgs(2).map(::decodeConversation)
}

/** client.ListMessagesResponse{2:[Message]} → messages. */
fun decodeMessagesResponse(
    bytes: ByteArray,
    participants: List<RcsParticipant> = emptyList(),
): List<RcsMessage> {
    val m = ProtoCodec.decode(bytes)
    return m.repMsgs(2).map { decodeRcsMessage(it, participants) }
}

/** client.SendMessageResponse{3:status}. */
fun decodeSendMessageStatus(bytes: ByteArray): Int =
    ProtoCodec.decode(bytes).firstVarint(3)?.toInt() ?: 0
