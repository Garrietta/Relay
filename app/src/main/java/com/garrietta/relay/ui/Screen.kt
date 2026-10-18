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
    ) : Screen
    data object Settings : Screen
    data object NewConversation : Screen
    data object RcsPairing : Screen
}

