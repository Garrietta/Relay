package com.garrietta.relay.data.repo

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "UnifiedInbox"

/**
 * One provider thread row before inbox merging. The provider splits SMS and
 * MMS into separate thread_ids when address formatting differs; [recipients]
 * (normalized distinct addresses) distinguishes 1:1 threads (mergeable) from
 * group threads (never merged).
 */
private data class RawThread(
    val threadId: Long,
    val address: String,
    val recipients: Set<String>,
    val date: Long,
    val snippet: String,
    val unread: Boolean,
    val messageCount: Int,
    val transportLabel: String,
)

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

    /**
     * Serializes conversation loads. Concurrent refreshes race the Telephony
     * provider (DB-locked failures) and each other; without this, a failed
     * load could win the race and clobber a good list.
     */
    private val refreshMutex = Mutex()

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
            // A failed load must NEVER clobber the last good list — that
            // single defect is what wiped the inbox on reopen. Null means
            // "load failed"; only a real result replaces the state.
            refreshMutex.withLock {
                val loaded = loadConversations()
                if (loaded == null) {
                    Log.w(TAG, "refresh: conversation load failed; keeping last-good list")
                } else {
                    _conversations.value = loaded
                    refreshTick.value += 1
                }
            }
        }
    }

    /** Emits whenever the inbox data changes; chat screens collect to reload. */
    fun inboxChanges(): Flow<Int> = refreshTick

    /**
     * Loads the conversation list, or null when the load FAILED. Null is
     * distinct from "no conversations": [refresh] keeps the last-good list on
     * null so a transient provider failure can never wipe the inbox.
     *
     * Thread list: content://mms-sms/conversations?simple=true — the standard
     * Telephony.Threads URI; the `simple` flag is honored by AOSP's
     * MmsSmsProvider. Note its `snippet` column comes back blank for MMS
     * threads on Samsung's provider, hence the [fallbackSnippet] below.
     *
     * 1:1 threads that resolve to the same normalized address are MERGED
     * into one Conversation (the provider splits SMS and MMS into separate
     * thread_ids when address formatting differs, e.g. "+15551234567" vs
     * "5551234567"). Group (multi-recipient) threads are never merged.
     */
    suspend fun loadConversations(): List<Conversation>? = withContext(Dispatchers.IO) {
        val raw = mutableListOf<RawThread>()
        try {
            val uri = Uri.parse("content://mms-sms/conversations?simple=true")
            val projection = arrayOf("_id", "date", "message_count", "snippet", "read")
            val cursor = context.contentResolver.query(uri, projection, null, null, "date DESC")
            if (cursor == null) {
                // Busy/unavailable provider — transient, not "no conversations".
                Log.w(TAG, "loadConversations: provider returned null cursor")
                return@withContext null
            }
            var rows = 0
            var dropped = 0
            cursor.use { c ->
                val idI = c.getColumnIndex("_id")
                val dateI = c.getColumnIndex("date")
                val countI = c.getColumnIndex("message_count")
                val snippetI = c.getColumnIndex("snippet")
                val readI = c.getColumnIndex("read")
                while (c.moveToNext()) {
                    rows++
                    val threadId = if (idI >= 0) c.getLong(idI) else continue
                    val address = threadAddress(threadId)
                    if (address == null) {
                        dropped++
                        Log.w(TAG, "loadConversations: no address for thread $threadId; dropping row")
                        continue
                    }
                    val providerSnippet = if (snippetI >= 0) c.getString(snippetI).orEmpty() else ""
                    val snippet = providerSnippet.ifBlank { fallbackSnippet(threadId) }
                    raw.add(
                        RawThread(
                            threadId = threadId,
                            address = address,
                            recipients = threadRecipients(threadId),
                            date = if (dateI >= 0) c.getLong(dateI) else 0L,
                            snippet = snippet,
                            unread = !(readI >= 0 && c.getInt(readI) == 1),
                            messageCount = if (countI >= 0) c.getInt(countI) else 0,
                            transportLabel = if (looksLikeMmsThread(threadId)) "MMS" else "SMS",
                        )
                    )
                }
            }
            if (rows > 0 && dropped == rows) {
                // Every row failed address resolution — systemic failure, not
                // an empty inbox. Null keeps the last-good list.
                Log.w(TAG, "loadConversations: all $rows threads dropped in address resolution")
                return@withContext null
            }
        } catch (e: SecurityException) {
            // READ_SMS not granted yet — empty inbox until permissions arrive.
            Log.w(TAG, "loadConversations: READ_SMS not granted", e)
            return@withContext null
        } catch (e: Exception) {
            Log.w(TAG, "loadConversations: query failed", e)
            return@withContext null
        }
        mergeThreads(raw).sortedByDescending { it.date }
    }

    /**
     * Merges 1:1 threads that resolve to the same normalized address into a
     * single [Conversation]. Group (multi-recipient) threads are never merged
     * with anything — they keep their own row. Threads whose address can't be
     * normalized to a real phone number (short codes, alphanumeric senders)
     * are never merged either.
     */
    private fun mergeThreads(raw: List<RawThread>): List<Conversation> {
        val singles = raw.filter { it.recipients.size <= 1 }
        val groups = raw.filter { it.recipients.size > 1 }
        // Bucket mergeable 1:1 threads by normalized address; anything that
        // doesn't normalize to a real number keeps its own bucket.
        val buckets = mutableMapOf<String, MutableList<RawThread>>()
        val unmerged = mutableListOf<RawThread>()
        for (t in singles) {
            // Primary key: contact ID (same person, possibly different
            // numbers — e.g. the two "Alec Siurek" threads). Fallback:
            // normalized address. Either one buckets the thread.
            val key = contactLookup.contactId(t.address)?.let { "contact:$it" }
                ?: mergeKey(t.address)?.let { "addr:$it" }
            if (key == null) {
                Log.d(TAG, "mergeThreads: no merge key for thread ${t.threadId} (${t.address})")
                unmerged.add(t)
            } else {
                buckets.getOrPut(key) { mutableListOf() }.add(t)
            }
        }
        val out = mutableListOf<Conversation>()
        for (bucket in buckets.values) out.add(mergeBucket(bucket))
        for (t in unmerged) out.add(t.toConversation(listOf(t.threadId)))
        for (t in groups) {
            Log.d(TAG, "mergeThreads: keeping group thread ${t.threadId} separate")
            out.add(t.toConversation(listOf(t.threadId)))
        }
        return out
    }

    /** Merges one bucket of same-address 1:1 threads. Latest wins for the
     *  row fields; unread is OR-ed; message counts are summed. */
    private fun mergeBucket(bucket: List<RawThread>): Conversation {
        val sorted = bucket.sortedByDescending { it.date }
        val primary = sorted.first()
        val ids = sorted.map { it.threadId }
        if (ids.size > 1) {
            Log.d(TAG, "mergeThreads: merged ${ids.size} threads for ${primary.address}: $ids")
        }
        return Conversation(
            threadId = primary.threadId,
            address = primary.address,
            displayName = contactLookup.displayName(primary.address),
            snippet = primary.snippet,
            date = primary.date,
            unread = sorted.any { it.unread },
            messageCount = sorted.sumOf { it.messageCount },
            transportLabel = primary.transportLabel,
            photoUri = contactLookup.photoUri(primary.address),
            threadIds = ids,
        )
    }

    private fun RawThread.toConversation(ids: List<Long>): Conversation =
        Conversation(
            threadId = threadId,
            address = address,
            displayName = contactLookup.displayName(address),
            snippet = snippet,
            date = date,
            unread = unread,
            messageCount = messageCount,
            transportLabel = transportLabel,
            photoUri = contactLookup.photoUri(address),
            threadIds = ids,
        )

    /**
     * Normalizes an address to a merge key: digits only; NANP numbers compare
     * by last 10 digits. Returns null when the address isn't a mergeable
     * phone number (short codes, alphanumeric senders) — those are never
     * merged.
     */
    private fun mergeKey(address: String): String? {
        val digits = address.filter { it.isDigit() }
        if (digits.length < 7) return null
        return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
    }

    /** Digits-only normalization used for recipient-set comparison. */
    private fun normalizeAddress(address: String): String = address.filter { it.isDigit() }

    /**
     * All distinct normalized recipient addresses in a thread — latest SMS
     * address plus every SMS address plus MMS TO/FROM addresses. More than
     * one distinct recipient means a group thread.
     */
    private fun threadRecipients(threadId: Long): Set<String> {
        val out = mutableSetOf<String>()
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(0)
                    if (!a.isNullOrBlank()) out.add(normalizeAddress(a))
                }
            }
        } catch (_: Exception) { }
        // MMS side: the latest message's addresses are representative — every
        // message in a group MMS thread carries the full recipient list.
        try {
            var mmsId = -1L
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) mmsId = c.getLong(0)
            }
            if (mmsId >= 0) {
                context.contentResolver.query(
                    Uri.parse("content://mms/$mmsId/addr"),
                    arrayOf("address", "type"), null, null, null,
                )?.use { c ->
                    val addrI = c.getColumnIndex("address")
                    val typeI = c.getColumnIndex("type")
                    while (c.moveToNext()) {
                        val a = if (addrI >= 0) c.getString(addrI) else null
                        if (a.isNullOrBlank() || a == "insert-address-token") continue
                        val t = if (typeI >= 0) c.getInt(typeI) else -1
                        // 137 (0x89) = FROM, 151 (0x97) = TO
                        if (t == 137 || t == 151) out.add(normalizeAddress(a))
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * Loads messages across all [threadIds] of a (possibly merged)
     * conversation, oldest first. Merged 1:1 threads show one full history.
     */
    suspend fun loadMessages(threadIds: List<Long>): List<Message> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Message>()
        if (threadIds.isEmpty()) return@withContext out
        val placeholders = threadIds.joinToString(",") { "?" }
        val args = threadIds.map { it.toString() }.toTypedArray()
        // Representative thread id for the merged view (latest thread).
        val primaryThreadId = threadIds.first()
        try {
            // SMS parts
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "thread_id", "address", "body", "date", "type", "read"),
                "thread_id IN ($placeholders)",
                args,
                "date ASC",
            )?.use { c ->
                val idI = c.getColumnIndex("_id")
                val threadI = c.getColumnIndex("thread_id")
                val addrI = c.getColumnIndex("address")
                val bodyI = c.getColumnIndex("body")
                val dateI = c.getColumnIndex("date")
                val typeI = c.getColumnIndex("type")
                val readI = c.getColumnIndex("read")
                while (c.moveToNext()) {
                    out.add(
                        Message(
                            id = if (idI >= 0) c.getLong(idI) else 0L,
                            threadId = if (threadI >= 0) c.getLong(threadI) else primaryThreadId,
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
                arrayOf("_id", "thread_id", "date", "msg_box", "read"),
                "thread_id IN ($placeholders)",
                args,
                "date ASC",
            )?.use { c ->
                val idI = c.getColumnIndex("_id")
                val threadI = c.getColumnIndex("thread_id")
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
                            threadId = if (threadI >= 0) c.getLong(threadI) else primaryThreadId,
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

    /** Single-thread convenience; delegates to the merged loader. */
    suspend fun loadMessages(threadId: Long): List<Message> = loadMessages(listOf(threadId))

    suspend fun markThreadRead(threadIds: List<Long>) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply { put("read", 1) }
            for (threadId in threadIds) {
                context.contentResolver.update(
                    Uri.parse("content://sms/inbox"), values,
                    "thread_id=? AND read=0", arrayOf(threadId.toString()),
                )
                context.contentResolver.update(
                    Uri.parse("content://mms/inbox"), values,
                    "thread_id=? AND read=0", arrayOf(threadId.toString()),
                )
            }
        } catch (_: Exception) { }
        refresh()
    }

    suspend fun markThreadRead(threadId: Long) = markThreadRead(listOf(threadId))

    suspend fun markThreadUnread(threadIds: List<Long>) = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply { put("read", 0) }
            for (threadId in threadIds) {
                context.contentResolver.update(
                    Uri.parse("content://sms/inbox"), values,
                    "thread_id=?", arrayOf(threadId.toString()),
                )
            }
        } catch (_: Exception) { }
        refresh()
    }

    suspend fun markThreadUnread(threadId: Long) = markThreadUnread(listOf(threadId))

    suspend fun deleteThread(threadIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            for (threadId in threadIds) {
                context.contentResolver.delete(
                    Uri.parse("content://mms-sms/conversations/$threadId"), null, null,
                )
            }
            refresh()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteThread(threadId: Long): Boolean = deleteThread(listOf(threadId))

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

    /**
     * Newest message text for [threadId], used when the conversations
     * provider's snippet column is blank (MMS threads with ?simple=true on
     * Samsung's provider). Newest of the latest SMS body and the latest MMS
     * text part wins.
     */
    private fun fallbackSnippet(threadId: Long): String {
        var best: String? = null
        var bestDate = -1L
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("body", "date"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    val body = c.getString(0)
                    if (!body.isNullOrBlank()) {
                        best = body
                        bestDate = c.getLong(1)
                    }
                }
            }
        } catch (_: Exception) { }
        try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    val text = mmsText(c.getLong(0))
                    val date = c.getLong(1) * 1000L
                    if (text.isNotBlank() && date >= bestDate) best = text
                }
            }
        } catch (_: Exception) { }
        return best.orEmpty()
    }

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

/** In-memory cache over PhoneLookup. Methods are synchronized: refreshes run
 * on Dispatchers.IO and must never hit ConcurrentModificationException on
 * these maps (which used to surface as a swallowed load failure). */
private class ContactLookup(private val context: Context) {
    private val cache = mutableMapOf<String, String>()
    private val photoCache = mutableMapOf<String, String?>()

    @Synchronized
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

    /**
     * Raw contact ID for [address], or null when not in contacts. Used as a
     * merge key so two different numbers for the SAME person (e.g. mobile +
     * work) land in one conversation.
     */
    @Synchronized
    fun contactId(address: String): Long? {
        return try {
            var result: Long? = null
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address),
                ),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) result = c.getLong(0)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /** Profile photo URI for [address], or null when the contact has none. */
    @Synchronized
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
