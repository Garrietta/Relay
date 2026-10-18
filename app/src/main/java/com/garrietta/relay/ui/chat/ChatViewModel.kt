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
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private var activeThreadId: Long = threadId

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    init {
        reload()
        viewModelScope.launch {
            repository.inboxChanges().collect { reload() }
        }
    }

    fun reload() {
        viewModelScope.launch {
            _messages.value = repository.loadMessages(activeThreadId)
            if (activeThreadId >= 0) repository.markThreadRead(activeThreadId)
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
