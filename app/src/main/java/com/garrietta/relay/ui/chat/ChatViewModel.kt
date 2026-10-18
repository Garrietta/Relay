package com.garrietta.relay.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.Message
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.transport.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: UnifiedInboxRepository,
    private val threadId: Long,
    /** All provider threads merged into this conversation (see Screen.Chat). */
    private val threadIds: List<Long> = listOf(threadId),
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

    init {
        reload()
        viewModelScope.launch {
            repository.inboxChanges().collect { reload() }
        }
    }

    fun loadContactTheme(recipient: String) {
        viewModelScope.launch {
            val uri = repository.resolvePhotoUri(recipient) ?: return@launch
            _photoUri.value = uri
            _seedColor.value = repository.extractContactSeed(uri)
        }
    }

    fun reload() {
        viewModelScope.launch {
            // Merged conversations load the full history across thread_ids.
            _messages.value = repository.loadMessages(threadIds)
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

