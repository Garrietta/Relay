package com.garrietta.relay.data.repo

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.transport.IncomingMessage
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.data.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Merges all [Transport]s into one chronological unified inbox.
 *
 * Conversation/message reads go through the Telephony providers
 * (content://mms-sms/...), which the SMS receiver keeps up to date. A
 * [ContentObserver] refreshes the list on any provider change, and incoming
 * transport events force a refresh too — so RCS messages will appear here with
 * zero repository changes once [RcsTransport] is real.
 */
class UnifiedInboxRepository(
    private val context: Context,
    val smsTransport: SmsTransport,
    val rcsTransport: RcsTransport,
) {
    val transports: List<Transport> = listOf(smsTransport, rcsTransport)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contactLookup = ContactLookup(context)

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val refreshTick = MutableStateFlow(0)

    init {
        // Observe the Telephony provider: any SMS/MMS change refreshes the inbox.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { refresh() }
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://mms-sms/conversations"),
                true,
                observer,
            )
        } catch (_: Exception) { /* no SMS permission yet */ }

        // Transport-level incoming events also refresh (covers future RCS).
        transports.forEach { transport ->
            scope.launch {
                transport.observeIncoming().collect {
                    refresh()
                }
            }
        }

        scope.launch { refresh() }
    }

    fun refresh() {
        scope.launch {
            _conversations.value = loadConversations()
            refreshTick.value += 1
        }
    }

    /** Emits whenever the inbox data changes; chat screens collect to reload. */
    fun inboxChanges(): Flow<Int> = refreshTick

    suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Conversation>()
        try {
            val uri = Uri.parse("content://mms-sms/conversations?simple=true")
            val projection = arrayOf("_id", "date", "message_count", "snippet", "read")
            context.contentResolver.query(uri, projection, null, null, "date DESC")
                ?.use { c ->
                    val idI = c.getColumnIndex("_id")
                    val dateI = c.getColumnIndex("date")
                    val countI = c.getColumnIndex("message_count")
                    val snippetI = c.getColumnIndex("snippet")
                    val readI = c.getColumnIndex("read")
                    while (c.moveToNext()) {
                        val threadId = if (idI >= 0) c.getLong(idI) else continue
                        val address = threadAddress(threadId) ?: continue
                        val snippet = if (snippetI >= 0) c.getString(snippetI).orEmpty() else ""
                        out.add(
                            Conversation(
                                threadId = threadId,
                                address = address,
                                displayName = contactLookup.displayName(address),
                                snippet = snippet,
                                date = if (dateI >= 0) c.getLong(dateI) else 0L,
                                unread = !(readI >= 0 && c.getInt(readI) == 1),
                                messageCount = if (countI >= 0) c.getInt(countI) else 0,
                                transportLabel = if (looksLikeMmsThread(threadId)) "MMS" else "SMS",
                                photoUri = contactLookup.photoUri(address),
                            )
                        )
                    }
                }
        } catch (_: SecurityException) {
            // READ_SMS not granted yet — empty inbox until permissions arrive.
        } catch (_: Exception) { /* keep what we have */ }
        out
    }

    suspend fun loadMessages(threadId: Long): List<Message> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Message>()
        try {
            // SMS parts
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "thread_id", "address", "body", "date", "type", "read"),
                "thread_id=?",
                arrayOf(threadId.toString()),
                "date ASC",
            )?.use { c ->
                val idI = c.getColumnIndex("_id")
                val addrI = c.getColumnIndex("address")
                val bodyI = c.getColumnIndex("body")
                val dateI = c.getColumnIndex("date")
                val typeI = c.getColumnIndex("type")
                val readI = c.getColumnIndex("read")
                while (c.moveToNext()) {
                    out.add(
                        Message(
                            id = if (idI >= 0) c.getLong(idI) else 0L,
                            threadId = threadId,
                            address = if (addrI >= 0) c.getString(addrI).orEmpty() else "",
                            body = if (bodyI >= 0) c.getString(bodyI).orEmpty() else "",
                            date = if (dateI >= 0) c.getLong(dateI) else 0L,
                            isOutgoing = typeI >= 0 && c.getInt(typeI) == 2,
                            isMms = false,
                            read = readI < 0 || c.getInt(readI) == 1,
                        )
                    )
                }
            }
            // MMS parts (text only for v1)
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date", "msg_box", "read"),
                "thread_id=?",
                arrayOf(threadId.toString()),
                "date ASC",
            )?.use { c ->
                val idI = c.getColumnIndex("_id")
                val dateI = c.getColumnIndex("date")
                val boxI = c.getColumnIndex("msg_box")
                val readI = c.getColumnIndex("read")
                while (c.moveToNext()) {
                    val mmsId = if (idI >= 0) c.getLong(idI) else continue
                    val text = mmsText(mmsId)
                    if (text.isBlank()) continue
                    out.add(
                        Message(
                            id = -mmsId, // negative to avoid colliding with SMS ids
                            threadId = threadId,
                            address = mmsAddress(mmsId).orEmpty(),
                            body = text,
                            date = if (dateI >= 0) c.getLong(dateI) * 1000L else 0L,
                            isOutgoing = boxI >= 0 && c.getInt(boxI) == 2,
                            isMms = true,
                            read = readI < 0 || c.getInt(readI) == 1,
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) { /* best effort */ }
        out.sortedBy { it.date }
    }

    suspend fun markThreadRead(threadId: Long) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply { put("read", 1) }
            context.contentResolver.update(
                Uri.parse("content://sms/inbox"), values,
                "thread_id=? AND read=0", arrayOf(threadId.toString()),
            )
            context.contentResolver.update(
                Uri.parse("content://mms/inbox"), values,
                "thread_id=? AND read=0", arrayOf(threadId.toString()),
            )
        } catch (_: Exception) { }
        refresh()
    }

    suspend fun markThreadUnread(threadId: Long) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply { put("read", 0) }
            context.contentResolver.update(
                Uri.parse("content://sms/inbox"), values,
                "thread_id=?", arrayOf(threadId.toString()),
            )
        } catch (_: Exception) { }
        refresh()
    }

    suspend fun deleteThread(threadId: Long): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            context.contentResolver.delete(
                Uri.parse("content://mms-sms/conversations/$threadId"), null, null,
            )
            refresh()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun resolveDisplayName(address: String): String =
        withContext(Dispatchers.IO) { contactLookup.displayName(address) }

    /** Contact profile photo URI for [address], or null when there is none. */
    suspend fun resolvePhotoUri(address: String): String? =
        withContext(Dispatchers.IO) { contactLookup.photoUri(address) }

    /**
     * Extracts a Material-3 seed color from the contact's profile photo via
     * the Palette API (vibrant → dominant → muted). Result is cached per
     * photo URI. Null when there is no photo or extraction fails — callers
     * fall back to the wallpaper dynamic scheme.
     */
    suspend fun extractContactSeed(photoUri: String): androidx.compose.ui.graphics.Color? =
        withContext(Dispatchers.IO) {
            if (ContactSeedCache.contains(photoUri)) return@withContext ContactSeedCache.get(photoUri)
            val color = try {
                context.contentResolver.openInputStream(android.net.Uri.parse(photoUri))?.use { stream ->
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                        ?: return@use null
                    val palette = androidx.palette.graphics.Palette.from(bmp).generate()
                    val swatch = palette.vibrantSwatch
                        ?: palette.dominantSwatch
                        ?: palette.mutedSwatch
                    swatch?.let { androidx.compose.ui.graphics.Color(it.rgb) }
                }
            } catch (_: Exception) {
                null
            }
            ContactSeedCache.put(photoUri, color)
            color
        }

    /** Finds the thread id for an address (used after starting a new conversation). */
    suspend fun findThreadId(address: String): Long = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("thread_id"), "address=?", arrayOf(address),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) return@withContext c.getLong(0)
            }
        } catch (_: Exception) { }
        -1L
    }

    fun observeIncoming(): Flow<IncomingMessage> = callbackFlow {
        val jobs = transports.map { t ->
            scope.launch { t.observeIncoming().collect { trySend(it) } }
        }
        awaitClose { jobs.forEach { it.cancel() } }
    }

    // ---- provider helpers ----

    private fun threadAddress(threadId: Long): String? {
        // Prefer the latest SMS address…
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    val a = c.getString(0)
                    if (!a.isNullOrBlank()) return a
                }
            }
        } catch (_: Exception) { }
        // …fall back to the MMS sender.
        try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    val addr = mmsAddress(c.getLong(0))
                    if (!addr.isNullOrBlank()) return addr
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun looksLikeMmsThread(threadId: Long): Boolean {
        return try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun mmsText(mmsId: Long): String {
        val sb = StringBuilder()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text", "_data"),
                "mid=?", arrayOf(mmsId.toString()), null,
            )?.use { c ->
                val ctI = c.getColumnIndex("ct")
                val textI = c.getColumnIndex("text")
                val dataI = c.getColumnIndex("_data")
                while (c.moveToNext()) {
                    val ct = if (ctI >= 0) c.getString(ctI) else ""
                    if (ct == "text/plain") {
                        var t = if (textI >= 0) c.getString(textI) else null
                        if (t == null && dataI >= 0) {
                            val partId = c.getLong(c.getColumnIndex("_id"))
                            t = readPartData(partId)
                        }
                        if (!t.isNullOrBlank()) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(t)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return sb.toString()
    }

    private fun readPartData(partId: Long): String? {
        return try {
            context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }

    private fun mmsAddress(mmsId: Long): String? {
        // addr types: 137 (0x89) = FROM, 151 (0x97) = TO
        return try {
            var result: String? = null
            context.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), null, null, null,
            )?.use { c ->
                val addrI = c.getColumnIndex("address")
                val typeI = c.getColumnIndex("type")
                var fallback: String? = null
                while (c.moveToNext()) {
                    val a = if (addrI >= 0) c.getString(addrI) else null
                    if (a.isNullOrBlank() || a == "insert-address-token") continue
                    val t = if (typeI >= 0) c.getInt(typeI) else -1
                    if (t == 137) {
                        result = a
                        break
                    }
                    if (fallback == null) fallback = a
                }
                if (result == null) result = fallback
            }
            result
        } catch (_: Exception) {
            null
        }
    }
}

/** In-memory cache over PhoneLookup. */
private class ContactLookup(private val context: Context) {
    private val cache = mutableMapOf<String, String>()
    private val photoCache = mutableMapOf<String, String?>()

    fun displayName(address: String): String {
        cache[address]?.let { return it }
        val name = try {
            var result: String? = null
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address),
                ),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) result = c.getString(0)
            }
            result ?: address
        } catch (_: Exception) {
            address
        }
        cache[address] = name
        return name
    }

    /** Profile photo URI for [address], or null when the contact has none. */
    fun photoUri(address: String): String? {
        if (photoCache.containsKey(address)) return photoCache[address]
        val uri = try {
            var result: String? = null
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address),
                ),
                arrayOf(ContactsContract.PhoneLookup.PHOTO_URI),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) result = c.getString(0)
            }
            result
        } catch (_: Exception) {
            null
        }
        photoCache[address] = uri
        return uri
    }
}

/** Photo-URI → seed-color cache for per-contact chat theming. */
internal object ContactSeedCache {
    private val map = mutableMapOf<String, androidx.compose.ui.graphics.Color?>()

    @Synchronized
    fun contains(photoUri: String): Boolean = map.containsKey(photoUri)

    @Synchronized
    fun get(photoUri: String): androidx.compose.ui.graphics.Color? = map[photoUri]

    @Synchronized
    fun put(photoUri: String, color: androidx.compose.ui.graphics.Color?) {
        map[photoUri] = color
    }
}
