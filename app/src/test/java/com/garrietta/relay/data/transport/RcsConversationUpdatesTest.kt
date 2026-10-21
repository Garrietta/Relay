package com.garrietta.relay.data.transport

import com.garrietta.relay.rcs.proto.RcsConversation
import org.junit.Assert.assertEquals
import org.junit.Test

class RcsConversationUpdatesTest {

    @Test
    fun `conversation updates replace matching entries and preserve others`() {
        val existing = conversation("existing", timestamp = 10)
        val untouched = conversation("untouched", timestamp = 5)
        val updated = conversation("existing", timestamp = 20, snippet = "latest")
        val added = conversation("added", timestamp = 15)

        assertEquals(
            listOf(updated, added, untouched),
            mergeRcsConversationUpdates(listOf(existing, untouched), listOf(added, updated)),
        )
    }

    @Test
    fun `blank ids are ignored`() {
        val existing = conversation("existing", timestamp = 10)
        val invalid = conversation("", timestamp = 20)

        assertEquals(
            listOf(existing),
            mergeRcsConversationUpdates(listOf(existing), listOf(invalid)),
        )
    }

    private fun conversation(
        id: String,
        timestamp: Long,
        snippet: String = "",
    ) = RcsConversation(
        id = id,
        name = id,
        snippet = snippet,
        timestampMs = timestamp,
        unread = false,
        isGroup = false,
        type = 2,
        pinned = false,
    )
}
