package com.garrietta.relay.rcs.proto

import com.garrietta.relay.data.model.isUnknownSenderGhostName

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
    // rpc.ActionType.SEND_REACTION (Garrett 2026-10-19: REAL reactions —
    // the wire format is client.SendReactionRequest{messageID, reactionData,
    // action}, NOT a text message. Reference facts: rpc.proto ActionType
    // enum, client.proto SendReactionRequest, conversations.proto
    // ReactionData/ReactionEntry).
    const val ACTION_SEND_REACTION = 38

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
        ACTION_SEND_REACTION -> "SEND_REACTION"
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
) {
    /**
     * The REAL per-conversation transport from conversations.Conversation
     * field 22 (type: SMS=1 / RCS=2). Never hardcoded: a conversation the
     * server marks SMS (e.g. the (463) 225-0067 campaign thread) must read
     * "SMS" in the header, chips and composer routing. Unknown (0) defaults
     * to RCS in paired mode — the common case.
     */
    fun transportLabel(): String = when (type) {
        1 -> "SMS"
        else -> "RCS"
    }

    /** True when the server marks this conversation RCS-capable. */
    fun isRcsType(): Boolean = type != 1
}

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

/** Digits-only form of a participant number for comparison. */
private fun RcsParticipant.normNumber(): String = number.filter(Char::isDigit)

/** True when this participant is one of [selfNumbers] (last-10-digit match). */
private fun RcsParticipant.isSelfNumber(selfNumbers: Set<String>): Boolean {
    val norm = normNumber()
    if (norm.isEmpty() || selfNumbers.isEmpty()) return false
    return selfNumbers.any { s ->
        val d = s.filter(Char::isDigit)
        d.isNotEmpty() && norm.takeLast(10) == d.takeLast(10)
    }
}

/**
 * Display name for a participant that can NEVER render "Unknown": a blank
 * or placeholder ("Unknown", any case) name falls back to the number, so a
 * ghost entry renders as digits, never the word "Unknown".
 */
/**
 * RCS-level ghost predicate (Garrett 2026-10-19): the UNKNOWN_SENDER!
 * remnant — checked against the RAW server name, before any display
 * resolution.
 */
fun RcsParticipant.isUnknownSenderGhost(): Boolean =
    fullName.isUnknownSenderGhostName() || firstName.isUnknownSenderGhostName()

/**
 * GHOST CHOKE POINT (Garrett 2026-10-19): the remnant name can NEVER
 * resolve to itself — every caller gets the number instead, so no display
 * path can render the literal ghost string even if it forgets to filter.
 * Display-layer only; the underlying data is untouched.
 */
fun RcsParticipant.displayNameOrNumber(): String {
    val n = fullName.ifBlank { firstName }.trim()
    if (n.isUnknownSenderGhostName()) return number
    return if (n.isBlank() || n.equals("Unknown", ignoreCase = true)) number else n
}

/**
 * Drops self and ghost participants from a server participant list.
 * Pure function — unit-tested with the GIRTH shape.
 *
 * - isMe-flagged entries go.
 * - Entries whose number matches any of [selfNumbers] go (the server doesn't
 *   always flag every self entry — the GIRTH ghost was an unflagged self
 *   entry with a real number and an "Unknown"/blank name; callers pass
 *   ownAddress() plus every isMe number seen across conversations).
 * - "Ghosts" (blank number AND blank/"Unknown" name) go.
 * - Duplicates collapse by normalized number.
 */
fun cleanRcsParticipantList(
    parts: List<RcsParticipant>,
    selfNumbers: Set<String>,
): List<RcsParticipant> {
    val seen = mutableSetOf<String>()
    return parts.filter { p ->
        if (p.isMe) return@filter false
        if (p.isSelfNumber(selfNumbers)) return@filter false
        val norm = p.normNumber()
        if (norm.isEmpty()) {
            val name = p.fullName.ifBlank { p.firstName }.trim()
            if (name.isBlank() || name.equals("Unknown", ignoreCase = true)) return@filter false
            return@filter true
        }
        seen.add(norm)
    }
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
    /** Media attachment when the message carries one (MessageInfo field 3). */
    val media: RcsMediaAttachment? = null,
    /** Shared location when the message carries one (MessageInfo field 5). */
    val location: RcsLocation? = null,
    /**
     * Wire reactions attached to THIS message (conversations.Message field
     * 19, repeated ReactionEntry). The real reaction events from the
     * protocol — distinct from text-pattern reactions.
     */
    val wireReactions: List<RcsWireReaction> = emptyList(),
)

/**
 * One conversations.ReactionEntry from the wire: one emoji + the
 * participant ids who applied it. [emoji] is the unicode string from
 * ReactionData; [emojiType] is the conversations.EmojiType value
 * (fallback glyph source when unicode is empty).
 */
data class RcsWireReaction(
    val emoji: String,
    val emojiType: Int = 0,
    val participantIds: List<String> = emptyList(),
)

/**
 * conversations.MediaContent (MessageInfo field 3): format=1, mediaID=2,
 * mediaName=4, size=5, dimensions=6{1:width, 2:height}, mediaData=7,
 * thumbnailMediaID=9, decryptionKey=11, thumbnailDecryptionKey=12,
 * mimeType=14.
 */
data class RcsMediaAttachment(
    val mediaId: String,
    val mimeType: String,
    val decryptionKey: ByteArray,
    val width: Long = 0,
    val height: Long = 0,
    val size: Long = 0,
    /**
     * Small media can arrive inline in the message itself; when present no
     * download is needed.
     */
    val inlineData: ByteArray? = null,
    /**
     * Server-hosted thumbnail ID (MediaContent field 9) + its key (field
     * 12). Some messages carry no full-size mediaID (field 2) but DO carry
     * a thumbnailMediaID — the old code ignored it and rendered the tiny
     * inline field-7 bytes. A thumbnail-ID download goes through the same
     * validated choke point as a full download.
     */
    val thumbnailMediaId: String = "",
    val thumbnailKey: ByteArray = byteArrayOf(),
    /**
     * Media name (MediaContent field 4) — the file name the sender's
     * client attached. Relay's sticker sends name their PNG
     * "relay-sticker-<id>.png" (see ui.chat.Stickers), which is how a
     * receiving Relay recognizes its own stickers exactly instead of
     * guessing from pixels.
     */
    val name: String = "",
) {
    /** True for image content types (renderable in a bubble). */
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * A shared location pin (conversations.LocationContent, MessageInfo
 * field 5). [name] is the place/address label the sender's client
 * attached, when it sent one.
 */
data class RcsLocation(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
)

/**
 * Decodes conversations.LocationContent from a MessageInfo field-5 map.
 *
 * Canonical layout (Google proto convention): field 1 = a LatLng
 * submessage {1: double latitude, 2: double longitude}, field 2 = the
 * optional place name. Google has shipped more than one client against
 * this oneof, so decode is deliberately tolerant: flat double/float
 * coordinate pairs and varint E7 pairs (degrees × 10^7, the other common
 * Google encoding) are accepted too. Anything whose numbers don't land
 * in valid lat/lng ranges is rejected — coordinates are never guessed.
 */
fun decodeLocationContent(m: Map<Int, List<FieldVal>>): RcsLocation? {
    fun plausible(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0
    // Canonical: nested LatLng at field 1, name at field 2 (or 3).
    m.firstMsg(1)?.let { ll ->
        val lat = ll.firstDouble(1)
        val lng = ll.firstDouble(2)
        if (lat != null && lng != null && plausible(lat, lng)) {
            return RcsLocation(
                lat, lng,
                m.firstStr(2)?.ifBlank { null } ?: m.firstStr(3).orEmpty(),
            )
        }
        val flat = ll.firstFloat(1)?.toDouble()
        val flng = ll.firstFloat(2)?.toDouble()
        if (flat != null && flng != null && plausible(flat, flng)) {
            return RcsLocation(flat, flng, m.firstStr(2).orEmpty())
        }
    }
    // Flat doubles / floats directly on the content message.
    val lat = m.firstDouble(1)
    val lng = m.firstDouble(2)
    if (lat != null && lng != null && plausible(lat, lng)) {
        return RcsLocation(lat, lng, "")
    }
    val flat = m.firstFloat(1)?.toDouble()
    val flng = m.firstFloat(2)?.toDouble()
    if (flat != null && flng != null && plausible(flat, flng)) {
        return RcsLocation(flat, flng, "")
    }
    // Varint E7 pair (nested or flat).
    val e7 = m.firstMsg(1) ?: m
    val elat = e7.firstVarint(1)
    val elng = e7.firstVarint(2)
    if (elat != null && elng != null) {
        val dlat = elat / 1e7
        val dlng = elng / 1e7
        if (plausible(dlat, dlng)) return RcsLocation(dlat, dlng, "")
    }
    return null
}

/** Decodes conversations.MediaContent from a MessageInfo field-3 map. */
fun decodeMediaContent(m: Map<Int, List<FieldVal>>): RcsMediaAttachment? {
    val mediaId = m.firstStr(2).orEmpty()
    val inline = m.firstBytes(7)
    val thumbnailMediaId = m.firstStr(9).orEmpty()
    if (mediaId.isEmpty() && inline == null && thumbnailMediaId.isEmpty()) return null
    val dims = m.firstMsg(6)
    return RcsMediaAttachment(
        mediaId = mediaId,
        mimeType = m.firstStr(14).orEmpty(),
        decryptionKey = m.firstBytes(11) ?: byteArrayOf(),
        width = dims?.firstVarint(1) ?: 0L,
        height = dims?.firstVarint(2) ?: 0L,
        size = m.firstVarint(5) ?: 0L,
        inlineData = inline,
        thumbnailMediaId = thumbnailMediaId,
        thumbnailKey = m.firstBytes(12) ?: byteArrayOf(),
        name = m.firstStr(4).orEmpty(),
    )
}

/**
 * client.DownloadAttachmentRequest{1:AttachmentInfo{1:attachmentID,
 * 2:encrypted}, 2:AuthMessage} as binary protobuf. The base64 of this goes
 * in the x-goog-download-metadata header of GET .../upload.
 */
fun encodeDownloadAttachmentRequest(
    auth: AuthMessage,
    mediaId: String,
): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Msg(
            mapOf(
                1 to FieldVal.Str(mediaId),
                2 to FieldVal.Varint(1L),
            ),
        ),
        2 to FieldVal.Msg(authMessageFields(auth)),
    ),
)

/**
 * Media UPLOAD request metadata (Garrett 2026-10-19: attachment sending),
 * sent base64 in the x-goog-upload-metadata header of POST .../upload —
 * the mirror of [encodeDownloadAttachmentRequest]. Structure mirrors the
 * download request: field 1 = upload info {1:mimeType, 2:fileName,
 * 3:plaintext size}, field 2 = AuthMessage. The encrypted bytes are the
 * POST body; the response carries the assigned media ID.
 *
 * NOTE: the upload-side metadata schema is the least-verified part of the
 * media flow (the download side is device-proven). Any server rejection
 * surfaces verbatim through the transport's error path so it can be
 * diagnosed from the device.
 */
fun encodeUploadMediaRequest(
    auth: AuthMessage,
    mimeType: String,
    fileName: String,
    plainSize: Long,
): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Msg(
            mapOf(
                1 to FieldVal.Str(mimeType),
                2 to FieldVal.Str(fileName),
                3 to FieldVal.Varint(plainSize),
            ),
        ),
        2 to FieldVal.Msg(authMessageFields(auth)),
    ),
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

/**
 * An uploaded media blob ready to send (Garrett 2026-10-19: attachment
 * sending). [mediaId] comes from the upload endpoint; [decryptionKey] is the
 * sender-generated 32-byte AES key the bytes were encrypted with — it
 * travels in the message itself (MediaContent field 11), which is how the
 * recipient decrypts. [wireSize] is the ENCRYPTED size actually uploaded
 * (MediaContent field 5 carries the encrypted wire size — see the download
 * size-accounting note in RcsTransport).
 */
data class UploadedMediaRef(
    val mediaId: String,
    val decryptionKey: ByteArray,
    val mimeType: String,
    val fileName: String,
    val wireSize: Long,
    val width: Long = 0,
    val height: Long = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is UploadedMediaRef && mediaId == other.mediaId &&
            decryptionKey.contentEquals(other.decryptionKey)
    override fun hashCode(): Int = mediaId.hashCode()
}

/**
 * client.SendMessageRequest carrying a media attachment (Garrett
 * 2026-10-19): same envelope as [encodeSendMessageRequest], but the
 * MessageInfo carries field 3 = MediaContent instead of field 2 text.
 * MediaContent fields (conversations.proto): format=1, mediaID=2,
 * mediaName=4, size=5, dimensions=6{1:width, 2:height}, decryptionKey=11,
 * mimeType=14. An optional caption rides in the standard text content.
 */
fun encodeSendMediaMessageRequest(
    conversationId: String,
    tmpId: String,
    media: UploadedMediaRef,
    caption: String = "",
    forceRcs: Boolean = true,
): ByteArray {
    val mediaContent = mutableMapOf<Int, FieldVal>(
        1 to FieldVal.Varint(1),
        2 to FieldVal.Str(media.mediaId),
        4 to FieldVal.Str(media.fileName),
        5 to FieldVal.Varint(media.wireSize),
        11 to FieldVal.Bytes(media.decryptionKey),
        14 to FieldVal.Str(media.mimeType),
    )
    if (media.width > 0 && media.height > 0) {
        mediaContent[6] = FieldVal.Msg(
            mapOf(
                1 to FieldVal.Varint(media.width),
                2 to FieldVal.Varint(media.height),
            ),
        )
    }
    val payload = mapOf(
        1 to FieldVal.Str(tmpId),
        6 to FieldVal.Msg(mapOf(1 to FieldVal.Msg(mapOf(1 to FieldVal.Str(caption))))),
        7 to FieldVal.Str(conversationId),
        10 to FieldVal.Rep(
            listOf(FieldVal.Msg(mapOf(3 to FieldVal.Msg(mediaContent)))),
        ),
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
/**
 * client.SendMessageRequest carrying a shared location (Garrett
 * 2026-10-20, feature queue 6/7): same envelope as the text/media sends,
 * but the MessagePayload carries TWO MessageInfo entries —
 *
 *  1. field 5 = LocationContent, the native pin: {1: LatLng{1: double
 *     latitude, 2: double longitude}, 2: name (when known)}.
 *  2. field 2 = MessageContent holding [linkText] — a Google Maps link
 *     for the same coordinates, riding in the SAME send so any client
 *     that doesn't render the native payload still shows the recipient
 *     a tappable link instead of nothing. (The payload's field-6 content
 *     mirrors the link text, exactly like a caption rides a media send.)
 *
 * Whether the phone's Google Messages renders the native field-5 pin
 * from a web-client send is UNVERIFIED — the device test against Google
 * Messages settles it; the link entry guarantees the share is never
 * empty either way.
 */
fun encodeSendLocationMessageRequest(
    conversationId: String,
    tmpId: String,
    latitude: Double,
    longitude: Double,
    name: String = "",
    linkText: String,
    forceRcs: Boolean = true,
): ByteArray {
    val locationContent = mutableMapOf<Int, FieldVal>(
        1 to FieldVal.Msg(
            mapOf(
                1 to FieldVal.Fixed64(latitude.toRawBits()),
                2 to FieldVal.Fixed64(longitude.toRawBits()),
            ),
        ),
    )
    if (name.isNotBlank()) locationContent[2] = FieldVal.Str(name)
    val payload = mapOf(
        1 to FieldVal.Str(tmpId),
        6 to FieldVal.Msg(mapOf(1 to FieldVal.Msg(mapOf(1 to FieldVal.Str(linkText))))),
        7 to FieldVal.Str(conversationId),
        10 to FieldVal.Rep(
            listOf(
                FieldVal.Msg(mapOf(5 to FieldVal.Msg(locationContent))),
                FieldVal.Msg(mapOf(2 to FieldVal.Msg(mapOf(1 to FieldVal.Str(linkText))))),
            ),
        ),
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

/**
 * conversations.EmojiType for the 7 standard reaction emojis (conversations.proto).
 * ❤️ is LOVE (the standard Google Messages heart reaction; RED_HEART=12 is a
 * different custom set entry and is NOT used here).
 */
fun emojiTypeFor(emoji: String): Int = when (emoji.replace("\uFE0F", "")) {
    "\uD83D\uDC4D" -> 1 // 👍 LIKE
    "\u2764" -> 2       // ❤️ LOVE
    "\uD83D\uDE02" -> 3 // 😂 LAUGH
    "\uD83D\uDE2E" -> 4 // 😮 SURPRISED
    "\uD83D\uDE22" -> 5 // 😢 SAD
    "\uD83D\uDE21" -> 6 // 😡 ANGRY
    "\uD83D\uDC4E" -> 7 // 👎 DISLIKE
    else -> 2           // default: heart, the most common reaction
}

/** conversations.EmojiType → emoji glyph (inverse of [emojiTypeFor]). */
fun emojiForType(type: Int): String = when (type) {
    1 -> "\uD83D\uDC4D" // LIKE
    2 -> "\u2764\uFE0F" // LOVE
    3 -> "\uD83D\uDE02" // LAUGH
    4 -> "\uD83D\uDE2E" // SURPRISED
    5 -> "\uD83D\uDE22" // SAD
    6 -> "\uD83D\uDE21" // ANGRY
    7 -> "\uD83D\uDC4E" // DISLIKE
    else -> "\u2764\uFE0F"
}

/**
 * client.SendReactionRequest{1:messageID, 2:reactionData{1:unicode, 2:type},
 * 3:action} as binary protobuf (Garrett 2026-10-19: REAL reactions —
 * client.proto; action is the ADD enum value 1). SIMPayload (field 4) is
 * omitted like Relay's other sends — dual-SIM selection isn't a Relay
 * feature and the reference sends it empty-absent for reactions too.
 */
fun encodeSendReactionRequest(
    messageId: String,
    emoji: String,
    action: Int = 1,
): ByteArray = ProtoCodec.encode(
    mapOf(
        1 to FieldVal.Str(messageId),
        2 to FieldVal.Msg(
            mapOf(
                1 to FieldVal.Str(emoji),
                2 to FieldVal.Varint(emojiTypeFor(emoji).toLong()),
            ),
        ),
        3 to FieldVal.Varint(action.toLong()),
    ),
)

/** client.SendReactionResponse{1:success} — false or absent = server declined. */
fun decodeSendReactionResponse(bytes: ByteArray): Boolean =
    ProtoCodec.decode(bytes).firstBool(1) == true

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

/**
 * Server timestamps are MICROseconds (the reference builds them as
 * `UnixMilli() * 1000`); the app sorts and formats in MILLIseconds. A raw
 * µs value compares as "newer" than any System.currentTimeMillis()-stamped
 * optimistic bubble, which put just-sent messages at the TOP of the
 * reverseLayout thread. Normalize to ms at decode time. The magnitude
 * guard keeps this safe if the server ever sends ms: 1e14 ms is the year
 * 5138, so anything larger is unambiguously microseconds.
 */
fun normalizeServerTimestamp(raw: Long): Long =
    if (raw > 100_000_000_000_000L) raw / 1000 else raw

private fun decodeMessageInfo(m: Map<Int, List<FieldVal>>): String? {
    // MessageInfo oneof{2:messageContent, 3:mediaContent, 5:locationContent}
    m.firstMsg(2)?.firstStr(1)?.let { return it }
    return null
}

/**
 * Decodes conversations.Message field 19 (repeated ReactionEntry).
 * ReactionEntry{1:ReactionData{1:unicode, 2:type}, 2:participantIDs}.
 */
private fun decodeWireReactions(m: Map<Int, List<FieldVal>>): List<RcsWireReaction> =
    m.repMsgs(19).mapNotNull { entry ->
        val data = entry.firstMsg(1) ?: return@mapNotNull null
        val unicode = data.firstStr(1).orEmpty()
        val type = data.firstVarint(2)?.toInt() ?: 0
        val emoji = unicode.ifBlank { if (type > 0) emojiForType(type) else "" }
        if (emoji.isBlank()) return@mapNotNull null
        RcsWireReaction(
            emoji = emoji,
            emojiType = type,
            participantIds = entry.repStrs(2),
        )
    }

fun decodeRcsMessage(
    m: Map<Int, List<FieldVal>>,
    participants: List<RcsParticipant> = emptyList(),
    fallbackFromMe: Boolean = false,
): RcsMessage {
    val infos = m.repMsgs(10)
    val media = infos.mapNotNull { it.firstMsg(3)?.let(::decodeMediaContent) }.firstOrNull()
    val location = infos.mapNotNull {
        it.firstMsg(5)?.let(::decodeLocationContent)
    }.firstOrNull()
    return RcsMessage(
        id = m.firstStr(1).orEmpty(),
        conversationId = m.firstStr(7).orEmpty(),
        body = infos.mapNotNull(::decodeMessageInfo).firstOrNull().orEmpty(),
        timestampMs = normalizeServerTimestamp(m.firstVarint(5) ?: 0L),
        fromMe = resolveFromMe(m, participants, fallbackFromMe),
        status = m.firstMsg(4)?.firstVarint(2)?.toInt() ?: 0,
        tmpId = m.firstStr(12).orEmpty(),
        participantId = m.firstStr(9).orEmpty(),
        msgTransportType = m.firstVarint(11)?.toInt() ?: 0,
        media = media,
        location = location,
        wireReactions = decodeWireReactions(m),
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
        timestampMs = normalizeServerTimestamp(m.firstVarint(5) ?: 0L),
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
