package com.garrietta.relay.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.model.MessageReaction
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.model.locationMapsLink
import com.garrietta.relay.data.model.isGroupEventNotice
import com.garrietta.relay.data.model.isTransportSystemNotice
import com.garrietta.relay.data.model.rcsStatusToDelivery
import com.garrietta.relay.data.repo.AppSeedStore
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatScrollStore
import com.garrietta.relay.data.repo.ScrollPosition
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.SendResult
import com.garrietta.relay.ui.theme.ContactPalettes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

class ChatViewModel(
    private val repository: UnifiedInboxRepository,
    private val appSeedStore: AppSeedStore,
    private val scrollStore: ChatScrollStore,
    private val aiSettingsStore: com.garrietta.relay.data.repo.AiSettingsStore,
    private val transcriptStore: com.garrietta.relay.data.repo.TranscriptStore,
    /** Application context: the on-device recognizer + scratch files. */
    private val appContext: android.content.Context,
    private val threadId: Long,
    /** All provider threads merged into this conversation (see Screen.Chat). */
    private val threadIds: List<Long> = listOf(threadId),
    /** Recipient address — used to re-resolve threads if the ID key goes stale. */
    private val address: String = "",
    /**
     * Server conversation id for RCS rows (paired mode). When set, message
     * loads/sends go through the RCS session, never the Telephony provider.
     * Mutable: a brand-new conversation resolves it on init.
     */
    private var rcsConversationId: String? = null,
    /** Header/composer transport label from the nav arg; refreshed from the server type for RCS rows. */
    initialTransportLabel: String = "SMS",
) : ViewModel() {

    /** The REAL transport label: the server's Conversation.type for RCS rows, the nav arg otherwise. */
    private val _transportLabel = MutableStateFlow(initialTransportLabel)
    val transportLabel: StateFlow<String> = _transportLabel

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    /**
     * RCS history depth: starts at the fast 50 for the first paint, grows
     * as the background deep fill lands — later reloads keep the deeper
     * count so the list never shrinks back.
     */
    private var rcsHistoryCount = 50L
    private companion object {
        /** Garrett 2026-10-19: at least three weeks of chat history. */
        const val HISTORY_DAYS = 21L
        /** Safety cap on the background deep fill (messages). */
        const val MAX_HISTORY_COUNT = 2000L
        /** Pacing between forwarded sends so the target thread keeps order. */
        const val FORWARD_PACING_MS = 300L
    }

    private var activeThreadId: Long = threadId

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    /**
     * Remote typing indicator (Garrett 2026-10-19): true while this
     * conversation has an entry in the transport's ONE typing store
     * (RcsTransport.typingStates — the same store the inbox rows read).
     * Expiry (typing=false, message arrival, TTL) is enforced in the
     * store, not here.
     */
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping

    /**
     * Long-press bubble selection (M3E shape-as-state: selected bubbles
     * square off). Tapping a bubble while any selection is active toggles it.
     */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds

    /**
     * Tracks whether this chat is the currently visible screen, so the
     * application-scoped notification collector (RelayApp) can skip buzzing
     * for the chat Garrett is already looking at. Called from ChatScreen's
     * DisposableEffect (ViewModel.onCleared doesn't fire on pop — the VM
     * survives in the activity scope while the back stack changes).
     */
    fun setVisible(visible: Boolean) {
        if (visible) {
            repository.openRcsConversation.value = rcsConversationId
        } else if (repository.openRcsConversation.value == rcsConversationId) {
            repository.openRcsConversation.value = null
        }
    }

    fun toggleSelect(id: Long) {
        _selectedIds.value = _selectedIds.value.let {
            if (id in it) it - id else it + id
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /** Inbox conversations (newest first) — the forward picker's targets. */
    val conversations: StateFlow<List<Conversation>> = repository.conversations

    /** True while a forward batch is sending (the selection bar shows it). */
    private val _forwarding = MutableStateFlow(false)
    val forwarding: StateFlow<Boolean> = _forwarding

    /**
     * One-shot forward outcome for the snackbar ("Forwarded 3 to Dad",
     * "Forwarded 2 of 5 … — stopped: <verbatim reason>"). The screen
     * shows it once and clears it, same pattern as [sendError].
     */
    private val _forwardResult = MutableStateFlow<String?>(null)
    val forwardResult: StateFlow<String?> = _forwardResult

    fun clearForwardResult() {
        _forwardResult.value = null
    }

    /**
     * Multi-message forwarding (Garrett 2026-10-20, feature queue 4/7 —
     * the Google Messages most-requested batch forward). Sends every
     * selected message into [target], OLDEST first, as ordinary messages
     * through the existing send paths: RCS targets via
     * [UnifiedInboxRepository.sendRcs] (to that conversation's own
     * server id — no cross-conversation routing), SMS/Telephony
     * targets via the SMS transport — the same routing the composer uses
     * for that conversation (a server conversation typed SMS (type 1)
     * sends as SMS even while paired). Media messages re-send their
     * attachment through the #131 upload pipeline (decrypted bytes from
     * the mediaBytes choke point, caption riding along); where media
     * can't ride (non-RCS target, download/upload failure) the caption
     * still forwards as text and the attachment is COUNTED in the
     * outcome summary — never silently dropped. A failed text send
     * stops the batch and reports how many landed before it, with the
     * verbatim reason. Reactions are badges on their targets, not
     * messages — they never forward; transport system notices are
     * excluded up front.
     */
    fun forwardSelected(target: Conversation) {
        if (_forwarding.value) return
        val batch = _messages.value
            .filter { it.id in _selectedIds.value }
            .filterNot { isTransportSystemNotice(it.body) }
            // Group event sentences are system items, not messages —
            // they never forward (they can't be selected in the UI
            // either; this is the belt-and-braces half).
            .filterNot { _others.value.size > 1 && isGroupEventNotice(it.body) }
            .sortedBy { it.date }
        if (batch.isEmpty()) {
            clearSelection()
            return
        }
        _forwarding.value = true
        viewModelScope.launch {
            val targetRcsId = target.rcsConversationId
            // Same routing rule as send(): only a server conversation NOT
            // typed SMS takes the RCS session; everything else is SMS.
            val targetIsRcs = targetRcsId != null &&
                repository.rcsConversationType(targetRcsId) != 1
            var landed = 0
            var attachmentsLost = 0
            var mediaFailure: String? = null
            var stopFailure: String? = null
            try {
                for ((index, msg) in batch.withIndex()) {
                    if (index > 0) kotlinx.coroutines.delay(FORWARD_PACING_MS)
                    val att = msg.media
                    if (att == null) {
                        val err = forwardText(target, targetIsRcs, targetRcsId, msg.body)
                        if (err != null) {
                            stopFailure = err
                            break
                        }
                        landed++
                        continue
                    }
                    // Media message: the full re-send first (RCS targets
                    // only — Relay never sends MMS).
                    var mediaSent = false
                    if (targetIsRcs && targetRcsId != null) {
                        val (ok, reason) = forwardMedia(targetRcsId, msg, att)
                        mediaSent = ok
                        if (!ok && mediaFailure == null) mediaFailure = reason
                    } else if (mediaFailure == null) {
                        mediaFailure = "Attachments need an RCS chat"
                    }
                    if (mediaSent) {
                        landed++
                    } else {
                        attachmentsLost++
                        // The caption still forwards as a plain text.
                        if (msg.body.isNotBlank()) {
                            val err = forwardText(target, targetIsRcs, targetRcsId, msg.body)
                            if (err != null) {
                                stopFailure = err
                                break
                            }
                            landed++
                        }
                    }
                }
            } finally {
                _forwarding.value = false
            }
            // Selection clears on every completed attempt: the landed
            // messages can't be un-sent, and leaving them selected invites
            // an accidental duplicate re-forward.
            clearSelection()
            _forwardResult.value = when {
                stopFailure != null ->
                    if (landed > 0) {
                        "Forwarded $landed of ${batch.size} to ${target.displayName} — stopped: $stopFailure"
                    } else {
                        "Couldn't forward to ${target.displayName}: $stopFailure"
                    }
                landed == 0 && mediaFailure != null ->
                    "Couldn't forward to ${target.displayName}: $mediaFailure"
                attachmentsLost > 0 ->
                    "Forwarded $landed to ${target.displayName} — " +
                        "$attachmentsLost attachment${if (attachmentsLost == 1) "" else "s"} " +
                        "couldn't be forwarded"
                else -> "Forwarded $landed to ${target.displayName}"
            }
        }
    }

    /**
     * Sends one text body into [target] over the conversation's own send
     * path. Returns the VERBATIM failure reason, or null on success.
     */
    private suspend fun forwardText(
        target: Conversation,
        targetIsRcs: Boolean,
        targetRcsId: String?,
        body: String,
    ): String? {
        if (body.isBlank()) return null
        return if (targetIsRcs && targetRcsId != null) {
            when (val outcome = repository.sendRcs(
                targetRcsId, body, java.util.UUID.randomUUID().toString(),
            )) {
                is UnifiedInboxRepository.RcsSendOutcome.Sent -> null
                is UnifiedInboxRepository.RcsSendOutcome.Failed -> outcome.reason
            }
        } else {
            when (val result = repository.smsTransport.sendMessage(target.address, body)) {
                is SendResult.Sent -> null
                is SendResult.Failed -> result.reason
                is SendResult.Unavailable -> result.reason
            }
        }
    }

    /**
     * Re-sends [msg]'s attachment into the RCS conversation [targetRcsId]:
     * decrypted bytes via the mediaBytes choke point, re-uploaded through
     * the #131 file-transfer pipeline, the caption riding as the media
     * message's text, the source dimensions carried over. Returns
     * (sent, verbatim failure reason when not sent).
     */
    private suspend fun forwardMedia(
        targetRcsId: String,
        msg: Message,
        att: com.garrietta.relay.rcs.proto.RcsMediaAttachment,
    ): Pair<Boolean, String?> {
        return try {
            val bytes = repository.mediaBytes(att)
            if (bytes.isEmpty()) return false to "media unavailable: empty attachment"
            val ext = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(att.mimeType)?.ifBlank { null } ?: "bin"
            val uploaded = repository.uploadRcsMedia(
                bytes, att.mimeType, "forwarded.$ext",
            ) {}
            when (val outcome = repository.sendRcsMedia(
                targetRcsId,
                uploaded.copy(width = att.width, height = att.height),
                msg.body,
                java.util.UUID.randomUUID().toString(),
            )) {
                is UnifiedInboxRepository.RcsSendOutcome.Sent -> true to null
                is UnifiedInboxRepository.RcsSendOutcome.Failed -> false to outcome.reason
            }
        } catch (e: Exception) {
            false to (e.message ?: "media unavailable: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Palette-extracted seed color from the contact's profile photo; drives
     * per-contact chat theming. Null = no photo (or extraction failed) → the
     * wallpaper dynamic scheme is used instead.
     */
    private val _seedColor = MutableStateFlow<androidx.compose.ui.graphics.Color?>(null)
    val seedColor: StateFlow<androidx.compose.ui.graphics.Color?> = _seedColor

    /** Contact profile photo URI for the avatar; null when there is none. */
    private val _photoUri = MutableStateFlow<String?>(null)
    val photoUri: StateFlow<String?> = _photoUri

    /**
     * Group participants (1:1 threads: the single other party). Drives the
     * group header cluster and per-sender bubble theming. Keyed maps below
     * are indexed by NORMALIZED address so message senders match regardless
     * of provider address formatting.
     */
    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants

    /**
     * Participants OTHER than the device owner. Drives the header mode:
     * 1 other = 1:1 header, 2 others = pair header, 3+ = hexagonal group
     * header. The provider's MMS addr table lists self (FROM on sent, TO on
     * received), which used to misdetect him+1 threads as groups.
     */
    private val _others = MutableStateFlow<List<Participant>>(emptyList())
    val others: StateFlow<List<Participant>> = _others

    /**
     * Bubble-tint seed per participant: photo-derived when the sender
     * has a photo; a photo-less sender seeds from their assigned
     * expressive palette instead (Garrett 2026-10-20) — null now only
     * for an address that isn't a known participant.
     */
    private val _senderSeeds = MutableStateFlow<Map<String, androidx.compose.ui.graphics.Color?>>(emptyMap())
    val senderSeeds: StateFlow<Map<String, androidx.compose.ui.graphics.Color?>> = _senderSeeds

    private var participantIndex: Map<String, Participant> = emptyMap()

    /** Pending optimistic RCS bubbles, keyed by tmpId (echo reconciliation). */
    private val pendingOptimistic = mutableMapOf<String, Message>()

    init {
        val initialRcsId = rcsConversationId
        if (initialRcsId != null) {
            initRcs(initialRcsId)
        } else if (address.isNotBlank() && repository.isRcsPaired()) {
            // Paired + brand-new conversation (e.g. the self-chat test):
            // resolve the server conversation first, then run the RCS path.
            viewModelScope.launch {
                try {
                    rcsConversationId = repository.rcsConversationFor(address)
                    initRcs(rcsConversationId!!)
                } catch (e: Exception) {
                    _sendError.value = "Couldn't open chat: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        } else {
            reload()
            loadParticipants()
            viewModelScope.launch {
                repository.inboxChanges().collect { reload() }
            }
        }
    }

    /**
     * RCS thread init: history from the server, participants from the server
     * (self excluded via isMe + ownAddress fallback, deduped — never in the
     * header unless it's the self-chat), and a live subscription for echoes
     * + new arrivals. The transport label comes from the server's
     * Conversation.type, never hardcoded.
     */
    private fun initRcs(conversationId: String) {
        // Instant open: show the cached messages immediately (empty on
        // first visit), then refresh in the background and rewrite the
        // cache — no visible load delay on tap. The first network pull never
        // goes SHALLOWER than what's on screen (Garrett 2026-10-19): if the
        // cache already holds 300, pulling 50 would shrink the list out
        // from under a restored scroll position.
        publishMessages(repository.cachedMessages(conversationId) ?: emptyList())
        viewModelScope.launch {
            // Disk LRU (Garrett 2026-10-19): on cold start the memory cache
            // missed — paint instantly from the persistent 50-chat history
            // while the network load runs behind it.
            if (_messages.value.isEmpty()) {
                val disk = repository.cachedHistoryFromDisk(conversationId)
                if (disk != null && _messages.value.isEmpty()) publishMessages(disk)
            }
            val floor = maxOf(rcsHistoryCount, _messages.value.size.toLong())
            // Keep the count consistent for later live reloads — a later
            // 50-pull must not shrink a 300-deep list either.
            rcsHistoryCount = floor
            publishMessages(repository.rcsMessages(conversationId, floor))
            repository.markRcsRead(conversationId)
            // Background deep fill (Garrett 2026-10-19: "at least the last
            // three weeks of chats"). The first paint stays instant from
            // cache; then we page deeper in the background until the oldest
            // loaded message is >= 21 days old or the server is exhausted.
            // Each step only replaces the list when it actually grew, so the
            // UI never flickers or slows the initial open.
            try {
                var target = maxOf(500L, floor)
                while (target <= MAX_HISTORY_COUNT) {
                    val deep = repository.rcsMessages(conversationId, target)
                    if (deep.size > _messages.value.size) {
                        rcsHistoryCount = target
                        setMessages(deep)
                    }
                    val oldest = deep.minOfOrNull { it.date } ?: break
                    val daysBack =
                        (System.currentTimeMillis() - oldest) / 86_400_000L
                    // Server exhausted (returned fewer than asked) or three
                    // weeks deep — done.
                    if (deep.size < target || daysBack >= HISTORY_DAYS) break
                    target += 500L
                }
            } catch (e: Exception) {
                Log.w(TAG, "Deep history fill failed: ${e.message}", e)
            }
        }
        viewModelScope.launch {
            _transportLabel.value =
                if (repository.rcsConversationType(conversationId) == 1) "SMS" else "RCS"
        }
        viewModelScope.launch {
            val parts = repository.getRcsParticipants(conversationId)
            _participants.value = parts
            _others.value = repository.rcsOthers(conversationId)
            participantIndex = parts.associateBy { repository.normalizedAddress(it.address) }
            val seeds = mutableMapOf<String, androidx.compose.ui.graphics.Color?>()
            for (p in parts) {
                val key = repository.normalizedAddress(p.address)
                // No photo (or extraction failed): seed the bubble
                // tint from the sender's assigned expressive palette
                // (Garrett 2026-10-20) — the same hue family as their
                // photo-less avatar, never the neutral fallback.
                seeds[key] = p.photoUri?.let { repository.extractContactSeed(it) }
                    ?: ContactPalettes.paletteFor(p.address).seed
            }
            _senderSeeds.value = seeds
        }
        viewModelScope.launch {
            repository.observeRcsMessages().collect { msg ->
                // Only arrivals for THIS conversation belong to this
                // chat — nothing crosses conversation boundaries.
                if (msg.conversationId == conversationId) {
                    onRcsIncoming(msg)
                }
            }
        }
        viewModelScope.launch {
            // Inbox tick reload (Garrett 2026-10-19): the notification
            // path forces repository.refresh() when it posts — the open
            // chat re-pulls its thread so the message appears in-app, not
            // just in the notification shade.
            repository.inboxChanges().collect {
                publishMessages(repository.rcsMessages(conversationId, rcsHistoryCount))
            }
        }
        // Remote typing: read the transport's typing store — the same
        // store the inbox rows read (Garrett 2026-10-20: one source, no
        // divergent logic). The wire event carries the typist's number;
        // the in-chat surface only needs the boolean for this
        // conversation. The store enforces expiry.
        viewModelScope.launch {
            repository.rcsTransport.typingStates.collect { states ->
                _isTyping.value = conversationId in states
            }
        }
        if (address.isNotBlank()) loadContactTheme(address)
    }

    /**
     * Live RCS arrival. A server echo carries the optimistic bubble's tmpId
     * and replaces it; a status-only push (same server id, new status)
     * updates just the ticks; anything else is genuinely new — reload to
     * stay consistent rather than risking a duplicate.
     */
    private fun onRcsIncoming(msg: com.garrietta.relay.rcs.proto.RcsMessage) {
        val tmpId = msg.tmpId.ifBlank { null }
        val optimistic = tmpId?.let { pendingOptimistic.remove(it) }
        if (optimistic == null) {
            // Status-only update? Match by server id and patch the ticks
            // without a full reload.
            val serverId = msg.id.hashCode().toLong()
            val newStatus = rcsStatusToDelivery(msg.status, msg.fromMe)
            val current = _messages.value
            val idx = current.indexOfFirst { it.id == serverId }
            if (idx >= 0 && current[idx].deliveryStatus != newStatus &&
                newStatus != DeliveryStatus.NONE
            ) {
                setMessages(
                    current.toMutableList().also {
                        it[idx] = it[idx].copy(deliveryStatus = newStatus)
                    },
                )
                return
            }
            viewModelScope.launch {
                publishMessages(
                    repository.rcsMessages(
                        rcsConversationId ?: return@launch,
                        rcsHistoryCount,
                    ),
                )
            }
            return
        }
        val mapped = Message(
            id = msg.id.hashCode().toLong(),
            threadId = threadId,
            address = optimistic.address,
            body = msg.body.ifBlank { optimistic.body },
            date = msg.timestampMs,
            isOutgoing = true,
            isMms = false,
            read = true,
            tmpId = tmpId,
            location = msg.location ?: optimistic.location,
            deliveryStatus = rcsStatusToDelivery(msg.status, true),
        )
        setMessages(
            _messages.value
                .map { if (it.tmpId == tmpId) mapped else it }
                .sortedByDescending { it.date },
        )
        viewModelScope.launch { repository.markRcsRead(rcsConversationId ?: return@launch) }
    }

    /**
     * Saves the current first-visible position so reopening restores it.
     * [newestMessageId]/[newestMessageDate] snapshot the list's tail at
     * save time: on reopen, a tail newer than the snapshot means the
     * chat opens at the new bottom instead of the stale anchor
     * (Garrett 2026-10-20, [ChatScrollLogic.hasNewerThanSaved]).
     */
    fun saveScrollPosition(
        messageId: Long,
        scrollOffset: Int,
        newestMessageId: Long,
        newestMessageDate: Long,
    ) {
        scrollStore.save(
            cacheKey(), messageId, scrollOffset, newestMessageId, newestMessageDate,
        )
    }

    /** The saved scroll position for this conversation, if any. */
    fun savedScrollPosition(): ScrollPosition? = scrollStore.positionFor(cacheKey())

    /**
     * Cache key for this thread: the RCS conversation id when known, else
     * the Telephony thread id. Matches the keys the repository's loaders
     * write through.
     */
    private fun cacheKey(): String =
        CategoryStore.conversationKey(
            rcsConversationId, threadIds.firstOrNull() ?: threadId,
        )

    /**
     * Assigns the message list, consuming reaction text messages into their
     * targets' [Message.reactions] so they render as inline badges instead
     * of stray bubbles.
     */
    private fun publishMessages(list: List<Message>) {
        _messages.value = attachReactions(list, ::reactorNameFor)
    }

    /** Resolves a reaction message's reactor display name ("You" when Garrett reacted). */
    private fun reactorNameFor(msg: Message): String =
        if (msg.isOutgoing) "You" else senderFor(msg.address)?.displayName ?: msg.address

    /** Sets the message list and writes it through to the instant-open cache. */
    private fun setMessages(list: List<Message>) {
        publishMessages(list)
        repository.putCachedMessages(cacheKey(), _messages.value)
    }

    /** Resolves the participant who sent [address], or null when unknown. */
    fun senderFor(address: String): Participant? {
        val idx = participantIndex
        return idx[address] ?: idx[repository.normalizedAddress(address)]
    }

    /** Bubble-tint seed for the sender of [address]; null only when the sender is not a known participant. */
    fun seedFor(address: String): androidx.compose.ui.graphics.Color? {
        val seeds = _senderSeeds.value
        return seeds[address] ?: seeds[repository.normalizedAddress(address)]
    }

    fun loadParticipants() {
        viewModelScope.launch {
            val parts = repository.getGroupParticipants(threadIds)
            _participants.value = parts
            val self = repository.ownAddress()
            val computed = if (self == null) parts
                else parts.filterNot { repository.normalizedAddress(it.address) == self }
            // Same-source reconciliation as the RCS path (Garrett
            // 2026-10-20, "No Dad"): if the inbox row's participant list
            // is fuller than this thread query's, the row wins — the
            // chat's group classification and member list must match the
            // row the user tapped.
            val inboxOthers = repository.inboxRowParticipants(null, threadId, threadIds)
            _others.value = if (inboxOthers.size > computed.size) inboxOthers else computed
            participantIndex = parts.associateBy { repository.normalizedAddress(it.address) }
            val seeds = mutableMapOf<String, androidx.compose.ui.graphics.Color?>()
            for (p in parts) {
                val key = repository.normalizedAddress(p.address)
                // No photo (or extraction failed): the assigned
                // expressive palette seeds the bubble tint (Garrett
                // 2026-10-20) — same rule as the RCS path above.
                seeds[key] = p.photoUri?.let { repository.extractContactSeed(it) }
                    ?: ContactPalettes.paletteFor(p.address).seed
            }
            _senderSeeds.value = seeds
        }
    }

    fun loadContactTheme(recipient: String) {
        viewModelScope.launch {
            val uri = repository.resolvePhotoUri(recipient) ?: return@launch
            _photoUri.value = uri
            val seed = repository.extractContactSeed(uri)
            _seedColor.value = seed
            // App-wide persistent seed (Item 5): only a chat WITH a contact
            // photo replaces it. Chats without photos keep the current seed.
            if (seed != null) appSeedStore.setSeed(seed)
        }
    }

    fun reload() {
        // Instant open (Telephony path): cached messages first, then the
        // provider refresh rewrites the cache in the background.
        val smsKey = CategoryStore.conversationKey(
            null, threadIds.firstOrNull() ?: threadId,
        )
        publishMessages(repository.cachedMessages(smsKey) ?: emptyList())
        if (_messages.value.isEmpty()) {
            // Disk LRU: cold-start instant paint from the persistent
            // 50-chat history while the provider load runs.
            viewModelScope.launch {
                val disk = repository.cachedHistoryFromDisk(smsKey)
                if (disk != null && _messages.value.isEmpty()) publishMessages(disk)
            }
        }
        viewModelScope.launch {
            // Merged conversations load the full history across thread_ids.
            val loaded = repository.loadMessages(threadIds)
            publishMessages(loaded.ifEmpty {
                // Empty but the inbox showed a snippet: the thread_id key
                // may have gone stale (provider re-split threads). Re-resolve
                // by address so the thread shows the same unified set.
                if (address.isBlank()) {
                    emptyList()
                } else {
                    val fallback = repository.loadMessagesByAddress(address)
                    if (fallback.isNotEmpty()) {
                        Log.w(TAG, "reload: threadIds=$threadIds empty; address fallback found ${fallback.size}")
                    }
                    fallback
                }
            })
            if (activeThreadId >= 0) repository.markThreadRead(threadIds)
        }
    }

    fun send(recipient: String, body: String) {
        if (body.isBlank() || _sending.value) return
        _sending.value = true
        _sendError.value = null
        viewModelScope.launch {
            // Resolve the RCS conversation on demand: init's async
            // resolution (rcsConversationFor) may still be in flight when
            // the user hits send immediately after opening the chat.
            // session.conversationFor caches, so a redundant call is cheap.
            val convId = rcsConversationId ?: if (repository.isRcsPaired() && address.isNotBlank()) {
                runCatching { repository.rcsConversationFor(address) }
                    .onFailure { e ->
                        Log.w(TAG, "send: on-demand RCS resolve failed", e)
                    }
                    .getOrNull()?.also { rcsConversationId = it }
            } else null
            if (convId != null) {
                // Route by the REAL server transport: an SMS-marked
                // conversation (type=1) sends via SMS even when paired; only
                // RCS-marked conversations go through the RCS session.
                val type = repository.rcsConversationType(convId)
                _transportLabel.value = if (type == 1) "SMS" else "RCS"
                if (type == 1) sendSms(recipient, body) else sendRcs(convId, body)
            } else {
                sendSms(recipient, body)
            }
            _sending.value = false
        }
    }

    /**
     * RCS send: optimistic bubble (keyed by tmpId) → server → the long-poll
     * echo replaces it via [onRcsIncoming]. Failures remove the optimistic
     * bubble and surface the VERBATIM reason (no logcat on his device).
     */
    private suspend fun sendRcs(conversationId: String, body: String) {
        val tmpId = java.util.UUID.randomUUID().toString()
        val optimistic = Message(
            id = -System.currentTimeMillis(),
            threadId = threadId,
            address = "",
            body = body,
            date = System.currentTimeMillis(),
            isOutgoing = true,
            isMms = false,
            read = true,
            tmpId = tmpId,
            deliveryStatus = DeliveryStatus.SENDING,
        )
        pendingOptimistic[tmpId] = optimistic
        // Newest first (reverseLayout list): the optimistic bubble lands at the bottom.
        setMessages((_messages.value + optimistic).sortedByDescending { it.date })
        // Dispatched: the composer resets NOW, not when the wire call
        // returns (Garrett 2026-10-20 — the sending state visibly
        // outlasted delivery). The bubble carries the wire state from
        // here (SENDING ticks → echo). send()'s trailing reset remains
        // as the backstop for paths that fail before this point.
        _sending.value = false
        when (val outcome = repository.sendRcs(conversationId, body, tmpId)) {
            is UnifiedInboxRepository.RcsSendOutcome.Sent -> {
                // The server echo (same tmpId) replaces the optimistic bubble.
            }
            is UnifiedInboxRepository.RcsSendOutcome.Failed -> {
                pendingOptimistic.remove(tmpId)
                setMessages(_messages.value.filterNot { it.tmpId == tmpId })
                _sendError.value = outcome.reason
            }
        }
    }

    /**
     * Shares the current location (Garrett 2026-10-20, feature queue
     * 6/7) from the composer [+] sheet's Location entry. Routing mirrors
     * text sends exactly: an RCS conversation gets the native
     * locationContent payload with the Maps link riding in the same
     * send; an SMS conversation (server type 1, or Telephony) gets the
     * Maps link as a plain text message — the form that works in every
     * client. One-shot pin only; nothing tracks in the background.
     */
    fun sendLocation(
        recipient: String,
        latitude: Double,
        longitude: Double,
        name: String?,
    ) {
        if (_sending.value) return
        _sending.value = true
        _sendError.value = null
        viewModelScope.launch {
            try {
                // Same on-demand RCS resolution as send(): the init-time
                // resolve may still be in flight.
                val convId = rcsConversationId ?: if (repository.isRcsPaired() && address.isNotBlank()) {
                    runCatching { repository.rcsConversationFor(address) }
                        .onFailure { e ->
                            Log.w(TAG, "sendLocation: on-demand RCS resolve failed", e)
                        }
                        .getOrNull()?.also { rcsConversationId = it }
                } else null
                val link = locationMapsLink(latitude, longitude)
                if (convId != null && repository.rcsConversationType(convId) != 1) {
                    sendRcsLocation(convId, latitude, longitude, name.orEmpty(), link)
                } else {
                    // SMS: the link as plain text, through the normal SMS path.
                    sendSms(recipient, link)
                }
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * RCS location send: optimistic location bubble (tmpId-keyed, body =
     * the Maps link that rides along) → server → the long-poll echo
     * replaces it via [onRcsIncoming]. Failures remove the optimistic
     * bubble and surface the VERBATIM reason, exactly like text sends.
     */
    private suspend fun sendRcsLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        name: String,
        link: String,
    ) {
        val tmpId = java.util.UUID.randomUUID().toString()
        val optimistic = Message(
            id = -System.currentTimeMillis(),
            threadId = threadId,
            address = "",
            body = link,
            date = System.currentTimeMillis(),
            isOutgoing = true,
            isMms = false,
            read = true,
            tmpId = tmpId,
            location = com.garrietta.relay.rcs.proto.RcsLocation(
                latitude, longitude, name,
            ),
            deliveryStatus = DeliveryStatus.SENDING,
        )
        pendingOptimistic[tmpId] = optimistic
        setMessages((_messages.value + optimistic).sortedByDescending { it.date })
        // Dispatched — composer sending state resets at the insert,
        // not after the wire call (see sendRcs).
        _sending.value = false
        when (val outcome = repository.sendRcsLocation(
            conversationId, latitude, longitude, name, link, tmpId,
        )) {
            is UnifiedInboxRepository.RcsSendOutcome.Sent -> {
                // The server echo (same tmpId) replaces the optimistic bubble.
            }
            is UnifiedInboxRepository.RcsSendOutcome.Failed -> {
                pendingOptimistic.remove(tmpId)
                setMessages(_messages.value.filterNot { it.tmpId == tmpId })
                _sendError.value = outcome.reason
            }
        }
    }

    /**
     * Sends an attachment picked in the composer [+] sheet (Garrett
     * 2026-10-19: attachment SENDING, finally). Reads the bytes, shows an
     * optimistic bubble carrying the local bytes + live upload progress,
     * uploads through the RCS file-transfer path, then sends the media
     * message; the server echo replaces the optimistic bubble via tmpId.
     * Failures remove the optimistic bubble and surface the VERBATIM
     * reason. Attachments are RCS-only (SMS/MMS sending goes through the
     * SMS transport, which Relay never drives as the default SMS app).
     */
    fun sendAttachment(uri: android.net.Uri, resolver: android.content.ContentResolver) {
        if (_sending.value) return
        _sending.value = true
        _sendError.value = null
        viewModelScope.launch {
            try {
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                var name = "attachment"
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("media unavailable: couldn't read the selected file")
                if (bytes.isEmpty()) {
                    throw IllegalStateException("media unavailable: the selected file is empty")
                }
                sendMediaBytes(bytes, mime, name)
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Attachment send failed"
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Sends a starter-pack sticker (Garrett 2026-10-20, feature queue
     * 5/7): the artwork is rasterized to a transparent PNG in code (see
     * [StickerRenderer]) and sent through the exact same media pipeline
     * as a photo attachment — optimistic bubble, live upload progress,
     * verbatim failures. The upload's file name is the sticker tag
     * ([stickerFileName]) that receiving Relays recognize. RCS-only,
     * exactly like photo sends: an SMS chat gets the same "Attachments
     * need an RCS chat" refusal (Relay never sends MMS).
     */
    fun sendSticker(def: StickerDef) {
        if (_sending.value) return
        _sending.value = true
        _sendError.value = null
        viewModelScope.launch {
            try {
                val bytes = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.Default,
                ) { StickerRenderer.pngBytes(def) }
                sendMediaBytes(bytes, "image/png", stickerFileName(def))
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Sticker send failed"
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * The shared media-send core behind [sendAttachment] and
     * [sendSticker]: resolves the RCS conversation, shows an optimistic
     * bubble carrying the local bytes + live upload progress, uploads
     * through the RCS file-transfer path, then sends the media message;
     * the server echo replaces the optimistic bubble via tmpId. Throws
     * with the VERBATIM reason on any failure (the optimistic bubble is
     * removed first); callers surface it as the send error. RCS-only.
     */
    private suspend fun sendMediaBytes(bytes: ByteArray, mime: String, name: String) {
        val convId = rcsConversationId ?: if (repository.isRcsPaired() && address.isNotBlank()) {
            runCatching { repository.rcsConversationFor(address) }
                .getOrNull()?.also { rcsConversationId = it }
        } else null
        if (convId == null || repository.rcsConversationType(convId) == 1) {
            throw IllegalStateException("Attachments need an RCS chat")
        }
        // Image dimensions for the MediaContent dimensions field.
        var w = 0L
        var h = 0L
        if (mime.startsWith("image/")) {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                w = opts.outWidth.toLong(); h = opts.outHeight.toLong()
            }
        }
        val tmpId = java.util.UUID.randomUUID().toString()
        val optimistic = Message(
            id = -System.currentTimeMillis(),
            threadId = threadId,
            address = "",
            body = "",
            date = System.currentTimeMillis(),
            isOutgoing = true,
            isMms = false,
            read = true,
            tmpId = tmpId,
            media = com.garrietta.relay.rcs.proto.RcsMediaAttachment(
                mediaId = "",
                mimeType = mime,
                decryptionKey = byteArrayOf(),
                width = w,
                height = h,
                size = bytes.size.toLong(),
                inlineData = bytes,
                name = name,
            ),
            deliveryStatus = DeliveryStatus.SENDING,
            uploadProgress = 0f,
        )
        pendingOptimistic[tmpId] = optimistic
        setMessages((_messages.value + optimistic).sortedByDescending { it.date })
        // Dispatched — composer sending state resets at the insert;
        // upload progress lives on the bubble, not the send button
        // (see sendRcs).
        _sending.value = false
        try {
            val uploaded = repository.uploadRcsMedia(bytes, mime, name) { p ->
                val cur = _messages.value
                val idx = cur.indexOfFirst { it.tmpId == tmpId }
                if (idx >= 0) {
                    setMessages(cur.toMutableList().apply {
                        this[idx] = this[idx].copy(uploadProgress = p)
                    })
                }
            }
            when (val outcome = repository.sendRcsMedia(
                convId, uploaded.copy(width = w, height = h), "", tmpId,
            )) {
                is UnifiedInboxRepository.RcsSendOutcome.Sent -> {
                    val cur = _messages.value
                    val idx = cur.indexOfFirst { it.tmpId == tmpId }
                    if (idx >= 0) {
                        setMessages(cur.toMutableList().apply {
                            this[idx] = this[idx].copy(uploadProgress = null)
                        })
                    }
                    pendingOptimistic[tmpId]?.let {
                        pendingOptimistic[tmpId] = it.copy(uploadProgress = null)
                    }
                }
                is UnifiedInboxRepository.RcsSendOutcome.Failed -> {
                    throw IllegalStateException(outcome.reason)
                }
            }
        } catch (e: Exception) {
            pendingOptimistic.remove(tmpId)
            setMessages(_messages.value.filterNot { it.tmpId == tmpId })
            throw e
        }
    }

    /**
     * Sends an emoji reaction to [target] (Garrett 2026-10-19: reactions
     * both ways). REAL reaction event via the paired protocol
     * (SEND_REACTION, client.SendReactionRequest) — NOT the old text-format
     * fake, which landed as literal "❤️ to ..." spam on recipients' other
     * clients. The badge applies optimistically and the server echo
     * reconciles it; a declined send rolls the badge back with the
     * verbatim reason. Needs the target's server message id (RCS chats
     * only).
     */
    fun sendReaction(target: Message, emoji: String) {
        viewModelScope.launch {
            val serverId = target.serverId
            if (serverId.isNullOrBlank()) {
                _sendError.value = "Reactions need an RCS message"
                return@launch
            }
            val optimisticReaction = MessageReaction(
                emoji = emoji,
                reactorName = "You",
                fromMe = true,
                timestampMs = System.currentTimeMillis(),
            )
            val cur = _messages.value
            val idx = cur.indexOfFirst { it.id == target.id }
            if (idx >= 0 && cur[idx].reactions.none { it.fromMe && it.emoji == emoji }) {
                setMessages(cur.toMutableList().also {
                    it[idx] = cur[idx].copy(
                        reactions = (cur[idx].reactions + optimisticReaction)
                            .sortedBy { r -> r.timestampMs },
                    )
                })
            }
            when (val outcome = repository.sendRcsReaction(serverId, emoji)) {
                is UnifiedInboxRepository.RcsSendOutcome.Sent -> {
                    // The server echo (message re-push with reactions
                    // populated) reconciles the optimistic badge.
                }
                is UnifiedInboxRepository.RcsSendOutcome.Failed -> {
                    val cur2 = _messages.value
                    val idx2 = cur2.indexOfFirst { it.id == target.id }
                    if (idx2 >= 0) {
                        setMessages(cur2.toMutableList().also {
                            it[idx2] = cur2[idx2].copy(
                                reactions = cur2[idx2].reactions.filterNot {
                                    it.fromMe && it.emoji == emoji &&
                                        it.timestampMs == optimisticReaction.timestampMs
                                },
                            )
                        })
                    }
                    _sendError.value = outcome.reason
                }
            }
        }
    }

    private suspend fun sendSms(recipient: String, body: String) {
        when (val result = repository.smsTransport.sendMessage(recipient, body)) {
            is SendResult.Sent -> {
                // Optimistic insert so the bubble appears instantly —
                // newest first, so it lands at the bottom of the thread.
                val optimistic = Message(
                    id = System.currentTimeMillis(),
                    threadId = activeThreadId,
                    address = recipient,
                    body = body,
                    date = System.currentTimeMillis(),
                    isOutgoing = true,
                    isMms = false,
                    read = true,
                    deliveryStatus = DeliveryStatus.SENT,
                )
                setMessages((_messages.value + optimistic).sortedByDescending { it.date })
                // Dispatched — composer sending state resets at the
                // insert, not at coroutine end (see sendRcs).
                _sending.value = false
                repository.refresh()
                // A brand-new conversation has no thread yet; bind to the
                // real one once the provider knows about it.
                if (activeThreadId < 0) {
                    val found = repository.findThreadId(recipient)
                    if (found >= 0) {
                        activeThreadId = found
                        reload()
                    }
                }
            }
            is SendResult.Failed -> _sendError.value = result.reason
            is SendResult.Unavailable -> _sendError.value = result.reason
        }
    }

    /** Downloads + decrypts an RCS media attachment for bubble rendering. */
    suspend fun mediaBytes(
        att: com.garrietta.relay.rcs.proto.RcsMediaAttachment,
    ): ByteArray = repository.mediaBytes(att)

    // ---- Voice-note transcription (Garrett 2026-10-20, feature queue
    // 7/7): provider choice from Settings → AI (On-device / Gemini /
    // Off); transcripts persist in TranscriptStore keyed by message, so
    // they survive reloads, cache evictions and history-cache version
    // bumps. Receiving-side only: the composer has no voice recorder
    // today (the mic button is a stub), so there is nothing of ours to
    // transcribe at send time.

    /** The active transcription provider (Settings → AI). */
    val transcriptionProvider:
        StateFlow<com.garrietta.relay.data.repo.TranscriptionProvider> =
        aiSettingsStore.provider

    /** Stored transcripts by message key (see transcriptKeyFor). */
    val transcripts:
        StateFlow<Map<String, com.garrietta.relay.data.repo.TranscriptEntry>> =
        transcriptStore.transcripts

    /** Message keys with a transcription currently in flight. */
    private val _transcribingKeys = MutableStateFlow<Set<String>>(emptySet())
    val transcribingKeys: StateFlow<Set<String>> = _transcribingKeys

    /**
     * One-shot transcription outcome for the snackbar (same pattern as
     * [forwardResult]): the verbatim failure, or the plain no-key
     * explanation. Success is silent — the transcript itself appears
     * under the bubble.
     */
    private val _transcriptNotice = MutableStateFlow<String?>(null)
    val transcriptNotice: StateFlow<String?> = _transcriptNotice

    fun clearTranscriptNotice() {
        _transcriptNotice.value = null
    }

    /**
     * Transcribes one voice note with the provider picked in Settings.
     * No-ops when transcription is Off (the UI hides the action then)
     * or when this message is already in flight.
     */
    fun transcribe(message: Message) {
        val att = message.media ?: return
        if (!att.isAudio) return
        val provider = aiSettingsStore.provider.value
        if (provider == com.garrietta.relay.data.repo.TranscriptionProvider.OFF) return
        val key = com.garrietta.relay.data.repo.transcriptKeyFor(message)
        if (key in _transcribingKeys.value) return
        viewModelScope.launch {
            _transcribingKeys.value = _transcribingKeys.value + key
            try {
                val bytes = repository.mediaBytes(att)
                val stagingDir = java.io.File(appContext.cacheDir, "transcribe")
                val text = when (provider) {
                    com.garrietta.relay.data.repo.TranscriptionProvider.GEMINI -> {
                        val apiKey = aiSettingsStore.apiKey.value
                            ?: throw IllegalStateException(
                                "No Gemini API key is set. Add yours in " +
                                    "Settings → AI — Relay never ships one, " +
                                    "and the key stays on this phone.",
                            )
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.garrietta.relay.data.transcription.GeminiTranscriber
                                .transcribe(apiKey, bytes, att.mimeType, stagingDir)
                        }
                    }
                    com.garrietta.relay.data.repo.TranscriptionProvider.ON_DEVICE ->
                        com.garrietta.relay.data.transcription.OnDeviceTranscriber
                            .transcribe(appContext, bytes, att.mimeType, stagingDir)
                    com.garrietta.relay.data.repo.TranscriptionProvider.OFF ->
                        return@launch
                }
                transcriptStore.putTranscript(key, text, provider)
            } catch (e: Exception) {
                _transcriptNotice.value =
                    e.message ?: "Transcription failed: ${e.javaClass.simpleName}"
            } finally {
                _transcribingKeys.value = _transcribingKeys.value - key
            }
        }
    }

    fun clearError() {
        _sendError.value = null
    }
}

/** The standard reaction set (Google Messages' picker). Shared with the
 * chat UI's long-press reaction picker. */
val ReactionEmojis = listOf("❤️", "👍", "👎", "😂", "😮", "😢", "😡")

/** iPhone tapback verb forms → emoji, as Google Messages renders them. */
private val TapbackVerbs = linkedMapOf(
    "Loved" to "❤️",
    "Liked" to "👍",
    "Disliked" to "👎",
    "Laughed at" to "😂",
    "Emphasized" to "‼️",
    "Questioned" to "❓",
)

/**
 * Parses a reaction message body into (emoji, quoted snippet), or null when
 * the body is not a reaction. The paired web protocol delivers reactions as
 * formatted text messages: `{emoji} to "{snippet}"`; iPhone tapbacks arriving
 * over SMS/MMS use verb forms (`Loved "..."`, `Laughed at "..."`) with either
 * straight or curly quotes. The snippet is the START of the reacted-to
 * message body, truncated with an ellipsis when long.
 */
fun parseReaction(body: String): Pair<String, String>? {
    val t = body.trim()
    // NORMALIZE (Garrett 2026-10-19 21:38: reactions still not rendering):
    // the wire sends the heart both as U+2764 U+FE0F ("❤️", what's in
    // ReactionEmojis) and as BARE U+2764 — and the old matcher compared the
    // raw body, so the bare form never matched and no badge ever appeared.
    // Strip variation selectors (U+FE0F) from BOTH sides before matching,
    // and accept straight or curly quotes around the snippet.
    val norm = t.replace("\uFE0F", "")
    for (emoji in ReactionEmojis) {
        val normEmoji = emoji.replace("\uFE0F", "")
        for (open in listOf("\"", "“")) {
            val close = if (open == "\"") "\"" else "”"
            val prefix = "$normEmoji to $open"
            if (norm.startsWith(prefix) && norm.endsWith(close) && norm.length > prefix.length + 1) {
                return emoji to norm.removePrefix(prefix).dropLast(1)
            }
        }
    }
    for ((verb, emoji) in TapbackVerbs) {
        for (open in listOf("\"", "“")) {
            val close = if (open == "\"") "\"" else "”"
            val prefix = "$verb $open"
            if (norm.startsWith(prefix) && norm.endsWith(close) &&
                norm.length > prefix.length + 1
            ) {
                return emoji to norm.removePrefix(prefix).dropLast(1)
            }
        }
    }
    return null
}

/**
 * Consumes reaction text messages into [Message.reactions] on their targets
 * (Garrett 2026-10-19: inline emoji badges, Google Messages style, not
 * separate bubbles). The target is the most recent non-reaction message at
 * or before the reaction whose body starts with the quoted snippet (exact
 * match also accepted). A reaction with no match stays in the list as a
 * bubble — information is never silently dropped. [nameFor] resolves the
 * reactor's display name. Idempotent: re-running on an already-processed
 * list is a no-op.
 */
fun attachReactions(
    messages: List<Message>,
    nameFor: (Message) -> String,
): List<Message> {
    if (messages.isEmpty()) return messages
    val parsed = messages.mapNotNull { m -> parseReaction(m.body)?.let { m to it } }
    if (parsed.isEmpty()) return messages
    val consumed = mutableSetOf<Long>()
    val byTarget = mutableMapOf<Long, MutableList<MessageReaction>>()
    for ((msg, pr) in parsed) {
        val (emoji, quote) = pr
        val needle = quote.trimEnd().removeSuffix("…").removeSuffix("...").trimEnd()
        if (needle.isEmpty()) continue
        val target = messages
            .filter {
                it.id != msg.id && it.date <= msg.date && parseReaction(it.body) == null
            }
            .filter { it.body == needle || it.body.startsWith(needle) }
            .maxByOrNull { it.date }
        if (target != null) {
            consumed += msg.id
            byTarget.getOrPut(target.id) { mutableListOf() } += MessageReaction(
                emoji = emoji,
                reactorName = nameFor(msg),
                fromMe = msg.isOutgoing,
                timestampMs = msg.date,
            )
        }
    }
    if (consumed.isEmpty()) return messages
    return messages
        .filterNot { it.id in consumed }
        .map { m ->
            byTarget[m.id]?.let { rs ->
                val merged = (m.reactions + rs)
                    .distinctBy { r -> Triple(r.emoji, r.reactorName, r.fromMe) }
                m.copy(reactions = merged.sortedBy { it.timestampMs })
            } ?: m
        }
}

