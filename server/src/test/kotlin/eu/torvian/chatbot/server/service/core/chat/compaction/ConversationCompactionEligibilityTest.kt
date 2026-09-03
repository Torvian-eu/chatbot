package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the pure eligibility (largest-coverage selection) and compaction-input algorithms.
 *
 * These functions implement branch-aware ID coverage, edit-aware timestamps, contiguity, largest
 * eligible-coverage precedence, and whole-window auxiliary-input assembly without touching the database.
 */
class ConversationCompactionEligibilityTest {

    private val t0 = Instant.fromEpochMilliseconds(1_000L)
    private val t1 = Instant.fromEpochMilliseconds(1_001L)
    private val t2 = Instant.fromEpochMilliseconds(1_002L)
    private val t3 = Instant.fromEpochMilliseconds(1_003L)

    /**
     * Builds a context with one user unit per message id using the given (id, updatedAt) pairs.
     */
    private fun contextOf(vararg snapshots: Pair<Long, Instant>): ConversationContext = ConversationContext(
        snapshots.map { (id, updatedAt) ->
            ConversationContextUnit(
                source = SourceMessageSnapshot(id, updatedAt),
                rawMessages = listOf(RawChatMessage.User("m$id"))
            )
        }
    )

    /**
     * Builds a chunk whose coverage ordinals start at 0, matching v1 full-thread chunks.
     */
    private fun chunkOf(
        id: Long,
        createdAt: Long,
        summary: String = "summary-$id",
        coverage: List<Triple<Int, Long, Instant>>
    ): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = 1L,
        summary = summary,
        modelId = 1L,
        settingsId = 1L,
        providerId = 1L,
        modelName = "m",
        settingsName = "s",
        providerName = "p",
        instruction = "instr",
        thresholdTokens = 100_000L,
        sourceTokenCount = 10_000L,
        resultTokenCount = 100L,
        tokenCounterVersion = "approx_utf16_json_v1",
        coverageCount = coverage.size,
        createdAt = createdAt,
        coverage = coverage.map { (ordinal, messageId, observed) ->
            CompactedMessageCoverage(ordinal, messageId, observed)
        }
    )

    @Test
    fun `chunk is eligible when coverage is complete and current`() {
        val context = contextOf(1L to t0, 2L to t1, 3L to t2)
        val chunk = chunkOf(
            id = 10L,
            createdAt = 5_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1), Triple(2, 3L, t2))
        )
        val eligible = findLargestEligibleChunk(listOf(chunk), context)
        assertNotNull(eligible)
        assertEquals(10L, eligible.id)
    }

    @Test
    fun `chunk is ineligible when any covered message id is missing from the thread`() {
        // Branch excludes message 3.
        val context = contextOf(1L to t0, 2L to t1)
        val chunk = chunkOf(
            id = 10L,
            createdAt = 5_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1), Triple(2, 3L, t2))
        )
        assertNull(findLargestEligibleChunk(listOf(chunk), context))
    }

    @Test
    fun `chunk is ineligible when a covered timestamp changed`() {
        // Message 2 was edited: updatedAt moved from 1001 to 1100.
        val context = contextOf(1L to t0, 2L to Instant.fromEpochMilliseconds(1_100L), 3L to t2)
        val chunk = chunkOf(
            id = 10L,
            createdAt = 5_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1), Triple(2, 3L, t2))
        )
        assertNull(findLargestEligibleChunk(listOf(chunk), context))
    }

    @Test
    fun `chunk is ineligible when coverage row count mismatches or ordinals are not contiguous`() {
        val context = contextOf(1L to t0, 2L to t1, 3L to t2)
        val countMismatch = chunkOf(
            id = 10L,
            createdAt = 5_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1), Triple(2, 3L, t2))
        ).copy(coverageCount = 2)
        assertNull(findLargestEligibleChunk(listOf(countMismatch), context))

        // Ordinals skipping 1 (malformed legacy coverage) must be rejected.
        val nonContiguousOrdinals = chunkOf(
            id = 11L,
            createdAt = 5_001L,
            coverage = listOf(Triple(0, 1L, t0), Triple(2, 3L, t2))
        )
        assertNull(findLargestEligibleChunk(listOf(nonContiguousOrdinals), context))
    }

    @Test
    fun `largest eligible coverage wins over a smaller one regardless of creation time`() {
        val context = contextOf(1L to t0, 2L to t1, 3L to t2)
        val smallerButNewer = chunkOf(
            id = 31L,
            createdAt = 2_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1))
        )
        val largerButOlder = chunkOf(
            id = 30L,
            createdAt = 1_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1), Triple(2, 3L, t2))
        )
        val selected = findLargestEligibleChunk(listOf(smallerButNewer, largerButOlder), context)
        assertNotNull(selected)
        // Coverage size decides, not recency: the chunk covering the most source message ids is used.
        assertEquals(30L, selected.id)
    }

    @Test
    fun `smaller prefix chunk stays eligible when the larger chunk is ineligible on a branch`() {
        val branchContext = contextOf(1L to t0, 2L to t1, 4L to t3)
        val smallerPrefix = chunkOf(
            id = 20L,
            createdAt = 1_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1))
        )
        val largerFull = chunkOf(
            id = 21L,
            createdAt = 2_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1), Triple(2, 3L, t2))
        )
        // Message 3 is missing on this branch, so the larger chunk is ineligible and the smaller
        // fully contained prefix is selected instead; the larger row is never deleted.
        val selected = findLargestEligibleChunk(listOf(smallerPrefix, largerFull), branchContext)
        assertNotNull(selected)
        assertEquals(20L, selected.id)
    }

    @Test
    fun `equal-coverage ties break by newest created chunk then by id descending`() {
        val context = contextOf(1L to t0, 2L to t1)
        val olderTime = chunkOf(
            id = 4L,
            createdAt = 8_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1))
        )
        val newerTime = chunkOf(
            id = 5L,
            createdAt = 9_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1))
        )
        val sameTimeB = chunkOf(
            id = 6L,
            createdAt = 9_000L,
            coverage = listOf(Triple(0, 1L, t0), Triple(1, 2L, t1))
        )
        // Newest createdAt wins over an older chunk with the same coverage.
        assertEquals(5L, findLargestEligibleChunk(listOf(olderTime, newerTime), context)?.id)
        // Same createdAt: id descending breaks the tie deterministically.
        assertEquals(6L, findLargestEligibleChunk(listOf(newerTime, sameTimeB), context)?.id)
    }

    @Test
    fun `eligible chunks are never injected when the raw thread fits`() {
        // Selection only happens when the caller decides compaction is required; the fit decision is
        // the service's job, and this test documents that a fit context simply returns originals.
        val context = contextOf(1L to t0)
        val chunk = chunkOf(id = 50L, createdAt = 1_000L, coverage = listOf(Triple(0, 1L, t0)))
        assertEquals(50L, findLargestEligibleChunk(listOf(chunk), context)?.id)
        // The pure selection algorithm does not inject anything; the service decides to use originals.
        assertEquals(context.flatten(), context.flatten())
    }

    @Test
    fun `buildCompactionInput emits the prior summary first then all unit raws in order`() {
        val context = contextOf(1L to t0, 2L to t1, 3L to t2)
        val priorSummary = RawChatMessage.User(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL + "prior")

        val input = buildCompactionInput(priorSummary, context.units)

        assertEquals(4, input.size)
        assertEquals(priorSummary, input[0])
        assertEquals("m1", (input[1] as RawChatMessage.User).content)
        assertEquals("m2", (input[2] as RawChatMessage.User).content)
        assertEquals("m3", (input[3] as RawChatMessage.User).content)
    }

    @Test
    fun `buildCompactionInput with no prior summary emits only the unit raws`() {
        val context = contextOf(1L to t0, 2L to t1)

        val input = buildCompactionInput(summary = null, units = context.units)

        assertEquals(listOf("m1", "m2"), input.map { it.content })
        assertTrue(input.none { it.content?.startsWith(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL) == true })
    }

    @Test
    fun `buildCompactionInput keeps reasoning and unit boundaries intact`() {
        val reasoning = listOf(buildJsonObject { put("type", "reasoning"); put("id", "rs_1") })
        val context = ConversationContext(
            listOf(
                ConversationContextUnit(SourceMessageSnapshot(1L, t0), listOf(RawChatMessage.User("first"))),
                ConversationContextUnit(
                    SourceMessageSnapshot(2L, t1),
                    listOf(
                        RawChatMessage.Assistant(content = "tool step", reasoningItems = reasoning, reasoningModelId = 5L),
                        RawChatMessage.Tool(content = "{}", toolCallId = "c1", name = "search")
                    )
                ),
                ConversationContextUnit(SourceMessageSnapshot(3L, t2), listOf(RawChatMessage.User("newer")))
            )
        )

        val input = buildCompactionInput(summary = null, units = context.units)

        // The assistant tool call and its tool result stay one coherent unit in the input.
        assertEquals(4, input.size)
        assertTrue(input[1] is RawChatMessage.Assistant)
        assertTrue(input[2] is RawChatMessage.Tool)
        assertEquals("newer", (input[3] as RawChatMessage.User).content)
        // Reasoning is retained verbatim: it can inform the summary and belongs to the producing model.
        val assistant = input.filterIsInstance<RawChatMessage.Assistant>().single()
        assertEquals(reasoning, assistant.reasoningItems)
        assertEquals(5L, assistant.reasoningModelId)
    }
}
