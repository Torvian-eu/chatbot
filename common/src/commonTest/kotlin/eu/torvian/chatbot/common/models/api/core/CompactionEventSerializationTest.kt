package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the dedicated compaction wire vocabulary: each [CompactionEvent] variant round-trips under
 * its explicit discriminator, carries the pinned `eventType`, and never collides with the chat-turn
 * surfaces.
 */
class CompactionEventSerializationTest {

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
    fun `completed carries the shared payload under its own discriminator`() {
        val event = CompactionEvent.Completed(payload)
        assertEquals("conversation_compacted", event.eventType)

        val wire = json.encodeToString(CompactionEvent.serializer(), event)
        assertTrue(wire.contains("\"type\":\"compaction_completed\""))
        assertTrue(wire.contains("\"coveredMessageIds\":[10,11]"))

        val decoded = json.decodeFromString(CompactionEvent.serializer(), wire)
        val roundTrip = assertIs<CompactionEvent.Completed>(decoded)
        assertEquals(payload, roundTrip.payload)
        assertEquals("conversation_compacted", roundTrip.eventType)
    }

    @Test
    fun `skipped carries its reason under its own discriminator`() {
        val event = CompactionEvent.Skipped(CompactionSkipReason.ALREADY_COMPACTED)
        assertEquals("conversation_compaction_skipped", event.eventType)

        val wire = json.encodeToString(CompactionEvent.serializer(), event)
        assertTrue(wire.contains("\"type\":\"compaction_skipped\""))
        assertTrue(wire.contains("\"reason\":\"ALREADY_COMPACTED\""))

        val decoded = json.decodeFromString(CompactionEvent.serializer(), wire)
        assertEquals(event, assertIs<CompactionEvent.Skipped>(decoded))
    }

    @Test
    fun `error carries the mapped api error under its own discriminator`() {
        val event = CompactionEvent.ErrorOccurred(
            apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, "Compaction failed")
        )

        val wire = json.encodeToString(CompactionEvent.serializer(), event)
        assertTrue(wire.contains("\"type\":\"compaction_error\""))

        val decoded = json.decodeFromString(CompactionEvent.serializer(), wire)
        assertEquals(event, assertIs<CompactionEvent.ErrorOccurred>(decoded))
    }

    @Test
    fun `stream completed is the terminal marker under its own discriminator`() {
        val wire = json.encodeToString(CompactionEvent.serializer(), CompactionEvent.StreamCompleted)
        assertEquals("done", CompactionEvent.StreamCompleted.eventType)
        assertTrue(wire.contains("\"type\":\"compaction_finished\""))

        val decoded = json.decodeFromString(CompactionEvent.serializer(), wire)
        assertEquals(CompactionEvent.StreamCompleted, assertIs<CompactionEvent.StreamCompleted>(decoded))
    }

    @Test
    fun `every skip reason round-trips under the skipped discriminator`() {
        CompactionSkipReason.entries.forEach { reason ->
            val wire = json.encodeToString(CompactionEvent.serializer(), CompactionEvent.Skipped(reason))

            val decoded = json.decodeFromString(CompactionEvent.serializer(), wire)

            assertEquals(CompactionEvent.Skipped(reason), assertIs<CompactionEvent.Skipped>(decoded))
        }
    }

    @Test
    fun `each variant has a distinct discriminator`() {
        val events: List<CompactionEvent> = listOf(
            CompactionEvent.Completed(payload),
            CompactionEvent.Skipped(CompactionSkipReason.NOTHING_TO_COMPACT),
            CompactionEvent.ErrorOccurred(
                apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, "Compaction failed")
            ),
            CompactionEvent.StreamCompleted
        )

        val discriminators = events.map { event ->
            val element = json.parseToJsonElement(json.encodeToString(CompactionEvent.serializer(), event))
            (element as JsonObject)["type"]!!.jsonPrimitive.content
        }

        assertEquals(discriminators.size, discriminators.distinct().size)
    }
}
