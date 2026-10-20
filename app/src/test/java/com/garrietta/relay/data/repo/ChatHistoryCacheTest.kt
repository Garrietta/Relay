package com.garrietta.relay.data.repo

import com.garrietta.relay.data.model.DeliveryStatus
import com.garrietta.relay.data.model.Message
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for the chat-history cache round-trip (Garrett
 * 2026-10-20). The load path matters for the send indicator: the
 * cache persists the delivery STATUS (so a delivered message keeps
 * its check offline) but must NEVER persist live-send provenance —
 * a cached message is by definition not a send happening right now,
 * and a resurrected flag would strand it on the spinner.
 * Pure JVM (temp dir). Run on CI via testDebugUnitTest.
 */
class ChatHistoryCacheTest {

    private fun msg(
        id: Long,
        status: DeliveryStatus,
        inFlight: Boolean = false,
        tmpId: String? = null,
    ) = Message(
        id = id,
        threadId = 7L,
        address = "+15551234567",
        body = "body-$id",
        date = 1_700_000_000_000L + id,
        isOutgoing = true,
        isMms = false,
        read = true,
        tmpId = tmpId,
        deliveryStatus = status,
        serverId = "server-$id",
        sendInFlight = inFlight,
    )

    @Test
    fun `delivery status round-trips but live-send provenance does not`() {
        val dir = Files.createTempDirectory("relay-cache-test").toFile()
        try {
            val cache = ChatHistoryCache(dir)
            val key = "conv-key"
            cache.write(
                key,
                listOf(
                    // Written mid-send (a write-through caught the
                    // list while this session's send was in flight).
                    msg(1, DeliveryStatus.SENT, inFlight = true, tmpId = "t-1"),
                    msg(2, DeliveryStatus.DELIVERED),
                    msg(3, DeliveryStatus.READ),
                ),
            )
            val back = cache.read(key)
            assertNotNull(back)
            back!!
            assertEquals(3, back.size)
            // Statuses survive — delivered/read evidence is honored
            // from the cache exactly as stored.
            assertEquals(DeliveryStatus.SENT, back[0].deliveryStatus)
            assertEquals(DeliveryStatus.DELIVERED, back[1].deliveryStatus)
            assertEquals(DeliveryStatus.READ, back[2].deliveryStatus)
            // Provenance never does: nothing read from disk is a
            // send happening right now.
            assertFalse(back[0].sendInFlight)
            assertFalse(back[1].sendInFlight)
            assertFalse(back[2].sendInFlight)
            // The rest of the identity survives for correlation.
            assertEquals("t-1", back[0].tmpId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing key reads null`() {
        val dir = Files.createTempDirectory("relay-cache-test").toFile()
        try {
            assertEquals(null, ChatHistoryCache(dir).read("nope"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
