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

    // rpc.ActionType (subset used by the MVP)
    const val ACTION_LIST_CONVERSATIONS = 1
    const val ACTION_LIST_MESSAGES = 2
    const val ACTION_SEND_MESSAGE = 3
    const val ACTION_GET_OR_CREATE_CONVERSATION = 9
    const val ACTION_MESSAGE_READ = 10

    // client.ListConversationsRequest.Folder
    const val FOLDER_INBOX = 1

    // conversations.ConversationType
    const val CONV_TYPE_RCS = 2
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
)

data class RcsMessage(
    val id: String,
    val conversationId: String,
    val body: String,
    val timestampMs: Long,
    val fromMe: Boolean,
    val status: Int,
    val tmpId: String,
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

// ---------- action payloads (pblite request bodies, sealed before sending) ----------

/** client.ListConversationsRequest{2:count, 4:folder} as pblite (pre-encryption). */
fun encodeListConversationsRequest(count: Long, folder: Int = Rpc.FOLDER_INBOX): String =
    Pblite.encode(mapOf(2 to PValue.Num(count), 4 to PValue.Num(folder.toLong())))

/** client.ListMessagesRequest{2:conversationID, 3:count} as pblite. */
fun encodeListMessagesRequest(conversationId: String, count: Long): String =
    Pblite.encode(mapOf(2 to PValue.Str(conversationId), 3 to PValue.Num(count)))

/**
 * client.SendMessageRequest{2:conversationID, 3:messagePayload, 5:tmpID, 6:forceRCS}.
 * messagePayload = MessagePayload{1:tmpID, 6:content{1:MessageContent{1:text}},
 * 7:conversationID, 10:[MessageInfo{2:MessageContent{1:text}}]}.
 */
fun encodeSendMessageRequest(
    conversationId: String,
    tmpId: String,
    text: String,
    forceRcs: Boolean = true,
): String {
    val content = msgArr(1 to PValue.Str(text))
    val messageInfo = PValue.Arr(listOf(msgArr(2 to content)))
    val payload = msgArr(
        1 to PValue.Str(tmpId),
        6 to msgArr(1 to content),
        7 to PValue.Str(conversationId),
        10 to messageInfo,
    )
    return Pblite.encode(
        mapOf(
            2 to PValue.Str(conversationId),
            3 to payload,
            5 to PValue.Str(tmpId),
            6 to PValue.Bool(forceRcs),
        ),
    )
}

/** client.MessageReadRequest{2:conversationID, 3:messageID} as pblite. */
fun encodeMessageReadRequest(conversationId: String, messageId: String): String =
    Pblite.encode(mapOf(2 to PValue.Str(conversationId), 3 to PValue.Str(messageId)))

/** client.ReceiveMessagesRequest{1:auth, 4:unknown} as pblite (long-poll). */
fun encodeReceiveMessagesRequest(requestId: String, tachyonToken: ByteArray): String {
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

// ---------- conversations & messages (decode) ----------

private fun decodeMessageInfo(m: Map<Int, PValue>): String? {
    // MessageInfo oneof{2:messageContent, 3:mediaContent, 5:locationContent}
    m.msg(2)?.str(1)?.let { return it }
    return null
}

fun decodeRcsMessage(m: Map<Int, PValue>, fallbackFromMe: Boolean = false): RcsMessage {
    val infos = m.rep(10).mapNotNull { (it as? PValue.Arr)?.asMsg()?.let(::decodeMessageInfo) }
    return RcsMessage(
        id = m.str(1).orEmpty(),
        conversationId = m.str(7).orEmpty(),
        body = infos.firstOrNull().orEmpty(),
        timestampMs = m.num(5) ?: 0L,
        fromMe = fallbackFromMe,
        status = m.msg(4)?.num(2)?.toInt() ?: 0,
        tmpId = m.str(12).orEmpty(),
    )
}

/** events.MessageEvent{2:[Message]} → messages. */
fun decodeMessageEvent(m: Map<Int, PValue>): List<RcsMessage> =
    m.rep(2).mapNotNull { (it as? PValue.Arr)?.asMsg() }
        .map { decodeRcsMessage(it) }

/** events.UpdateEvents oneof{2:conversationEvent, 3:messageEvent, 4:typingEvent}. */
sealed interface UpdateEvent {
    data class Messages(val messages: List<RcsMessage>) : UpdateEvent
    data class Conversations(val conversations: List<RcsConversation>) : UpdateEvent
    data class Typing(val conversationId: String, val typing: Boolean) : UpdateEvent
    data object Other : UpdateEvent
}

fun decodeUpdateEvents(m: Map<Int, PValue>): UpdateEvent {
    m.msg(3)?.let { return UpdateEvent.Messages(decodeMessageEvent(it)) }
    m.msg(2)?.let { ce ->
        return UpdateEvent.Conversations(
            ce.rep(2).mapNotNull { (it as? PValue.Arr)?.asMsg() }.map(::decodeConversation),
        )
    }
    m.msg(4)?.let { te ->
        val d = te.msg(2)
        return UpdateEvent.Typing(
            conversationId = d?.str(1).orEmpty(),
            typing = d?.num(3)?.toInt() == 1,
        )
    }
    return UpdateEvent.Other
}

fun decodeConversation(m: Map<Int, PValue>): RcsConversation {
    val latest = m.msg(4)
    return RcsConversation(
        id = m.str(1).orEmpty(),
        name = m.str(2).orEmpty(),
        snippet = latest?.str(1).orEmpty(),
        timestampMs = m.num(5) ?: 0L,
        unread = m.bool(6) ?: false,
        isGroup = m.bool(10) ?: false,
        type = m.num(22)?.toInt() ?: 0,
        pinned = m.bool(26) ?: false,
    )
}

/** client.ListConversationsResponse{2:[Conversation], 5:cursor?} → conversations. */
fun decodeConversationsResponse(pbliteBody: String): List<RcsConversation> {
    val m = Pblite.decode(pbliteBody)
    return m.rep(2).mapNotNull { (it as? PValue.Arr)?.asMsg() }.map(::decodeConversation)
}

/** client.ListMessagesResponse{2:[Message]} → messages. */
fun decodeMessagesResponse(pbliteBody: String): List<RcsMessage> {
    val m = Pblite.decode(pbliteBody)
    return m.rep(2).mapNotNull { (it as? PValue.Arr)?.asMsg() }.map { decodeRcsMessage(it) }
}

/** client.SendMessageResponse{3:status}. */
fun decodeSendMessageStatus(pbliteBody: String): Int =
    Pblite.decode(pbliteBody).num(3)?.toInt() ?: 0
