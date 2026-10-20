package com.garrietta.relay.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garrietta.relay.data.model.Conversation
import com.garrietta.relay.data.model.allRcsIds
import com.garrietta.relay.data.model.isUnknownNumber
import com.garrietta.relay.data.repo.CategoryStore
import com.garrietta.relay.data.repo.ChatCategory
import com.garrietta.relay.data.repo.MuteStore
import com.garrietta.relay.data.repo.UnifiedInboxRepository
import com.garrietta.relay.data.repo.typingLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(
    private val repository: UnifiedInboxRepository,
    private val categoryStore: CategoryStore,
    private val muteStore: MuteStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val categories: StateFlow<List<ChatCategory>> = categoryStore.categories
    val selectedCategoryId: StateFlow<String?> = categoryStore.selectedCategoryId
    val unknownEnabled: StateFlow<Boolean> = categoryStore.unknownEnabled
    val unreadEnabled: StateFlow<Boolean> = categoryStore.unreadEnabled

    /**
     * Conversations with notifications truly muted (Garrett 2026-10-20).
     * The inbox row shows a bell-off indicator for these; suppression
     * itself happens in the notification pipeline (see MuteStore).
     */
    val mutedKeys: StateFlow<Set<String>> = muteStore.mutedKeys

    fun isMuted(conversation: Conversation): Boolean {
        // One conversation, one mute key: the row's own server id for
        // RCS rows, its own thread key(s) for Telephony rows.
        val rcsIds = conversation.allRcsIds()
        if (rcsIds.isNotEmpty()) return rcsIds.any { muteStore.isMuted(it) }
        val ids = conversation.threadIds.ifEmpty { listOf(conversation.threadId) }
        return ids.any {
            muteStore.isMuted(CategoryStore.conversationKey(null, it))
        }
    }

    val conversations: StateFlow<List<Conversation>> =
        combine(
            repository.conversations,
            _query,
            categoryStore.assignments,
            categoryStore.selectedCategoryId,
            categoryStore.unknownEnabled,
        ) { list, q, assignments, selectedId, unknownOn ->
            var filtered = list
            // Category filter. "All" (null) shows everything EXCEPT unknown
            // numbers while the Unknown tab is on; the Unknown tab shows
            // ONLY bare-number threads; a custom category shows its assigned
            // chats — but auto-classification wins, so unknown threads never
            // leak into All or into a custom category while the toggle is on.
            // Stale assignments (to a deleted category) match nothing.
            // The built-in Unread tab is a FILTER VIEW, not a partition
            // (Garrett 2026-10-20): it shows only conversations with unread
            // messages — each row's own unread flag, nothing combined
            // across conversations — and muted chats still count (mute
            // silences notifications, not read state).
            // Turning Unread on never changes what All shows. Auto-
            // classification still wins inside it, exactly as in All and
            // custom categories: while the Unknown tab is on, unknown
            // threads live under Unknown, not here. Membership is live —
            // this combine re-runs on every repository emission, so a
            // chat joins/leaves the moment its read state flips.
            filtered = when {
                selectedId == CategoryStore.UNREAD_ID ->
                    filtered.filter {
                        it.unread && !(unknownOn && it.isUnknownNumber())
                    }
                unknownOn && selectedId == CategoryStore.UNKNOWN_ID ->
                    filtered.filter { it.isUnknownNumber() }
                selectedId != null ->
                    filtered.filter { conv ->
                        val key = CategoryStore.conversationKey(conv.rcsConversationId, conv.threadId)
                        assignments[key] == selectedId &&
                            !(unknownOn && conv.isUnknownNumber())
                    }
                unknownOn -> filtered.filterNot { it.isUnknownNumber() }
                else -> filtered
            }
            if (q.isBlank()) filtered
            else filtered.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.address.contains(q, ignoreCase = true) ||
                    it.snippet.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Inbox typing labels (Garrett 2026-10-20): threadId -> "{FirstName}
     * is typing..." for every conversation with an active typist in the
     * transport's ONE typing store (the same store the in-chat indicator
     * reads). The typist is resolved from the wire's TypingData number
     * against the row's own participants. Display-only — derived from
     * the unfiltered conversation list so a typing chat shows its label
     * in every category/search view, and typing never reorders rows or
     * touches read state.
     */
    val typingLabels: StateFlow<Map<Long, String>> =
        combine(
            repository.conversations,
            repository.rcsTransport.typingStates,
        ) { list, states ->
            if (states.isEmpty()) {
                emptyMap()
            } else {
                val byRcsId = list.mapNotNull { c ->
                    c.rcsConversationId?.let { it to c }
                }.toMap()
                val out = mutableMapOf<Long, String>()
                for ((rcsId, state) in states) {
                    val conv = byRcsId[rcsId] ?: continue
                    val label = typingLabel(conv.participants, state.typistNumber)
                    if (label != null) out[conv.threadId] = label
                }
                out
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Verbatim RCS sync errors (paired mode). Garrett has no logcat access,
     * so these surface exactly as reported — the inbox shows them.
     */
    val syncError: StateFlow<String?> = repository.rcsSyncErrors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun clearSyncError() = repository.clearRcsSyncError()

    /**
     * One-shot inbox scroll restore point (Garrett 2026-10-20): the inbox
     * list's scroll position (first visible index + offset) captured at
     * the moment a chat is opened. In single-pane the InboxScreen
     * composition is destroyed while a chat is open, so its LazyListState
     * dies with it and the list used to come back at the top. The state
     * is CONSTRUCTED from this point when the inbox is composed again, so
     * the list — and with it the avatar morph's target row — is at the
     * exact pre-chat position on the very first frame of the exit
     * transition: no jump, no re-scroll animation. One-shot: consumed by
     * the restore, and tagged with the category it belongs to so a stale
     * point can never land in a different category's list. Category
     * switches never write a point, so switching still starts at the top.
     */
    private var chatExitScroll: Triple<String?, Int, Int>? = null

    fun saveChatExitScroll(categoryId: String?, firstVisibleIndex: Int, firstVisibleOffset: Int) {
        chatExitScroll = Triple(categoryId, firstVisibleIndex, firstVisibleOffset)
    }

    fun consumeChatExitScroll(categoryId: String?): Pair<Int, Int>? {
        val saved = chatExitScroll ?: return null
        if (saved.first != categoryId) return null
        chatExitScroll = null
        return saved.second to saved.third
    }

    /**
     * The tap on a chat IS the read (Garrett 2026-10-20): the inbox's
     * open-chat funnel calls this at tap time so the repository flips
     * the row read locally (and latches it against stale refreshes)
     * in the same frame — the homepage never shows the just-read chat
     * as unread while the server mark-read propagates.
     */
    fun markOpened(conversation: Conversation) =
        repository.markConversationOpened(conversation)

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
