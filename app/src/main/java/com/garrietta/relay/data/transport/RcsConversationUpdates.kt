package com.garrietta.relay.data.transport

import com.garrietta.relay.rcs.proto.RcsConversation

internal fun mergeRcsConversationUpdates(
    current: List<RcsConversation>,
    updates: List<RcsConversation>,
): List<RcsConversation> {
    val merged = LinkedHashMap<String, RcsConversation>()
    current.forEach { conversation ->
        if (conversation.id.isNotBlank()) merged[conversation.id] = conversation
    }
    updates.forEach { conversation ->
        if (conversation.id.isNotBlank()) merged[conversation.id] = conversation
    }
    return merged.values.sortedByDescending { it.timestampMs }
}
