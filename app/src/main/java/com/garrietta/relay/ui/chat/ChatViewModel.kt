package com.garrietta.relay.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.model.Participant
import com.garrietta.relay.data.repo.AppSeedStore
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

class ChatViewModel(
    private val repository: UnifiedInboxRepository,
    private val appSeedStore: AppSeedStore,
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

    private var activeThreadId: Long = threadId

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    /**
     * Long-press bubble selection (M3E shape-as-state: selected bubbles
     * square off). Tapping a bubble while any selection is active toggles it.
     */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds

    /**
     * Tracks whether this chat is the currently visible screen, so
     * MainActivity's notification collector can skip buzzing for the chat
     * Garrett is already looking at. Called from ChatScreen's
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

    /** Palette seed per participant (null = no photo) for per-sender bubbles. */
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
        viewModelScope.launch {
            _messages.value = repository.rcsMessages(conversationId)
            repository.markRcsRead(conversationId)
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
                seeds[key] = p.photoUri?.let { repository.extractContactSeed(it) }
            }
            _senderSeeds.value = seeds
        }
        viewModelScope.launch {
            repository.observeRcsMessages().collect { msg ->
                if (msg.conversationId == conversationId) onRcsIncoming(msg)
            }
        }
        if (address.isNotBlank()) loadContactTheme(address)
    }

    /**
     * Live RCS arrival. A server echo carries the optimistic bubble's tmpId
     * and replaces it; anything else is genuinely new — reload to stay
     * consistent rather than risking a duplicate.
     */
    private fun onRcsIncoming(msg: com.garrietta.relay.rcs.proto.RcsMessage) {
        val tmpId = msg.tmpId.ifBlank { null }
        val optimistic = tmpId?.let { pendingOptimistic.remove(it) }
        if (optimistic == null) {
            viewModelScope.launch {
                _messages.value = repository.rcsMessages(rcsConversationId ?: return@launch)
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
        )
        _messages.value = _messages.value
            .map { if (it.tmpId == tmpId) mapped else it }
            .sortedByDescending { it.date }
        viewModelScope.launch { repository.markRcsRead(rcsConversationId ?: return@launch) }
    }

    /** Resolves the participant who sent [address], or null when unknown. */
    fun senderFor(address: String): Participant? {
        val idx = participantIndex
        return idx[address] ?: idx[repository.normalizedAddress(address)]
    }

    /** Palette seed for the sender of [address] (null = no photo). */
    fun seedFor(address: String): androidx.compose.ui.graphics.Color? {
        val seeds = _senderSeeds.value
        return seeds[address] ?: seeds[repository.normalizedAddress(address)]
    }

    fun loadParticipants() {
        viewModelScope.launch {
            val parts = repository.getGroupParticipants(threadIds)
            _participants.value = parts
            val self = repository.ownAddress()
            _others.value = if (self == null) parts
                else parts.filterNot { repository.normalizedAddress(it.address) == self }
            participantIndex = parts.associateBy { repository.normalizedAddress(it.address) }
            val seeds = mutableMapOf<String, androidx.compose.ui.graphics.Color?>()
            for (p in parts) {
                val key = repository.normalizedAddress(p.address)
                seeds[key] = p.photoUri?.let { repository.extractContactSeed(it) }
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
        viewModelScope.launch {
            // Merged conversations load the full history across thread_ids.
            val loaded = repository.loadMessages(threadIds)
            _messages.value = loaded.ifEmpty {
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
            }
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
        )
        pendingOptimistic[tmpId] = optimistic
        // Newest first (reverseLayout list): the optimistic bubble lands at the bottom.
        _messages.value = (_messages.value + optimistic).sortedByDescending { it.date }
        when (val outcome = repository.sendRcs(conversationId, body, tmpId)) {
            is UnifiedInboxRepository.RcsSendOutcome.Sent -> {
                // The server echo (same tmpId) replaces the optimistic bubble.
            }
            is UnifiedInboxRepository.RcsSendOutcome.Failed -> {
                pendingOptimistic.remove(tmpId)
                _messages.value = _messages.value.filterNot { it.tmpId == tmpId }
                _sendError.value = outcome.reason
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
                )
                _messages.value = (_messages.value + optimistic).sortedByDescending { it.date }
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

    fun clearError() {
        _sendError.value = null
    }
}

