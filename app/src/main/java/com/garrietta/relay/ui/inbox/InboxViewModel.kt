package com.garrietta.relay.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(private val repository: UnifiedInboxRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val conversations: StateFlow<List<Conversation>> =
        combine(repository.conversations, _query) { list, q ->
            if (q.isBlank()) list
            else list.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.address.contains(q, ignoreCase = true) ||
                    it.snippet.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun refresh() = repository.refresh()

    fun markRead(threadId: Long) {
        viewModelScope.launch { repository.markThreadRead(threadId) }
    }

    fun markUnread(threadId: Long) {
        viewModelScope.launch { repository.markThreadUnread(threadId) }
    }

    fun delete(threadId: Long) {
        viewModelScope.launch { repository.deleteThread(threadId) }
    }
}
