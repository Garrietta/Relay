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

    /**
     * Verbatim RCS sync errors (paired mode). Garrett has no logcat access,
     * so these surface exactly as reported — the inbox shows them.
     */
    val syncError: StateFlow<String?> = repository.rcsSyncErrors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun clearSyncError() = repository.clearRcsSyncError()

    fun setQuery(q: String) {
        _query.value = q
    }

    fun refresh() = repository.refresh()

    fun markRead(threadIds: List<Long>) {
        viewModelScope.launch { repository.markThreadRead(threadIds) }
    }

    fun markUnread(threadIds: List<Long>) {
        viewModelScope.launch { repository.markThreadUnread(threadIds) }
    }

    fun delete(threadIds: List<Long>) {
        viewModelScope.launch { repository.deleteThread(threadIds) }
    }
}
