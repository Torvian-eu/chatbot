package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the shared `conversation_compacted` wire contract: both chat event surfaces carry the same
 * bounded [CompactionCompletedPayload], use the pinned `eventType` string, and serialize/deserialize
 * with the default sealed-interface discriminator.
 */
class CompactionCompletedEventSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val payload = CompactionCompletedPayload(
        chunkId = 42L,
        sessionId = 7L,
        coveredMessageIds = listOf(10L, 11L),
        modelId = 1L,
        settingsId = 2L,
        providerId = 3L,
        modelName = "Model Name",
        settingsName = "Settings Name",
        providerName = "Provider Name",
        sourceTokenCount = 4_500L,
        resultTokenCount = 2_000L,
        summaryPreview = "A concise summary preview.",
        createdAt = 1_700_000_000_100L
    )

    @Test
    fun `non-streaming chat event uses the pinned event type and round-trips`() {
        val event = ChatEvent.CompactionCompleted(payload)
        assertEquals("conversation_compacted", event.eventType)

        val wire = json.encodeToString(ChatEvent.serializer(), event)
        assertTrue(wire.contains("\"eventType\":\"conversation_compacted\""))
        assertTrue(wire.contains("\"coveredMessageIds\":[10,11]"))

        val decoded = json.decodeFromString(ChatEvent.serializer(), wire)
        val roundTrip = assertIs<ChatEvent.CompactionCompleted>(decoded)
        assertEquals("conversation_compacted", roundTrip.eventType)
        assertEquals(payload, roundTrip.payload)
    }

    @Test
    fun `streaming chat event uses the pinned event type and round-trips`() {
        val event = ChatStreamEvent.CompactionCompleted(payload)
        assertEquals("conversation_compacted", event.eventType)

        val wire = json.encodeToString(ChatStreamEvent.serializer(), event)
        assertTrue(wire.contains("\"eventType\":\"conversation_compacted\""))
        assertTrue(wire.contains("\"summaryPreview\":\"A concise summary preview.\""))

        val decoded = json.decodeFromString(ChatStreamEvent.serializer(), wire)
        val roundTrip = assertIs<ChatStreamEvent.CompactionCompleted>(decoded)
        assertEquals("conversation_compacted", roundTrip.eventType)
        assertEquals(payload, roundTrip.payload)
    }

    @Test
    fun `preview bound constants are part of the shared wire contract`() {
        assertEquals(500, CompactionCompletedPayload.MAX_SUMMARY_PREVIEW_CHARS)
        // The marker must never be empty so truncation is always detectable.
        assertTrue(CompactionCompletedPayload.SUMMARY_PREVIEW_TRUNCATION_MARKER.isNotBlank())
    }
}