package com.garrietta.relay.ui

/** Simple back-stack navigation for Relay's three screens. */
sealed interface Screen {
    data object Inbox : Screen
    /**
     * threadIds: all provider threads merged into this conversation (1:1
     * threads split by the provider are merged by normalized address).
     * Defaults to the single thread for new conversations.
     */
    data class Chat(
        val threadId: Long,
        val address: String,
        val displayName: String,
        val threadIds: List<Long> = listOf(threadId),
        /** Transport of the conversation's latest message ("SMS"/"MMS"). */
        val transportLabel: String = "SMS",
        /** Server conversation id for RCS rows (paired mode); null = Telephony. */
        val rcsConversationId: String? = null,
    ) : Screen
    data object Settings : Screen
    data object NewConversation : Screen
    data object RcsPairing : Screen
    /**
     * Conversation details (Garrett 2026-10-19): tapping any chat header
     * opens it; tapping a sender's avatar next to their message opens it
     * focused on that person. [focusAddress]: null = conversation mode
     * (group member list / 1:1 peer); set = person mode for that
     * participant. Reuses the Chat screen's ViewModel via the same key.
     */
    data class ChatDetails(
        val threadId: Long,
        val threadIds: List<Long>,
        val address: String,
        val displayName: String,
        val transportLabel: String = "SMS",
        val rcsConversationId: String? = null,
        val focusAddress: String? = null,
    ) : Screen
}

