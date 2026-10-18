package com.garrietta.relay.ui

/** Simple back-stack navigation for Relay's three screens. */
sealed interface Screen {
    data object Inbox : Screen
    data class Chat(val threadId: Long, val address: String, val displayName: String) : Screen
    data object Settings : Screen
    data object NewConversation : Screen
    data object RcsPairing : Screen
}

