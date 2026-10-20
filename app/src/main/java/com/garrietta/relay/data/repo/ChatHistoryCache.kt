package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.rcs.proto.RcsMediaAttachment
import java.io.File
import java.util.Base64

/**
 * Persistent LRU text-message cache (Garrett 2026-10-19): the 50 most
 * recently active conversations keep their full TEXT history on disk —
 * bodies and metadata only, NEVER image bytes (images keep their own
 * rcs_media disk cache and re-download/decrypt on demand; the attachment's
 * ids + keys are preserved so a cached media bubble can still fetch).
 *
 * Every new incoming/outgoing message touches its conversation (moves to
 * the front of the LRU). When a 51st conversation activates, the
 * least-recently-used drops out of the ACTIVE set — its file STAYS on
 * disk, it's just no longer kept fresh. Opening any cached chat paints
 * instantly from disk, then live-refreshes behind it.
 *
 * Hand-rolled JSON (flat string objects): org.json is an Android-framework
 * stub in plain JVM unit tests and throws "Stub!" there — same approach
 * as CategoryStore's codec.
 */
class ChatHistoryCache(dir: File) {

    companion object {
        /** Active-set cap: the 50 most recently active conversations. */
        const val MAX_ACTIVE = 50
    }

    /**
     * Cache directory version: v3 (2026-10-20). The v1 directory
     * ("chat_history") may hold histories written while the
     * cross-conversation merge was live — message lists from SEVERAL
     * server conversations mixed under one canonical conversation's
     * key. The v2 directory may hold histories written while the
     * person-level self-sender guard was live — every message from
     * Garrett's own number flattened to outgoing, including the self
     * conversation's legitimately incoming half (cached rows persist
     * isOutgoing per message). Neither set may ever be served again,
     * so the cache moved to a fresh directory; the older files are
     * simply orphaned (never read), exactly like the media cache's
     * v3 → v4 bump.
     */
    private val root = File(dir, "chat_history_v3").also { it.mkdirs() }
    private val orderFile = File(root, "_order.json")

    /** Most-recent-first keys. Guarded by the instance monitor. */
    private var order: ArrayDeque<String> = ArrayDeque()

    init {
        order = ArrayDeque(readOrder())
    }

    private fun fileFor(key: String): File {
        // Collision-proof: sanitized stem + full 32-bit hash.
        val stem = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val h = (key.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        return File(root, "${stem}_${h}.json")
    }

    /** Disk read; null when nothing is saved (or the file is corrupt). */
    @Synchronized
    fun read(key: String): List<Message>? {
        val f = fileFor(key)
        if (!f.exists()) return null
        return runCatching { deserialize(f.readText()) }.getOrNull()
    }

    /**
     * Full write-through: stores the text history and marks the
     * conversation most-recently-active. Eviction trims the ACTIVE order
     * to 50 — evicted files REMAIN on disk (stale but readable).
     */
    @Synchronized
    fun write(key: String, messages: List<Message>) {
        runCatching { fileFor(key).writeText(serialize(messages)) }
        touchLocked(key)
    }

    /** Recency bump without rewriting history (a single new message). */
    @Synchronized
    fun touch(key: String) {
        touchLocked(key)
    }

    private fun touchLocked(key: String) {
        order.remove(key)
        order.addFirst(key)
        while (order.size > MAX_ACTIVE) order.removeLast()
        persistOrder()
    }

    private fun readOrder(): List<String> {
        val raw = runCatching { orderFile.readText() }.getOrNull() ?: return emptyList()
        val r = JsonReader(raw)
        if (!r.expect('[')) return emptyList()
        val out = mutableListOf<String>()
        if (r.expect(']')) return out
        while (true) {
            out.add(r.str() ?: return emptyList())
            if (r.expect(']')) return out
            if (!r.expect(',')) return emptyList()
        }
    }

    private fun persistOrder() {
        runCatching {
            orderFile.writeText(order.joinToString(",", "[", "]") { "\"${escape(it)}\"" })
        }
    }

    // ---- Serialization: one flat string-object per message ----

    private fun b64(bytes: ByteArray): String =
        if (bytes.isEmpty()) "" else Base64.getEncoder().encodeToString(bytes)

    private fun unb64(s: String): ByteArray =
        if (s.isEmpty()) byteArrayOf()
        else runCatching { Base64.getDecoder().decode(s) }.getOrDefault(byteArrayOf())

    private fun serialize(messages: List<Message>): String =
        messages.joinToString(",", "[", "]") { m ->
            val a = m.media
            val pairs = listOf(
                "id" to m.id.toString(),
                "threadId" to m.threadId.toString(),
                "address" to m.address,
                "body" to m.body,
                "date" to m.date.toString(),
                "out" to if (m.isOutgoing) "1" else "0",
                "mms" to if (m.isMms) "1" else "0",
                "read" to if (m.read) "1" else "0",
                "tmp" to (m.tmpId ?: ""),
                "ds" to m.deliveryStatus.name,
                // Media metadata WITHOUT bytes: enough to re-download.
                "mmid" to (a?.mediaId ?: ""),
                "mmime" to (a?.mimeType ?: ""),
                "mkey" to b64(a?.decryptionKey ?: byteArrayOf()),
                "mw" to (a?.width?.toString() ?: ""),
                "mh" to (a?.height?.toString() ?: ""),
                "msize" to (a?.size?.toString() ?: ""),
                "mtmid" to (a?.thumbnailMediaId ?: ""),
                "mtkey" to b64(a?.thumbnailKey ?: byteArrayOf()),
                "mname" to (a?.name ?: ""),
                // Location pin (feature queue 6/7): coordinates + label,
                // so a cached location message reopens as a location card,
                // not a bare link bubble. Absent keys = no pin (old files).
                "llat" to (m.location?.latitude?.toString() ?: ""),
                "llng" to (m.location?.longitude?.toString() ?: ""),
                "lname" to (m.location?.name ?: ""),
            )
            pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${escape(v)}\"" }
        }

    private fun deserialize(json: String): List<Message>? {
        val r = JsonReader(json)
        if (!r.expect('[')) return null
        val out = mutableListOf<Message>()
        if (r.expect(']')) return out
        while (true) {
            val o = r.flatObject() ?: return null
            out.add(o.toMessage() ?: return null)
            if (r.expect(']')) break
            if (!r.expect(',')) return null
        }
        r.ws()
        if (r.i < r.s.length) return null
        return out
    }

    private fun Map<String, String>.toMessage(): Message? {
        val media =
            if (get("mmid").isNullOrEmpty() && get("mmime").isNullOrEmpty()) null
            else RcsMediaAttachment(
                mediaId = get("mmid").orEmpty(),
                mimeType = get("mmime").orEmpty(),
                decryptionKey = unb64(get("mkey").orEmpty()),
                width = get("mw")?.toLongOrNull() ?: 0L,
                height = get("mh")?.toLongOrNull() ?: 0L,
                size = get("msize")?.toLongOrNull() ?: 0L,
                inlineData = null, // text-only cache: never image bytes
                thumbnailMediaId = get("mtmid").orEmpty(),
                thumbnailKey = unb64(get("mtkey").orEmpty()),
                name = get("mname").orEmpty(),
            )
        return Message(
            id = get("id")?.toLongOrNull() ?: return null,
            threadId = get("threadId")?.toLongOrNull() ?: 0L,
            address = get("address").orEmpty(),
            body = get("body").orEmpty(),
            date = get("date")?.toLongOrNull() ?: 0L,
            isOutgoing = get("out") == "1",
            isMms = get("mms") == "1",
            read = get("read") != "0",
            tmpId = get("tmp")?.ifBlank { null },
            media = media,
            location = run {
                val la = get("llat")?.toDoubleOrNull()
                val lo = get("llng")?.toDoubleOrNull()
                if (la != null && lo != null) {
                    com.garrietta.relay.rcs.proto.RcsLocation(
                        la, lo, get("lname").orEmpty(),
                    )
                } else null
            },
            deliveryStatus = runCatching {
                DeliveryStatus.valueOf(get("ds") ?: "NONE")
            }.getOrDefault(DeliveryStatus.NONE),
        )
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private class JsonReader(val s: String) {
        var i = 0
        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun expect(c: Char): Boolean {
            ws()
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        fun str(): String? {
            ws()
            if (i >= s.length || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' && i < s.length -> when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i, minOf(i + 4, s.length))
                            if (hex.length == 4) {
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
            return null // unterminated string
        }

        /** Reads a flat {"k":"v",...} object; null when malformed. */
        fun flatObject(): Map<String, String>? {
            if (!expect('{')) return null
            val m = mutableMapOf<String, String>()
            if (expect('}')) return m
            while (true) {
                val k = str() ?: return null
                if (!expect(':')) return null
                val v = str() ?: return null
                m[k] = v
                if (expect('}')) return m
                if (!expect(',')) return null
            }
        }
    }
}
