package com.garrietta.relay.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(
    private val repository: UnifiedInboxRepository,
    private val categoryStore: CategoryStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val categories: StateFlow<List<ChatCategory>> = categoryStore.categories
    val selectedCategoryId: StateFlow<String?> = categoryStore.selectedCategoryId

    val conversations: StateFlow<List<Conversation>> =
        combine(
            repository.conversations,
            _query,
            categoryStore.assignments,
            categoryStore.selectedCategoryId,
        ) { list, q, assignments, selectedId ->
            var filtered = list
            // Category filter: "All" (null) shows everything; a category
            // shows only chats assigned to it. Stale assignments (to a
            // deleted category) match nothing.
            if (selectedId != null) {
                filtered = filtered.filter { conv ->
                    assignments[CategoryStore.conversationKey(conv.rcsConversationId, conv.threadId)] == selectedId
                }
            }
            if (q.isBlank()) filtered
            else filtered.filter {
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

    fun selectCategory(id: String?) = categoryStore.selectCategory(id)

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
