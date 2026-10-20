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
import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.model.isGroupEventNotice
import com.garrietta.relay.data.model.isUnknownSenderGhostName
import com.garrietta.relay.rcs.proto.isUnknownSenderGhost
import com.garrietta.relay.data.model.rcsStatusToDelivery
import com.garrietta.relay.data.transport.IncomingMessage
import com.garrietta.relay.data.transport.RcsTransport
import com.garrietta.relay.data.transport.SmsTransport
import com.garrietta.relay.data.transport.Transport
import com.garrietta.relay.rcs.proto.deriveRcsIsGroup
import com.garrietta.relay.rcs.proto.deriveRcsOthers
import com.garrietta.relay.rcs.proto.displayNameOrNumber
import com.garrietta.relay.rcs.proto.resolveMessageDirection
import com.garrietta.relay.ui.chat.parseReaction
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
     * Optimistic-read latches (Garrett 2026-10-20, see [ReadLatch]):
     * a tapped-open chat renders read on the first frame back to the
     * inbox instead of waiting for the server mark-read to propagate
     * through a refresh. The server path itself (ChatViewModel's
     * markRcsRead / markThreadRead) is unchanged — this only governs
     * what the local list shows in the gap, and a refresh that would
     * resurrect the unread flag from stale metadata is held by the
     * latch until the server catches up or a genuinely newer message
     * arrives.
     */
    private val readLatch = ReadLatch()

    /**
     * The tap on an unread chat marks it read (Garrett 2026-10-20):
     * called from the inbox's single open-chat funnel at the moment of
     * the tap, BEFORE navigation. Latches the conversation at its
     * current newest-message timestamp and flips the row read in the
     * published list immediately, so the Unread category releases the
     * row and every unread surface updates with the same emission.
     * Synchronous on purpose — the flip must land before the chat
     * opens, not after an async round-trip.
     */
    fun markConversationOpened(conversation: Conversation) {
        val key = CategoryStore.conversationKey(
            conversation.rcsConversationId, conversation.threadId,
        )
        readLatch.latch(key, conversation.date)
        val current = _conversations.value
        if (current.any { it.unread && conversationKeyOf(it) == key }) {
            _conversations.value = current.map { row ->
                if (row.unread && conversationKeyOf(row) == key) {
                    row.copy(unread = false)
                } else {
                    row
                }
            }
        }
    }

    private fun conversationKeyOf(row: Conversation): String =
        CategoryStore.conversationKey(row.rcsConversationId, row.threadId)

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
        // Write-through to the persistent LRU (text only, never image
        // bytes): the 50 most recently active chats survive restarts.
        scope.launch { historyCache.write(conversationKey, messages) }
    }

    /**
     * Persistent LRU text-history cache (Garrett 2026-10-19): the 50 most
     * recently active conversations keep their text history on disk.
     * Lazy so the files dir exists; all IO happens off the caller.
     */
    private val historyCache by lazy { ChatHistoryCache(context.filesDir) }

    /**
     * Disk-LRU read for instant paint on cold start. Call off the main
     * thread; on a hit the memory cache is primed too. Returns null on a
     * miss or corrupt file.
     */
    suspend fun cachedHistoryFromDisk(
        conversationKey: String,
    ): List<com.garrietta.relay.data.model.Message>? = withContext(Dispatchers.IO) {
        val disk = historyCache.read(conversationKey) ?: return@withContext null
        synchronized(this@UnifiedInboxRepository) { messageCache[conversationKey] = disk }
        disk
    }

    /**
     * Recency bump: a new incoming/outgoing message for [conversationKey]
     * moves it to the front of the 50-chat active set (its file stays
     * readable even after eviction — just no longer kept fresh).
     */
    fun touchHistory(conversationKey: String) {
        scope.launch { historyCache.touch(conversationKey) }
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
                    // Optimistic-read latches ride every emission: a
                    // refresh carrying pre-mark-read server metadata
                    // must not resurrect unread on a chat Garrett has
                    // already opened (see ReadLatch).
                    _conversations.value = readLatch.applyTo(loaded)
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
            // Own number, for the snippet prefix's group test (recipients
            // minus self) — resolved once for the whole list.
            val selfNorm = runCatching { ownAddress() }.getOrNull()
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
                    val rawSnippet = providerSnippet.ifBlank { fallbackSnippet(threadId) }
                    val recipients = threadRecipients(threadId)
                    // GM snippet prefixes (Garrett 2026-10-20), same rule
                    // as the RCS path: "You: " for his latest message,
                    // "{FirstName}: " for a group member's — from the
                    // thread's own newest message (see latestSnippetPrefix).
                    // Group-ness mirrors mergeThreads: recipients minus
                    // self, more than one other.
                    val isGroupThread =
                        recipients.count { !isSelfAddress(it, selfNorm) } > 1
                    val snippet = if (rawSnippet.isBlank()) rawSnippet
                        else latestSnippetPrefix(threadId, isGroupThread) + rawSnippet
                    raw.add(
                        RawThread(
                            threadId = threadId,
                            address = address,
                            recipients = recipients,
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
        // Every provider thread is its own row beyond the 1:1 leg
        // merger inside mergeThreads — no cross-conversation
        // participant-set unification (removed 2026-10-20).
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
        // Every server conversation is its own inbox row — mapping is
        // 1:1 per server id and NOTHING unifies rows across
        // conversations. (The participant-set merge that once combined
        // rows here was removed 2026-10-20: the server can hold two
        // conversation objects with look-alike participant sets that
        // are legitimately SEPARATE chats — his Dad 1:1 and the
        // Dad+Grace group — and combining them corrupted both.)
        val rows = rcsTransport.currentRcsConversations()
            .map { mapRcsConversation(it) }
        return rows.sortedByDescending { it.date }
    }

    /**
     * RCS participant cleanup, delegated to the pure, unit-tested
     * [deriveRcsOthers]: the server's participant list can include
     * self — sometimes twice, and not always flagged isMe ("Grace, Garrett,
     * Garrett", and the GIRTH ghost: an unflagged self entry with a real
     * number and an "Unknown"/blank name). Self is matched via the isMe flag
     * plus every known self number (ownAddress() plus every isMe number seen
     * across conversations — server-grounded, works when the Telephony-based
     * ownAddress() can't resolve in paired mode). His name must never appear
     * in a header unless the chat is to himself specifically, and the word
     * "Unknown" must never render. And when the payload ships field 21
     * (otherParticipantIds — the server's own membership answer), it is
     * the authority over field 20's attribute list: a stale entry the
     * server no longer counts as a member (Grace in the Dad 1:1) drops.
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
        otherIds: List<String>,
    ): List<com.garrietta.relay.rcs.proto.RcsParticipant> =
        deriveRcsOthers(parts, otherIds, selfNumberSet())

    private suspend fun mapRcsConversation(
        c: com.garrietta.relay.rcs.proto.RcsConversation,
    ): Conversation {
        // UNKNOWN_SENDER! ghosts vanish here — filtered at the RCS
        // level, before any name is composed or Participant built.
        // Display-layer only; the server data is untouched.
        val others = cleanRcsParticipants(c.participants, c.otherParticipantIds)
            .filterNot { it.isUnknownSenderGhost() }
        // Group classification (Garrett 2026-10-20): derived in THIS
        // pass from THIS payload, paired with the others above — when
        // field 21 shipped, membership decides; the isGroup flag only
        // stands when there is no membership list to consult.
        val group = deriveRcsIsGroup(c.isGroup, c.otherParticipantIds, others.size)
        // The full stored participant list (self included) for chat-side
        // sender resolution and the details page — derived in THIS pass
        // from THIS payload, so no surface ever re-derives membership
        // from a second transport lookup that can miss or disagree with
        // the row (Garrett 2026-10-20, single source of truth). Cleaning
        // mirrors what the chat getters used to apply on their own:
        // dedupe by normalized address, ghosts out, isSelf flags kept.
        val allParticipants = run {
            val seen = mutableSetOf<String>()
            c.participants.filter { p ->
                val norm = normalizedAddress(p.number)
                if (norm.isEmpty()) true else seen.add(norm)
            }.filterNot { it.isUnknownSenderGhost() }.map { p ->
                Participant(
                    address = p.number,
                    displayName = p.fullName.ifBlank { p.firstName }.ifBlank { p.number },
                    photoUri = contactLookup.photoUri(p.number),
                    isSelf = p.isMe,
                )
            }
        }
        // Never trust a server conversation name containing "unknown" — the
        // server auto-names groups from raw participants ("Alec, Trenton,
        // Unknown"); rebuild from the cleaned list instead. A genuine custom
        // name (no "unknown" in it) is kept. The UNKNOWN_SENDER! ghost is
        // hidden from the joined name too (display layer only — Garrett
        // 2026-10-19; the #118 header filter missed this join path).
        val name = c.name
            .takeIf { it.isNotBlank() && !it.contains("unknown", ignoreCase = true) }
            ?: others.filterNot { it.displayNameOrNumber().isUnknownSenderGhostName() }
                .joinToString(", ") {
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
        // Reaction traffic is not message traffic (Garrett 2026-10-19): a
        // reaction that arrives as formatted text must not render as the
        // inbox snippet — it renders as an inline badge in the thread.
        val baseSnippet = parseReaction(c.snippet)?.let { (emoji, _) -> "$emoji reaction" }
            ?: c.snippet
        // GM snippet prefixes (Garrett 2026-10-20): the server's own
        // LatestMessage fields say who sent the snippet's message —
        // fromMe (field 2) and displayName (field 4). "You: " when the
        // latest is his, "{FirstName}: " when it's a group member's, bare
        // text for a 1:1 — exactly Google Messages' rendering. Applied to
        // the final snippet text, reaction form included. Group EVENT
        // sentences are not message traffic (Garrett 2026-10-20): a
        // conversation whose latest entry is "… created this group chat
        // …" must not wear that event as its snippet, prefix or not.
        // The group flag read here is the single derived one above
        // (deriveRcsIsGroup) — no second local computation.
        val snippet = when {
            baseSnippet.isBlank() -> baseSnippet
            group && isGroupEventNotice(c.snippet) -> ""
            else -> snippetPrefix(c.latestFromMe, group, c.latestSenderName) + baseSnippet
        }
        return Conversation(
            threadId = ("rcs:" + c.id).hashCode().toLong(),
            address = address,
            displayName = name,
            snippet = snippet,
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
            // The full stored list (self included) — chat/details read
            // membership from HERE, never from a fresh transport lookup.
            allParticipants = allParticipants,
            isGroup = group,
        )
    }

    /** Latest server messages per conversation (for read marking). */
    private val rcsServerMessages = mutableMapOf<String, List<com.garrietta.relay.rcs.proto.RcsMessage>>()

    /**
     * Participant-integrity anomalies (Garrett 2026-10-20): server
     * conversation id → display title for conversations whose OWN
     * participant list claims a group while their loaded messages were
     * all sent by a single other participant (see [participantAnomaly]).
     * Evidence-based: recorded when a conversation's messages load,
     * cleared when later loads show a second other sender speaking.
     */
    private val participantAnomalyLock = Any()

    @Volatile
    private var participantAnomalies: Map<String, String> = emptyMap()

    /** participantAnomalies=N for Settings → Diagnostics. */
    fun participantAnomalyCount(): Int = participantAnomalies.size

    /** Titles of the anomalous conversations, for Diagnostics. */
    fun participantAnomalyTitles(): List<String> =
        participantAnomalies.values.sorted()

    /**
     * True direction-metadata contradictions counted in
     * [rcsMessagesSingle] (Garrett 2026-10-20): messages whose sender
     * entry — resolved against the conversation's OWN participant
     * table — is the isMe-flagged self entry, yet which carry an
     * explicit INCOMING_* status marker. Two independent wire signals
     * disagree; the entry determination wins and the message renders
     * outgoing. (The earlier guard also counted — and force-flipped —
     * any sender whose NUMBER matched Garrett's; that person-level
     * match is gone: it flattened the self conversation's two distinct
     * self entries, whose messages are legitimately both directions.)
     */
    private val selfSenderLock = Any()
    private var selfSenderContradictions: Int = 0

    /** selfSenderContradictions=N for Settings → Diagnostics. */
    fun selfSenderContradictionCount(): Int =
        synchronized(selfSenderLock) { selfSenderContradictions }

    /**
     * History for [conversationId] — ONLY that server conversation's
     * own messages. Nothing is ever unioned across conversations (the
     * cross-conversation merge was removed 2026-10-20): a message
     * belongs to exactly the conversation the server delivered it in.
     */
    suspend fun rcsMessages(conversationId: String, count: Long = 50L): List<Message> =
        rcsMessagesSingle(conversationId, count)

    /**
     * Single-server-conversation history load (see [rcsMessages]).
     * NEWEST FIRST — the thread list is reverseLayout, so index 0
     * (newest) renders at the bottom and the oldest sits at the top,
     * matching Google Messages. (Ascending here rendered every chat
     * upside down; diagnosed 2026-10-18.)
     */
    private suspend fun rcsMessagesSingle(conversationId: String, count: Long = 50L): List<Message> = withContext(Dispatchers.IO) {
        val conv = rcsTransport.currentRcsConversations().firstOrNull { it.id == conversationId }
        val participants = conv?.participants ?: emptyList()
        val threadKey = ("rcs:" + conversationId).hashCode().toLong()
        val server = rcsTransport.rcsMessages(conversationId, participants, count)
        synchronized(rcsServerMessages) { rcsServerMessages[conversationId] = server }
        // Participant-integrity evidence (Garrett 2026-10-20): does this
        // conversation's OWN participant list agree with its messages?
        // A list claiming a group whose loaded messages all come from a
        // single other sender is contradictory data ("Dad, Grace
        // Ambrose" with only Dad ever speaking) — recorded for
        // Diagnostics, never silently trusted or silently "fixed".
        run {
            val otherNumbers = cleanRcsParticipants(
                participants, conv?.otherParticipantIds.orEmpty(),
            )
                .filterNot { it.isUnknownSenderGhost() }
                .map { normalizedAddress(it.number) }
                .filter { it.isNotEmpty() }.toSet()
            val byPid = participants.associateBy { it.participantId }
            val incoming = server.filter { !it.fromMe }
            val distinctSenders = incoming.mapNotNull { m ->
                byPid[m.participantId]?.number
                    ?.let { normalizedAddress(it) }
                    ?.takeIf { it in otherNumbers }
            }.distinct().size
            val title = _conversations.value.firstOrNull { row ->
                row.rcsConversationId == conversationId
            }?.displayName?.ifBlank { null }
                ?: conv?.name?.ifBlank { null }
                ?: conversationId
            synchronized(participantAnomalyLock) {
                participantAnomalies = when {
                    participantAnomaly(
                        otherNumbers.size, distinctSenders, incoming.size,
                    ) -> participantAnomalies + (conversationId to title)
                    // Conclusive counter-evidence — a second other
                    // sender has actually spoken: the claim checks out.
                    distinctSenders >= 2 -> participantAnomalies - conversationId
                    // Too little evidence to judge: keep prior state.
                    else -> participantAnomalies
                }
            }
        }
        // Direction is PER-MESSAGE and ENTRY-LEVEL (Garrett 2026-10-20):
        // the sender entry — resolved by the message's participantID
        // within THIS conversation's own participant table — decides.
        // Outgoing iff that entry is this table's isMe-flagged entry;
        // incoming otherwise. NO person-level number matching: his self
        // conversation holds two entries for his own number (the
        // flagged sender-side entry and the unflagged recipient-side
        // entry), and the previous number-match guard flattened them,
        // rendering every self-chat message outgoing. The "Garrett
        // Marietta" incoming bubbles that guard fought came from
        // resolving senders against ANOTHER conversation's table (the
        // cross-conversation merge) — the teardown removed that, so no
        // force-outgoing override remains. When the participantID does
        // not resolve, [RcsMessage.fromMe] stands: it already carries
        // decode's MessageStatus fallback (resolveFromMe). A flagged
        // entry carrying an explicit INCOMING_* status is a true
        // metadata contradiction — the entry wins, and it is counted.
        val byPid = participants.associateBy { it.participantId }
        server.map { m ->
            val sender = byPid[m.participantId]
            val senderNumber = sender?.number.orEmpty()
            val direction = resolveMessageDirection(sender, m.fromMe, m.status)
            val outgoing = direction.outgoing
            if (direction.contradiction) {
                synchronized(selfSenderLock) { selfSenderContradictions++ }
            }
            Message(
                id = m.id.hashCode().toLong(),
                threadId = threadKey,
                address = senderNumber,
                body = m.body,
                date = m.timestampMs,
                isOutgoing = outgoing,
                isMms = false,
                read = true,
                tmpId = m.tmpId.ifBlank { null },
                media = m.media,
                location = m.location,
                deliveryStatus = rcsStatusToDelivery(m.status, outgoing),
                serverId = m.id.ifBlank { null },
                reactions = m.wireReactions.flatMap { wr ->
                    wr.participantIds.ifEmpty { listOf("") }.map { pid ->
                        val reactor = participants.firstOrNull { it.participantId == pid }
                        com.garrietta.relay.data.model.MessageReaction(
                            emoji = wr.emoji,
                            reactorName = when {
                                reactor?.isMe == true -> "You"
                                reactor != null -> reactor.displayNameOrNumber()
                                pid == m.participantId && m.fromMe -> "You"
                                else -> "Someone"
                            },
                            fromMe = reactor?.isMe == true,
                            timestampMs = m.timestampMs,
                        )
                    }
                },
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
        rcsTransport.currentRcsConversations()
            .firstOrNull { it.id == conversationId }?.type ?: 0

    /**
     * The stored inbox ROW for a server conversation — the single
     * source of truth for its membership (Garrett 2026-10-20). The row
     * in [_conversations] wins: it is the exact object the inbox and
     * search rendered, so a chat opened from it can never disagree
     * with what the user tapped. When the row isn't in the flow yet
     * (a chat opened from a notification before the first refresh
     * lands), the row is mapped on demand from the transport's current
     * payload through the SAME [mapRcsConversation] derivation — same
     * rule, same result a refresh would store; never a second,
     * differently-cleaned derivation. (The previous getters re-derived
     * from a live `currentRcsConversations()` lookup with their own
     * cleaning: when that snapshot's entry carried an empty field 20 —
     * or the lookup missed — the chat header rendered nobody and the
     * details page reported "0 members" for a chat whose row showed
     * its people. That whole class of divergence is now impossible:
     * there is one derivation, stored on the row, read everywhere.)
     */
    private suspend fun storedConversationRow(conversationId: String): Conversation? {
        _conversations.value.firstOrNull { it.rcsConversationId == conversationId }
            ?.let { return it }
        val conv = rcsTransport.currentRcsConversations()
            .firstOrNull { it.id == conversationId } ?: return null
        return mapRcsConversation(conv)
    }

    /**
     * Server participants for an RCS conversation (self kept, isSelf
     * flagged): the row's STORED full list — see [storedConversationRow].
     */
    suspend fun getRcsParticipants(conversationId: String): List<Participant> =
        storedConversationRow(conversationId)?.let { row ->
            row.allParticipants.ifEmpty { row.participants }
        } ?: emptyList()

    /**
     * Header-ready others for an RCS conversation: the row's STORED
     * others — self already removed at mapping time (isMe flag plus
     * every known self number — the server doesn't always flag every
     * self entry, e.g. the GIRTH ghost), ghosts out, deduped. The word
     * "Unknown" can never survive into the name list.
     */
    suspend fun rcsOthers(conversationId: String): List<Participant> =
        storedConversationRow(conversationId)?.participants ?: emptyList()

    /**
     * The participant list of the inbox ROW for a conversation — the exact
     * list the inbox rendered (and classified the row by). Chat surfaces
     * reconcile against it so the chat can never present a thinner group
     * than the row the user tapped (Garrett 2026-10-20, "No Dad").
     *
     * Participant integrity (Garrett 2026-10-20): the row is matched by
     * EXACT conversation identity only. RCS chats match on the server
     * conversation id — the row that IS this conversation; Telephony
     * chats match on their own provider thread-id set. The earlier
     * any-of matching also accepted bare threadId equality, and an RCS
     * row's threadId is a 32-bit `String.hashCode()` of its server id:
     * a hash collision could hand a chat a DIFFERENT conversation's
     * fuller participant list — a foreign participant entering a chat
     * whose own payload never listed them.
     */
    fun inboxRowParticipants(
        rcsConversationId: String?,
        threadId: Long,
        threadIds: List<Long>,
    ): List<Participant> {
        val rows = _conversations.value
        if (rcsConversationId != null) {
            return rows.firstOrNull { row ->
                row.rcsConversationId == rcsConversationId
            }?.participants.orEmpty()
        }
        val ids = (threadIds + threadId).toSet()
        return rows.firstOrNull { row ->
            row.threadId in ids || row.threadIds.any { it in ids }
        }?.participants.orEmpty()
    }

    /** Outcome of an RCS send: the tmpId for echo correlation, or the verbatim reason. */
    sealed interface RcsSendOutcome {
        data class Sent(val tmpId: String) : RcsSendOutcome
        data class Failed(val reason: String) : RcsSendOutcome
    }

    suspend fun sendRcs(conversationId: String, body: String, tmpId: String): RcsSendOutcome {
        // Sends go to the opened conversation only — there is no
        // canonical rerouting (cross-conversation routing removed
        // 2026-10-20).
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

    /**
     * Uploads an attachment through the RCS file-transfer path with
     * progress callbacks (Garrett 2026-10-19: the [+] picker finally sends
     * for real). Returns the [UploadedMediaRef] for the send step.
     */
    suspend fun uploadRcsMedia(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        onProgress: (Float) -> Unit,
    ): com.garrietta.relay.rcs.proto.UploadedMediaRef =
        rcsTransport.uploadMedia(bytes, mimeType, fileName, onProgress)

    /** Sends an uploaded media attachment; the echo reconciles via tmpId. */
    suspend fun sendRcsMedia(
        conversationId: String,
        media: com.garrietta.relay.rcs.proto.UploadedMediaRef,
        caption: String,
        tmpId: String,
    ): RcsSendOutcome {
        // Sends go to the opened conversation only, same as sendRcs.
        return try {
            rcsTransport.sendRcsMedia(conversationId, media, caption, tmpId)
            rcsTransport.refreshConversations()
            refresh()
            RcsSendOutcome.Sent(tmpId)
        } catch (e: Exception) {
            RcsSendOutcome.Failed(e.message ?: "RCS send failed")
        }
    }

    /**
     * Sends a shared location (Garrett 2026-10-20, feature queue 6/7):
     * the native locationContent payload with [linkText] riding in the
     * same send as the human fallback. The echo reconciles via tmpId.
     * Sends go to the opened conversation only, same as sendRcs.
     */
    suspend fun sendRcsLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        name: String,
        linkText: String,
        tmpId: String,
    ): RcsSendOutcome {
        return try {
            rcsTransport.sendRcsLocation(
                conversationId, latitude, longitude, name, linkText, tmpId,
            )
            rcsTransport.refreshConversations()
            refresh()
            RcsSendOutcome.Sent(tmpId)
        } catch (e: Exception) {
            RcsSendOutcome.Failed(e.message ?: "RCS location send failed")
        }
    }

    /**
     * Sends a REAL reaction event (Garrett 2026-10-19: SEND_REACTION action).
     * Returns the verbatim reason on failure so the UI surfaces it exactly.
     */
    suspend fun sendRcsReaction(messageId: String, emoji: String): RcsSendOutcome {
        return try {
            rcsTransport.sendRcsReaction(messageId, emoji)
            RcsSendOutcome.Sent(messageId)
        } catch (e: Exception) {
            RcsSendOutcome.Failed(e.message ?: "Reaction failed")
        }
    }

    /** Marks the latest incoming RCS message read (mirrors markThreadRead). */
    suspend fun markRcsRead(conversationId: String) = withContext(Dispatchers.IO) {
        // Only THIS conversation is marked — read state never fans out
        // to any other conversation (fan-out removed 2026-10-20).
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
                    // Telephony group flag, same contract as the RCS
                    // mapping: more than one OTHER participant = group.
                    isGroup = others.size > 1,
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
                            deliveryStatus = if (typeI >= 0 && c.getInt(typeI) == 2)
                                DeliveryStatus.SENT else DeliveryStatus.NONE,
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
                            deliveryStatus = if (boxI >= 0 && c.getInt(boxI) == 2)
                                DeliveryStatus.SENT else DeliveryStatus.NONE,
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
        // Manual Mark-as-unread must stick (Garrett 2026-10-20): drop
        // any optimistic-read latch for these threads FIRST, or the
        // refresh below — and every later one — would hold the row read
        // against the state the user just chose by hand.
        threadIds.forEach { readLatch.clear(CategoryStore.conversationKey(null, it)) }
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
        // A deleted thread's read latch dies with it — provider thread
        // ids can be reused, and a stale latch must never hold a future
        // thread read.
        threadIds.forEach { readLatch.clear(CategoryStore.conversationKey(null, it)) }
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
     * GM snippet prefix for a Telephony thread (Garrett 2026-10-20): the
     * thread's newest message across its SMS and MMS legs decides —
     * outgoing -> "You: "; incoming in a group -> "{FirstName}: " with
     * the sender resolved through contacts (never a bare number);
     * otherwise "". Same per-thread latest-message query pattern as
     * [latestTransportLabel]; the prefix describes the same message the
     * snippet text came from.
     */
    private fun latestSnippetPrefix(threadId: Long, isGroup: Boolean): String {
        var smsDate = -1L
        var smsFromMe = false
        var smsAddress: String? = null
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("type", "address", "date"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    // Telephony.Sms types: 1 = inbox; 2..6 are the sent
                    // side (sent, draft, outbox, failed, queued).
                    smsFromMe = c.getInt(0) != 1
                    smsAddress = c.getString(1)
                    smsDate = c.getLong(2)
                }
            }
        } catch (_: Exception) { }
        var mmsDate = -1L
        var mmsFromMe = false
        var mmsId = -1L
        try {
            context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date", "msg_box"), "thread_id=?", arrayOf(threadId.toString()),
                "date DESC LIMIT 1",
            )?.use { c ->
                if (c.moveToFirst()) {
                    mmsId = c.getLong(0)
                    mmsDate = c.getLong(1) * 1000L
                    // Telephony.Mms boxes: 1 = inbox; 2..5 the sent side.
                    mmsFromMe = c.getInt(2) != 1
                }
            }
        } catch (_: Exception) { }
        val fromMe: Boolean
        val senderAddress: String?
        if (mmsDate > smsDate) {
            fromMe = mmsFromMe
            senderAddress = if (mmsId >= 0) mmsAddress(mmsId) else null
        } else {
            fromMe = smsFromMe
            senderAddress = smsAddress
        }
        if (fromMe) return "You: "
        if (!isGroup) return ""
        val sender = senderAddress?.takeIf { it.isNotBlank() } ?: return ""
        // Contact name or nothing — never prefix with a bare number.
        val name = contactLookup.displayName(sender)
        if (name.isBlank() || name == sender) return ""
        return snippetPrefix(latestFromMe = false, isGroup = true, latestSenderName = name)
    }

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
