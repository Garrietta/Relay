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
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.transport.IncomingMessage
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.data.transport.Transport
import com.garrietta.relay.rcs.proto.cleanRcsParticipantList
import com.garrietta.relay.rcs.proto.displayNameOrNumber
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

    /**
     * The RCS conversation id of the chat currently on screen (null when
     * none). Set by ChatViewModel via ChatScreen's DisposableEffect; read by
     * MainActivity's notification collector so an incoming message for the
     * OPEN chat doesn't buzz while Garrett is already looking at it.
     */
    val openRcsConversation = MutableStateFlow<String?>(null)

    /**
     * In-memory message cache (Garrett 2026-10-19: "any time i tap on a chat
     * it takes a second to load the messages — that should just be cached").
     * ChatViewModel shows the cached list INSTANTLY on open, then refreshes
     * in the background; every load path writes through here, and live
     * arrivals update it too. LRU-capped; keyed by
     * CategoryStore.conversationKey (stable across restarts for RCS and
     * Telephony rows alike).
     */
    private val messageCache =
        object : LinkedHashMap<String, List<com.garrietta.relay.data.model.Message>>(48, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<com.garrietta.relay.data.model.Message>>,
            ): Boolean = size > 48
        }

    /** Synchronous cache read for instant chat open. */
    @Synchronized
    fun cachedMessages(conversationKey: String): List<com.garrietta.relay.data.model.Message>? =
        messageCache[conversationKey]

    @Synchronized
    fun putCachedMessages(conversationKey: String, messages: List<com.garrietta.relay.data.model.Message>) {
        messageCache[conversationKey] = messages
    }

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

        // Paired-mode switch: when the RCS pairing flips, the inbox source
        // flips with it (paired = server only, never the Telephony provider).
        scope.launch {
            rcsTransport.pairingState.collect { refresh() }
        }

        scope.launch { refresh() }
    }

    /** Last LIST_CONVERSATIONS pull (paired mode) — bounds recovery refreshes. */
    private var lastRcsSyncMs = 0L

    fun refresh() {
        scope.launch {
            // A failed load must NEVER clobber the last good list — that
            // single defect is what wiped the inbox on reopen. Null means
            // "load failed"; only a real result replaces the state.
            refreshMutex.withLock {
                // Paired: make sure the server list is fresh before mapping.
                // The pull at session start may have failed (or been stolen
                // by a pre-response message); an empty/stale list recovers
                // here instead of showing "No conversations yet" forever.
                // Failures surface verbatim via rcsSyncErrors (snackbar).
                if (rcsTransport.isPairedNow) {
                    val now = System.currentTimeMillis()
                    if (rcsTransport.currentRcsConversations().isEmpty() || now - lastRcsSyncMs > 60_000) {
                        rcsTransport.refreshConversations()
                        lastRcsSyncMs = now
                    }
                }
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
        // PAIRED: the Google Messages sync is the SINGLE source of truth for
        // SMS+MMS+RCS — the Telephony provider is NEVER read here (no
        // duplicates, no second copy). UNPAIRED: the Telephony fallback below.
        if (rcsTransport.isPairedNow) {
            return@withContext loadRcsConversations()
        }
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
                            // The chip describes the LATEST message — the one the
                            // snippet came from — not "any MMS ever in this
                            // thread". (The old looksLikeMmsThread labeled
                            // SMS threads "MMS" when an old MMS sat in them;
                            // diagnosed 2026-10-18 from short-code rows.)
                            transportLabel = latestTransportLabel(threadId),
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

    // ---------- RCS (paired mode) ----------

    /** Synchronous paired check for send-path routing. */
    fun isRcsPaired(): Boolean = rcsTransport.isPairedNow

    /** Verbatim RCS sync errors for the UI (no logcat on his device). */
    fun rcsSyncErrors(): Flow<String?> = rcsTransport.syncError
    fun clearRcsSyncError() = rcsTransport.clearSyncError()

    /** Live incoming RCS messages (server echoes + new arrivals). */
    fun observeRcsMessages(): Flow<com.garrietta.relay.rcs.proto.RcsMessage> =
        rcsTransport.observeRcsMessages()

    /** Resolves a number to a server conversation, creating it if needed. */
    suspend fun rcsConversationFor(number: String): String =
        rcsTransport.conversationForNumber(number)

    private suspend fun loadRcsConversations(): List<Conversation> {
        return rcsTransport.currentRcsConversations()
            .map { mapRcsConversation(it) }
            .sortedByDescending { it.date }
    }

    /**
     * RCS participant cleanup, delegated to the pure, unit-tested
     * [cleanRcsParticipantList]: the server's participant list can include
     * self — sometimes twice, and not always flagged isMe ("Grace, Garrett,
     * Garrett", and the GIRTH ghost: an unflagged self entry with a real
     * number and an "Unknown"/blank name). Self is matched via the isMe flag
     * plus every known self number (ownAddress() plus every isMe number seen
     * across conversations — server-grounded, works when the Telephony-based
     * ownAddress() can't resolve in paired mode). His name must never appear
     * in a header unless the chat is to himself specifically, and the word
     * "Unknown" must never render.
     */
    private suspend fun selfNumberSet(): Set<String> {
        val out = mutableSetOf<String>()
        runCatching { ownAddress() }.getOrNull()?.let {
            if (it.isNotBlank()) out.add(it)
        }
        // Server-grounded fallback: any isMe-flagged participant number,
        // across every conversation, is self by definition.
        rcsTransport.currentRcsConversations().forEach { c ->
            c.participants.forEach { p ->
                if (p.isMe && p.number.isNotBlank()) out.add(p.number)
            }
        }
        return out
    }

    private suspend fun cleanRcsParticipants(
        parts: List<com.garrietta.relay.rcs.proto.RcsParticipant>,
    ): List<com.garrietta.relay.rcs.proto.RcsParticipant> =
        com.garrietta.relay.rcs.proto.cleanRcsParticipantList(parts, selfNumberSet())

    private suspend fun mapRcsConversation(
        c: com.garrietta.relay.rcs.proto.RcsConversation,
    ): Conversation {
        val others = cleanRcsParticipants(c.participants)
        // Never trust a server conversation name containing "unknown" — the
        // server auto-names groups from raw participants ("Alec, Trenton,
        // Unknown"); rebuild from the cleaned list instead. A genuine custom
        // name (no "unknown" in it) is kept.
        val name = c.name
            .takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
            ?: others.joinToString(", ") {
                it.displayNameOrNumber()
            }.ifBlank { c.id }
        // Self-chat (no others): address on a self number so the photo
        // lookup and the avatar shape key match the chat header's. Falling
        // back to the display name here showed an initial in the inbox
        // while the chat showed the photo (and a different shape).
        val selfNorm = runCatching { ownAddress() }.getOrNull()
        val address = others.firstOrNull()?.number?.ifBlank { null }
            ?: selfNorm?.ifBlank { null }
            ?: name
        return Conversation(
            threadId = ("rcs:" + c.id).hashCode().toLong(),
            address = address,
            displayName = name,
            snippet = c.snippet,
            date = c.timestampMs,
            unread = c.unread,
            messageCount = 0,
            // The REAL per-conversation transport from the server
            // (Conversation.type field 22: 1=SMS, 2=RCS) — never hardcoded.
            transportLabel = c.transportLabel(),
            photoUri = contactLookup.photoUri(address),
            threadIds = emptyList(),
            rcsConversationId = c.id,
            // Group members (self already excluded) for the inbox avatar
            // cluster and its per-group pattern.
            participants = others.map {
                Participant(
                    address = it.number,
                    displayName = it.fullName.ifBlank { it.firstName }.ifBlank { it.number },
                    photoUri = contactLookup.photoUri(it.number),
                )
            },
        )
    }

    /** Latest server messages per conversation (for read marking). */
    private val rcsServerMessages = mutableMapOf<String, List<com.garrietta.relay.rcs.proto.RcsMessage>>()

    /**
     * Server message history for an RCS conversation, NEWEST FIRST — the
     * thread list is reverseLayout, so index 0 (newest) renders at the
     * bottom and the oldest sits at the top, matching Google Messages.
     * (Ascending here rendered every chat upside down; diagnosed 2026-10-18.)
     */
    suspend fun rcsMessages(conversationId: String, count: Long = 50L): List<Message> = withContext(Dispatchers.IO) {
        val conv = rcsTransport.currentRcsConversations().firstOrNull { it.id == conversationId }
        val participants = conv?.participants ?: emptyList()
        val threadKey = ("rcs:" + conversationId).hashCode().toLong()
        val server = rcsTransport.rcsMessages(conversationId, participants, count)
        synchronized(rcsServerMessages) { rcsServerMessages[conversationId] = server }
        server.map { m ->
            val senderNumber = participants
                .firstOrNull { it.participantId == m.participantId }?.number.orEmpty()
            Message(
                id = m.id.hashCode().toLong(),
                threadId = threadKey,
                address = senderNumber,
                body = m.body,
                date = m.timestampMs,
                isOutgoing = m.fromMe,
                isMms = false,
                read = true,
                tmpId = m.tmpId.ifBlank { null },
                media = m.media,
            )
        }.sortedByDescending { it.date }
            // Write-through to the instant-open cache.
            .also { putCachedMessages(conversationId, it) }
    }

    /** Downloads + decrypts an RCS media attachment (disk-cached in transport). */
    suspend fun mediaBytes(att: com.garrietta.relay.rcs.proto.RcsMediaAttachment): ByteArray =
        rcsTransport.downloadMedia(att)

    /** The server's transport type for one conversation (1=SMS, 2=RCS, 0=unknown). */
    fun rcsConversationType(conversationId: String): Int =
        rcsTransport.currentRcsConversations().firstOrNull { it.id == conversationId }?.type ?: 0

    /** Server participants for an RCS conversation, deduped by normalized address (self kept). */
    suspend fun getRcsParticipants(conversationId: String): List<Participant> =
        withContext(Dispatchers.IO) {
            val conv = rcsTransport.currentRcsConversations()
                .firstOrNull { it.id == conversationId }
                ?: return@withContext emptyList()
            val seen = mutableSetOf<String>()
            conv.participants.filter { p ->
                val norm = normalizedAddress(p.number)
                if (norm.isEmpty()) true else seen.add(norm)
            }.map { p ->
                Participant(
                    address = p.number,
                    displayName = p.fullName.ifBlank { p.firstName }.ifBlank { p.number },
                    photoUri = contactLookup.photoUri(p.number),
                    isSelf = p.isMe,
                )
            }
        }

    /**
     * Header-ready others for an RCS conversation: self removed (isMe flag
     * plus every known self number — the server doesn't always flag every
     * self entry, e.g. the GIRTH ghost) and deduped by normalized address.
     * The word "Unknown" can never survive into the name list.
     */
    suspend fun rcsOthers(conversationId: String): List<Participant> =
        withContext(Dispatchers.IO) {
            val conv = rcsTransport.currentRcsConversations()
                .firstOrNull { it.id == conversationId }
                ?: return@withContext emptyList()
            val selfNumbers = selfNumberSet()
            cleanRcsParticipantList(conv.participants, selfNumbers)
                .map { p ->
                    Participant(
                        address = p.number,
                        displayName = p.displayNameOrNumber()
                            .ifBlank { p.number },
                        photoUri = contactLookup.photoUri(p.number),
                        isSelf = p.isMe,
                    )
                }
        }

    /** Outcome of an RCS send: the tmpId for echo correlation, or the verbatim reason. */
    sealed interface RcsSendOutcome {
        data class Sent(val tmpId: String) : RcsSendOutcome
        data class Failed(val reason: String) : RcsSendOutcome
    }

    suspend fun sendRcs(conversationId: String, body: String, tmpId: String): RcsSendOutcome {
        return try {
            rcsTransport.sendRcsText(conversationId, body, tmpId)
            // Refresh the conversation list so the snippet/date update, then
            // the inbox. The echo arrives on the long-poll and reconciles the
            // optimistic bubble via tmpId.
            rcsTransport.refreshConversations()
            refresh()
            RcsSendOutcome.Sent(tmpId)
        } catch (e: Exception) {
            RcsSendOutcome.Failed(e.message ?: "RCS send failed")
        }
    }

    /** Marks the latest incoming RCS message read (mirrors markThreadRead). */
    suspend fun markRcsRead(conversationId: String) = withContext(Dispatchers.IO) {
        val latest = synchronized(rcsServerMessages) {
            rcsServerMessages[conversationId]
                ?.filter { !it.fromMe }
                ?.maxByOrNull { it.timestampMs }
        } ?: return@withContext
        rcsTransport.markRcsRead(conversationId, latest.id)
    }

    /**
     * Merges 1:1 threads into conversations via the pure [mergeCandidates]
     * merger (unit-tested). Contact IDs/names/photos are resolved here, up
     * front, so the merger itself stays JVM-testable.
     *
     * Contact-ID-first bucketing handles the same person on different numbers;
     * normalized-address bucketing handles the provider splitting SMS/MMS
     * legs by address format. Group threads and short codes never merge, and
     * two different contact IDs never merge.
     */
    private suspend fun mergeThreads(raw: List<RawThread>): List<Conversation> {
        val candidates = raw.map { t ->
            MergeCandidate(
                threadId = t.threadId,
                address = t.address,
                recipients = t.recipients,
                date = t.date,
                snippet = t.snippet,
                unread = t.unread,
                messageCount = t.messageCount,
                transportLabel = t.transportLabel,
                contactId = contactLookup.contactId(t.address),
                displayName = contactLookup.displayName(t.address),
                photoUri = contactLookup.photoUri(t.address),
            )
        }
        val merged = mergeCandidates(candidates)
        for (c in merged) {
            if (c.threadIds.size > 1) {
                Log.d(TAG, "mergeThreads: merged ${c.threadIds.size} threads for ${c.address}: ${c.threadIds}")
            }
        }
        if (merged.size < candidates.size) {
            Log.d(TAG, "mergeThreads: ${candidates.size - merged.size} thread(s) merged away or hidden as provider noise")
        }
        // Group rows get their members (self excluded) for the inbox avatar
        // cluster. Groups never merge, so each group row maps to exactly
        // one candidate's recipient set.
        val selfNorm = runCatching { ownAddress() }.getOrNull()
        val byThread = candidates.associateBy { it.threadId }
        return merged.map { conv ->
            val recipients = conv.threadIds
                .mapNotNull { byThread[it]?.recipients }
                .flatten().toSet()
            if (recipients.size > 1) {
                val others = recipients.filterNot { isSelfAddress(it, selfNorm) }
                conv.copy(
                    participants = others.map { addr ->
                        Participant(
                            address = addr,
                            displayName = contactLookup.displayName(addr),
                            photoUri = contactLookup.photoUri(addr),
                        )
                    },
                )
            } else {
                conv
            }
        }
    }

    /**
     * Self-address match tolerant of country-code formatting differences
     * ("+17653419588" vs "7653419588"): digits must match, comparing the
     * last 10 when lengths differ.
     */
    private fun isSelfAddress(addr: String, selfNorm: String?): Boolean {
        if (selfNorm.isNullOrEmpty()) return false
        val a = normalizeAddress(addr)
        if (a == selfNorm) return true
        return a.length >= 10 && selfNorm.length >= 10 &&
            a.takeLast(10) == selfNorm.takeLast(10)
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
                    // Text-less MMS (photo/video) is still a message: show it
                    // as a labeled bubble instead of dropping it, so the
                    // thread always shows the same set the inbox snippet and
                    // transport chip were built from.
                    val body = mmsText(mmsId).ifBlank { mmsAttachmentLabel(mmsId) }
                    if (body.isNullOrBlank()) continue
                    out.add(
                        Message(
                            id = -mmsId, // negative to avoid colliding with SMS ids
                            threadId = if (threadI >= 0) c.getLong(threadI) else primaryThreadId,
                            address = mmsAddress(mmsId).orEmpty(),
                            body = body,
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
        // NEWEST FIRST: the thread list is reverseLayout (index 0 renders at
        // the bottom), so descending puts the oldest at the top.
        out.sortedByDescending { it.date }
            // Write-through to the instant-open cache.
            .also {
                if (threadIds.isNotEmpty()) {
                    putCachedMessages(
                        CategoryStore.conversationKey(null, threadIds.first()), it,
                    )
                }
            }
    }

    /** Single-thread convenience; delegates to the merged loader. */
    suspend fun loadMessages(threadId: Long): List<Message> = loadMessages(listOf(threadId))

    /**
     * Fallback loader: re-resolves live provider thread IDs for [address]
     * and loads those. Used when [loadMessages] by thread ID comes back
     * empty — the thread_id key can go stale when the provider splits or
     * re-keys threads, but the address still resolves. The thread screen
     * must show the same unified message set the inbox snippet came from.
     */
    suspend fun loadMessagesByAddress(address: String): List<Message> = withContext(Dispatchers.IO) {
        // mergeKey() is null for short codes / non-numbers — fall back to
        // raw digits so those threads resolve too.
        val want = mergeKey(address) ?: address.filter { it.isDigit() }.ifEmpty { address }
        if (want.isEmpty()) return@withContext emptyList()
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id"), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val tid = c.getLong(0)
                    val a = threadAddress(tid) ?: continue
                    val key = mergeKey(a) ?: a.filter { it.isDigit() }.ifEmpty { a }
                    if (key == want) ids.add(tid)
                }
            }
        } catch (_: Exception) { }
        if (ids.isEmpty()) return@withContext emptyList()
        Log.d(TAG, "loadMessagesByAddress: resolved $ids for $address")
        loadMessages(ids)
    }

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

    /**
     * All distinct participants across [threadIds], with resolved display
     * names and photo URIs. 1:1 conversations return a single entry; group
     * (multi-recipient MMS) threads return everyone. Drives the group chat
     * header cluster and per-sender bubble theming.
     */
    suspend fun getGroupParticipants(threadIds: List<Long>): List<Participant> =
        withContext(Dispatchers.IO) {
            val addresses = threadIds.flatMap { threadRecipients(it) }.distinct()
            addresses.map { address ->
                Participant(
                    address = address,
                    displayName = contactLookup.displayName(address),
                    photoUri = contactLookup.photoUri(address),
                )
            }
        }

    /** Public address normalizer for matching message senders to participants. */
    fun normalizedAddress(address: String): String = normalizeAddress(address)

    private var ownAddressCache: String? = null
    private var ownAddressResolved = false

    /**
     * The device's own address (normalized digits), or null when it cannot be
     * determined. Two strategies, first hit wins (the winner is logged):
     *  1. FROM of a sent MMS (msg_box=2) — always self, no permission needed.
     *  2. Thread-presence: the address appearing in the most threads. His
     *     own number shows up in nearly every thread's recipient set; any
     *     other number shows up in few. (Strategy 1 fails when he hasn't sent
     *     an MMS recently — that left "Grace, Garrett" misdetected.)
     * Used to exclude self from participant counts so him+1 threads are not
     * misdetected as groups.
     */
    suspend fun ownAddress(): String? = withContext(Dispatchers.IO) {
        if (ownAddressResolved) return@withContext ownAddressCache
        ownAddressResolved = true
        val s1 = selfAddressFromSentMms()
        if (s1 != null) {
            Log.d(TAG, "ownAddress: strategy=sent-mms-from value=$s1")
            ownAddressCache = s1
            return@withContext s1
        }
        val s2 = selfAddressByThreadPresence()
        Log.d(TAG, "ownAddress: strategy=thread-presence value=$s2")
        ownAddressCache = s2
        s2
    }

    private fun selfAddressByThreadPresence(): String? {
        return try {
            val counts = mutableMapOf<String, Int>()
            var threads = 0
            context.contentResolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id"), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    threads++
                    for (a in threadRecipients(c.getLong(0))) {
                        counts[a] = (counts[a] ?: 0) + 1
                    }
                }
            }
            if (threads < 3) return null
            counts.maxByOrNull { it.value }
                ?.takeIf { it.value > threads / 2 }
                ?.key
        } catch (_: Exception) {
            null
        }
    }

    private fun selfAddressFromSentMms(): String? {
        try {
            var mmsId = -1L
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"), "msg_box=2", null, "date DESC LIMIT 1",
            )?.use { c -> if (c.moveToFirst()) mmsId = c.getLong(0) }
            if (mmsId < 0) return null
            // 137 (0x89) = FROM — on a sent message, FROM is always self.
            context.contentResolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address"), "type=137", null, null,
            )?.use { c ->
                val i = c.getColumnIndex("address")
                if (i >= 0 && c.moveToFirst()) {
                    val a = c.getString(i)
                    if (!a.isNullOrBlank() && a != "insert-address-token") {
                        return normalizeAddress(a)
                    }
                }
            }
        } catch (_: Exception) { }
        return null
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
     * text part wins. When the latest MMS carries no text (photo/video), an
     * attachment label is returned instead of "" — a thread with a photo is
     * never "No messages".
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
        var mmsId = -1L
        try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    mmsId = c.getLong(0)
                    val text = mmsText(mmsId)
                    val date = c.getLong(1) * 1000L
                    if (text.isNotBlank() && date >= bestDate) best = text
                }
            }
        } catch (_: Exception) { }
        if (!best.isNullOrBlank()) return best!!
        // No text anywhere: label the latest MMS's attachment (if any) so a
        // photo/video thread doesn't read as empty.
        return mmsAttachmentLabel(mmsId).orEmpty()
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

    /**
     * Transport of the thread's LATEST message (SMS vs MMS), by comparing
     * the newest SMS date against the newest MMS date. The inbox chip and
     * the snippet always describe the same message.
     */
    private fun latestTransportLabel(threadId: Long): String {
        var smsDate = -1L
        var mmsDate = -1L
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("date"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) smsDate = c.getLong(0)
            }
        } catch (_: Exception) { }
        try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("date"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                // MMS dates are in seconds; SMS dates are in millis.
                if (c.moveToFirst()) mmsDate = c.getLong(0) * 1000L
            }
        } catch (_: Exception) { }
        return if (mmsDate >= 0 && mmsDate >= smsDate) "MMS" else "SMS"
    }

    /**
     * Human label for an MMS that carries no text (photo/video/audio) — so
     * the inbox never claims "No messages" for a thread that has one, and
     * the thread screen can show the message as a labeled bubble. Null when
     * the MMS has no non-text, non-SMIL parts at all.
     */
    private fun mmsAttachmentLabel(mmsId: Long): String? {
        if (mmsId < 0) return null
        return try {
            var label: String? = null
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("ct"), "mid=?", arrayOf(mmsId.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val ct = c.getString(0).orEmpty()
                    val candidate = when {
                        ct.startsWith("image/") -> "\uD83D\uDCF7 Photo"
                        ct.startsWith("video/") -> "\uD83C\uDFA5 Video"
                        ct.startsWith("audio/") -> "\uD83C\uDFB5 Audio"
                        ct.startsWith("text/") || ct == "application/smil" || ct.isBlank() -> null
                        else -> "Attachment"
                    }
                    if (candidate != null) {
                        label = candidate
                        break
                    }
                }
            }
            label
        } catch (_: Exception) {
            null
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

    /**
     * Every address format worth trying against PhoneLookup for one number.
     * The provider stores the same number in different formats across
     * SMS/MMS legs ("+15551234567" vs "5551234567"), and a lookup that fails
     * on one leg's format is what left duplicate conversation rows unmerged.
     */
    private fun addressVariants(address: String): List<String> {
        val digits = address.filter { it.isDigit() }
        return buildList {
            add(address)
            if (digits.isNotEmpty() && digits != address) {
                add(digits)
                if (digits.length == 10) {
                    add("+1$digits")
                    add("1$digits")
                }
                if (digits.length == 11 && digits.startsWith("1")) {
                    add(digits.substring(1))
                    add("+$digits")
                }
            }
        }.distinct()
    }

    /** First non-null value of [column] for [address], or null. */
    private fun phoneLookupFirst(address: String, column: String): String? {
        return try {
            var result: String? = null
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address),
                ),
                arrayOf(column),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) result = c.getString(0)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun displayName(address: String): String {
        cache[address]?.let { return it }
        var name: String? = null
        for (variant in addressVariants(address)) {
            name = phoneLookupFirst(variant, ContactsContract.PhoneLookup.DISPLAY_NAME)
            if (name != null) break
        }
        val result = name ?: address
        cache[address] = result
        return result
    }

    /**
     * Raw contact ID for [address], or null when not in contacts. Tries every
     * address variant, so one leg's odd format can't strand a duplicate row.
     * Used as a merge key so two different numbers for the SAME person land
     * in one conversation.
     */
    @Synchronized
    fun contactId(address: String): Long? {
        for (variant in addressVariants(address)) {
            phoneLookupFirst(variant, ContactsContract.PhoneLookup._ID)
                ?.toLongOrNull()?.let { return it }
        }
        return null
    }

    /** Profile photo URI for [address], or null when the contact has none. */
    @Synchronized
    fun photoUri(address: String): String? {
        if (photoCache.containsKey(address)) return photoCache[address]
        var uri: String? = null
        for (variant in addressVariants(address)) {
            uri = phoneLookupFirst(variant, ContactsContract.PhoneLookup.PHOTO_URI)
            if (uri != null) break
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
