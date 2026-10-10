package eu.torvian.chatbot.server.ktor.mappers

import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.matches
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactedMessageCoverage
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.ManualCompactionOutcome
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the outcome→wire mapping of the manual compaction socket: a persisted chunk becomes the
 * shared completed payload, a skip carries its reason, and every failure category becomes an error
 * event carrying the existing compaction or model-configuration API error code.
 */
class ToCompactionEventMapperTest {

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /** Persisted chunk the mapper must reduce to the bounded completed payload. */
    private val chunk = ConversationCompactionChunk(
        id = 42L,
        sessionId = 7L,
        summary = "A concise summary of the conversation.",
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
        createdAt = 1_700_000_000_100L,
        coverage = listOf(
            CompactedMessageCoverage(ordinal = 0, messageId = 10L, observedUpdatedAt = t),
            CompactedMessageCoverage(ordinal = 1, messageId = 11L, observedUpdatedAt = t)
        )
    )

    @Test
    fun `a persisted outcome maps to the completed event carrying the bounded payload`() {
        val event = ManualCompactionOutcome.Persisted(chunk).toCompactionEvent()

        val completed = assertIs<CompactionEvent.Completed>(event)
        assertEquals("conversation_compacted", completed.eventType)
        assertEquals(42L, completed.payload.chunkId)
        assertEquals(listOf(10L, 11L), completed.payload.coveredMessageIds)
        assertEquals(4_500L, completed.payload.sourceTokenCount)
        assertEquals(2_000L, completed.payload.resultTokenCount)
        // The compaction instruction must never leak into the wire payload.
        assertFalse(completed.payload.summaryPreview.contains("Summarize faithfully"))
    }

    @Test
    fun `a skipped outcome maps to the skipped event with its reason`() {
        val event = ManualCompactionOutcome.Skipped(CompactionSkipReason.ALREADY_COMPACTED).toCompactionEvent()

        val skipped = assertIs<CompactionEvent.Skipped>(event)
        assertEquals(CompactionSkipReason.ALREADY_COMPACTED, skipped.reason)
        assertEquals("conversation_compaction_skipped", skipped.eventType)
    }

    @Test
    fun `an unsupported configuration also maps to the model-configuration error code`() {
        val error = ConversationCompactionError.UnsupportedConfiguration("no strategy")

        val event = assertIs<CompactionEvent.ErrorOccurred>(error.toCompactionEvent())

        assertTrue(event.error.matches(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR))
        assertEquals("no strategy", event.error.message)
    }

    @Test
    fun `an invalid configuration maps to the model-configuration error code`() {
        val error = ConversationCompactionError.InvalidConfiguration("compaction disabled")

        val event = assertIs<CompactionEvent.ErrorOccurred>(error.toCompactionEvent())

        assertEquals(error.toApiError(), event.error)
        assertTrue(event.error.matches(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR))
        assertEquals("compaction disabled", event.error.message)
        assertEquals("error", event.eventType)
    }

    @Test
    fun `every other failure category maps to the compaction-failed error code`() {
        val errors = listOf(
            ConversationCompactionError.GenerationFailed("provider said no"),
            ConversationCompactionError.TimedOut,
            ConversationCompactionError.InvalidOutput("blank summary"),
            ConversationCompactionError.InsufficientReduction(4_500L, 2_000L, 1_000L),
            ConversationCompactionError.SourceChanged("thread changed"),
            ConversationCompactionError.PersistenceFailed("insert failed")
        )

        errors.forEach { error ->
            val event = assertIs<CompactionEvent.ErrorOccurred>(error.toCompactionEvent())
            assertTrue(event.error.matches(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED))
            assertTrue(event.error.message.isNotBlank())
        }
    }
}
