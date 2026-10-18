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
) : ViewModel() {

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

    /** Palette seed per participant (null = no photo) for per-sender bubbles. */
    private val _senderSeeds = MutableStateFlow<Map<String, androidx.compose.ui.graphics.Color?>>(emptyMap())
    val senderSeeds: StateFlow<Map<String, androidx.compose.ui.graphics.Color?>> = _senderSeeds

    private var participantIndex: Map<String, Participant> = emptyMap()

    init {
        reload()
        loadParticipants()
        viewModelScope.launch {
            repository.inboxChanges().collect { reload() }
        }
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
            when (val result = repository.smsTransport.sendMessage(recipient, body)) {
                is SendResult.Sent -> {
                    // Optimistic insert so the bubble appears instantly.
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
                    _messages.value = _messages.value + optimistic
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
            _sending.value = false
        }
    }

    fun clearError() {
        _sendError.value = null
    }
}

