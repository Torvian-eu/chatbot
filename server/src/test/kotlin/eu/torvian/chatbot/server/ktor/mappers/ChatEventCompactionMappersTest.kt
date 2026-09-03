package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.CompactionCompletedPayload
import eu.torvian.chatbot.server.service.core.MessageEvent
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactedMessageCoverage
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.toCompactionCompletedPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the FR-12 public conversion path: internal `MessageEvent.CompactionCompleted` /
 * `MessageStreamEvent.CompactionCompleted` map to the shared `conversation_compacted` wire events on
 * both surfaces, the summary preview is bounded server-side, and the serialized wire shape carries
 * the pinned `eventType` and payload fields.
 */
class ChatEventCompactionMappersTest {

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val createdAt = 1_700_000_000_100L

    /** Chunk with a short summary: the preview must be passed through unchanged. */
    private fun shortChunk() = chunk(
        summary = "A concise summary of the conversation.",
        createdAt = createdAt
    )

    /** Chunk with an oversized summary: the preview must be truncated with the marker. */
    private fun longChunk() = chunk(
        summary = "x".repeat(CompactionCompletedPayload.MAX_SUMMARY_PREVIEW_CHARS + 100),
        createdAt = createdAt
    )

    private fun chunk(summary: String, createdAt: Long) = ConversationCompactionChunk(
        id = 42L,
        sessionId = 7L,
        summary = summary,
        modelId = 1L,
        settingsId = 2L,
        providerId = 3L,
        modelName = "Model Name",
        settingsName = "Settings Name",
        providerName = "Provider Name",
        instruction = "Summarize faithfully",
        thresholdTokens = 100_000L,
        sourceTokenCount = 4_500L,
        resultTokenCount = 2_000L,
        tokenCounterVersion = "test-v1",
        coverageCount = 2,
        createdAt = createdAt,
        coverage = listOf(
            CompactedMessageCoverage(ordinal = 0, messageId = 10L, observedUpdatedAt = t),
            CompactedMessageCoverage(ordinal = 1, messageId = 11L, observedUpdatedAt = t)
        )
    )

    @Test
    fun `message event compaction completed maps to chat event on the SSE non-streaming surface`() {
        val payload = shortChunk().toCompactionCompletedPayload()
        val chatEvent = MessageEvent.CompactionCompleted(payload).toChatEvent()

        val mapped = assertIs<ChatEvent.CompactionCompleted>(chatEvent)
        assertEquals("conversation_compacted", mapped.eventType)
        assertEquals(payload, mapped.payload)
    }

    @Test
    fun `message stream event compaction completed maps to chat stream event on the streaming surface`() {
        val payload = shortChunk().toCompactionCompletedPayload()
        val streamEvent = MessageStreamEvent.CompactionCompleted(payload).toChatStreamEvent()

        val mapped = assertIs<ChatStreamEvent.CompactionCompleted>(streamEvent)
        assertEquals("conversation_compacted", mapped.eventType)
        assertEquals(payload, mapped.payload)
    }

    @Test
    fun `payload derivation reduces the chunk to ordered message ids and provenance without the instruction`() {
        val payload = shortChunk().toCompactionCompletedPayload()

        assertEquals(42L, payload.chunkId)
        assertEquals(7L, payload.sessionId)
        assertEquals(listOf(10L, 11L), payload.coveredMessageIds)
        assertEquals(4_500L, payload.sourceTokenCount)
        assertEquals(2_000L, payload.resultTokenCount)
        // Provenance exposes ids plus immutable name snapshots.
        assertEquals(1L, payload.modelId)
        assertEquals(2L, payload.settingsId)
        assertEquals(3L, payload.providerId)
        assertEquals("Model Name", payload.modelName)
        assertEquals("Settings Name", payload.settingsName)
        assertEquals("Provider Name", payload.providerName)
        assertEquals(createdAt, payload.createdAt)
        // The compaction instruction must never leak into the wire payload.
        assertNull(payload.summaryPreview.takeIf { it.contains("Summarize faithfully") })
    }

    @Test
    fun `summary preview is bounded to the wire limit with a truncation marker`() {
        val payload = longChunk().toCompactionCompletedPayload()
        val preview = payload.summaryPreview

        assertEquals(
            CompactionCompletedPayload.MAX_SUMMARY_PREVIEW_CHARS +
                CompactionCompletedPayload.SUMMARY_PREVIEW_TRUNCATION_MARKER.length,
            preview.length
        )
        assertTrue(preview.endsWith(CompactionCompletedPayload.SUMMARY_PREVIEW_TRUNCATION_MARKER))
        // The preview is exactly the first 500 characters of the summary plus the marker.
        assertEquals("x".repeat(500) + CompactionCompletedPayload.SUMMARY_PREVIEW_TRUNCATION_MARKER, preview)
    }

    @Test
    fun `summary preview is passed through unchanged when within the bound`() {
        val payload = shortChunk().toCompactionCompletedPayload()
        assertEquals("A concise summary of the conversation.", payload.summaryPreview)
    }

    @Test
    fun `serialized chat event carries the pinned event type and payload fields`() {
        val payload = shortChunk().toCompactionCompletedPayload()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        val wire = json.encodeToString(
            ChatEvent.serializer(),
            ChatEvent.CompactionCompleted(payload)
        )
        // eventType appears on the wire as the SSE 'event' discriminator value.
        assertTrue(wire.contains("\"eventType\":\"conversation_compacted\""))
        assertTrue(wire.contains("\"chunkId\":42"))
        assertTrue(wire.contains("\"coveredMessageIds\":[10,11]"))
        assertTrue(wire.contains("\"sourceTokenCount\":4500"))
        assertTrue(wire.contains("\"resultTokenCount\":2000"))

        val decoded = json.decodeFromString(ChatEvent.serializer(), wire)
        val roundTrip = assertIs<ChatEvent.CompactionCompleted>(decoded)
        assertEquals(payload, roundTrip.payload)
        assertEquals("conversation_compacted", roundTrip.eventType)
    }

    @Test
    fun `serialized chat stream event carries the pinned event type and payload fields`() {
        val payload = shortChunk().toCompactionCompletedPayload()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        val wire = json.encodeToString(
            ChatStreamEvent.serializer(),
            ChatStreamEvent.CompactionCompleted(payload)
        )
        assertTrue(wire.contains("\"eventType\":\"conversation_compacted\""))
        assertTrue(wire.contains("\"modelName\":\"Model Name\""))

        val decoded = json.decodeFromString(ChatStreamEvent.serializer(), wire)
        val roundTrip = assertIs<ChatStreamEvent.CompactionCompleted>(decoded)
        assertEquals(payload, roundTrip.payload)
        assertEquals("conversation_compacted", roundTrip.eventType)
    }
}