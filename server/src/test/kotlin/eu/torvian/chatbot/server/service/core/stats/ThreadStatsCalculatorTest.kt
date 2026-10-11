package eu.torvian.chatbot.server.service.core.stats

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ThreadStatsCalculator].
 *
 * The fixtures are hand-built messages and tool calls, so every expected number is derived from the
 * documented semantics rather than from another implementation.
 */
class ThreadStatsCalculatorTest {

    private val sessionId = 1L
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /** Builds a user message of the session under test. */
    private fun userMessage(id: Long, parentId: Long?): ChatMessage.UserMessage = ChatMessage.UserMessage(
        id = id,
        sessionId = sessionId,
        content = "user $id",
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentId
    )

    /** Builds an assistant message of the session under test with the given measurements. */
    private fun assistantMessage(
        id: Long,
        parentId: Long?,
        responseDurationMs: Long? = null,
        usageStats: UsageStats? = null,
        isComplete: Boolean = true,
        reasoningItems: List<JsonObject>? = null
    ): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
        id = id,
        sessionId = sessionId,
        content = "assistant $id",
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentId,
        modelId = 1L,
        settingsId = 2L,
        reasoningItems = reasoningItems,
        isComplete = isComplete,
        usageStats = usageStats,
        responseDurationMs = responseDurationMs
    )

    /** Builds a tool call attributed to [messageId]. */
    private fun toolCall(
        id: Long,
        messageId: Long,
        status: ToolCallStatus = ToolCallStatus.SUCCESS,
        durationMs: Long? = null
    ): ToolCall = ToolCall(
        id = id,
        messageId = messageId,
        toolDefinitionId = null,
        toolName = "tool",
        status = status,
        executedAt = now,
        durationMs = durationMs
    )

    /** Builds reasoning items, treated by the statistics as opaque payloads. */
    private fun reasoningItems(count: Int): List<JsonObject> = List(count) { JsonObject(emptyMap()) }

    // --- Branch resolution ---

    @Test
    fun `measures only the branch of the given leaf`() {
        // 1 -> 2 -> 3 is the requested branch; 4 (child of 2) and 5 (child of 4) are a sibling branch.
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(2L, 1L, responseDurationMs = 1_000L),
            assistantMessage(3L, 2L, responseDurationMs = 3_000L),
            assistantMessage(4L, 2L, responseDurationMs = 9_000L),
            assistantMessage(5L, 4L, responseDurationMs = 9_000L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 3L, sessionToolCalls = emptyList())

        assertEquals(3, stats.totalMessages)
        assertEquals(1, stats.userMessages)
        assertEquals(2, stats.assistantMessages)
        assertEquals(4_000L, stats.responseDuration.totalMs)
    }

    @Test
    fun `excludes tool calls of messages outside the branch`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(2L, 1L),
            assistantMessage(3L, 2L),
            assistantMessage(4L, 2L)
        )
        val toolCalls = listOf(
            toolCall(id = 10L, messageId = 2L),
            toolCall(id = 11L, messageId = 4L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 3L, sessionToolCalls = toolCalls)

        assertEquals(1, stats.toolCalls)
    }

    @Test
    fun `null leaf yields an empty thread`() {
        val messages = listOf(userMessage(1L, null), assistantMessage(2L, 1L))

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = null, sessionToolCalls = emptyList())

        assertEquals(0, stats.totalMessages)
        assertNull(stats.responseDuration.totalMs)
        assertNull(stats.toolCallDuration.totalMs)
        assertNull(stats.responseDuration.averageMs)
        assertNull(stats.toolCallSuccessRate)
    }

    @Test
    fun `session without messages yields an empty thread`() {
        val stats = ThreadStatsCalculator.compute(emptyList(), currentLeafMessageId = 7L, sessionToolCalls = emptyList())

        assertEquals(0, stats.totalMessages)
        assertEquals(0, stats.userMessages)
        assertEquals(0, stats.assistantMessages)
        assertEquals(0, stats.toolCalls)
        assertEquals(0, stats.reasoningItems)
        assertNull(stats.inputTokens)
        assertNull(stats.outputTokens)
        assertNull(stats.reasoningTokens)
        assertNull(stats.cachedInputTokens)
        assertNull(stats.cost)
        assertNull(stats.toolCallDuration.totalMs)
        assertTrue(stats.responseDuration.outliers.isEmpty())
        assertTrue(stats.toolCallDuration.outliers.isEmpty())
        assertNull(stats.responseDuration.standardDeviationMs)
        assertNull(stats.toolCallDuration.averageMs)
        assertNull(stats.assistantMessageSuccessRate)
        assertNull(stats.averageOutputTokensPerSecond)
        assertNull(stats.inputCacheHitRate)
    }

    @Test
    fun `missing leaf message fails loudly`() {
        val messages = listOf(userMessage(1L, null))

        assertFailsWith<IllegalStateException> {
            ThreadStatsCalculator.compute(messages, currentLeafMessageId = 42L, sessionToolCalls = emptyList())
        }
    }

    @Test
    fun `broken parent chain fails loudly`() {
        // Message 3 claims a parent that is not part of the session.
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(3L, 99L)
        )

        assertFailsWith<IllegalStateException> {
            ThreadStatsCalculator.compute(messages, currentLeafMessageId = 3L, sessionToolCalls = emptyList())
        }
    }

    @Test
    fun `cyclic parent chain fails loudly`() {
        val messages = listOf(
            userMessage(1L, 2L),
            assistantMessage(2L, 1L)
        )

        assertFailsWith<IllegalStateException> {
            ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())
        }
    }

    // --- Counts ---

    @Test
    fun `counts messages, tool calls and reasoning items of the branch`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(2L, 1L, reasoningItems = reasoningItems(3)),
            userMessage(3L, 2L),
            assistantMessage(4L, 3L, reasoningItems = null)
        )
        val toolCalls = listOf(
            toolCall(id = 10L, messageId = 2L),
            toolCall(id = 11L, messageId = 2L),
            toolCall(id = 12L, messageId = 4L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 4L, sessionToolCalls = toolCalls)

        assertEquals(4, stats.totalMessages)
        assertEquals(2, stats.userMessages)
        assertEquals(2, stats.assistantMessages)
        assertEquals(3, stats.toolCalls)
        assertEquals(3, stats.reasoningItems)
        assertEquals(stats.totalMessages, stats.userMessages + stats.assistantMessages)
    }

    // --- Tokens ---

    @Test
    fun `token totals include the first usage carrying message and skip unmeasured counters`() {
        val messages = listOf(
            userMessage(1L, null),
            // The first message with usage: excluded from the cache-hit rate only, not from the totals.
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(
                    inputTokens = 1_000,
                    outputTokens = 100,
                    totalTokens = 1_100,
                    reasoningTokens = null,
                    cachedTokens = null
                )
            ),
            assistantMessage(
                id = 3L,
                parentId = 2L,
                usageStats = UsageStats(
                    inputTokens = 200,
                    outputTokens = 50,
                    totalTokens = 250,
                    reasoningTokens = 30,
                    cachedTokens = 150
                )
            ),
            // No usage at all: contributes nothing to any total.
            assistantMessage(id = 4L, parentId = 3L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 4L, sessionToolCalls = emptyList())

        assertEquals(1_200L, stats.inputTokens)
        assertEquals(150L, stats.outputTokens)
        assertEquals(30L, stats.reasoningTokens)
        assertEquals(150L, stats.cachedInputTokens)
    }

    @Test
    fun `token totals are null when no message reports usage`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(id = 2L, parentId = 1L),
            userMessage(3L, parentId = 2L),
            assistantMessage(id = 4L, parentId = 3L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 4L, sessionToolCalls = emptyList())

        assertNull(stats.inputTokens)
        assertNull(stats.outputTokens)
        assertNull(stats.reasoningTokens)
        assertNull(stats.cachedInputTokens)
    }

    @Test
    fun `a total is null when only its own counter is unreported`() {
        val messages = listOf(
            userMessage(1L, null),
            // Reports input and output only.
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(inputTokens = 700, outputTokens = 70, totalTokens = 770)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertEquals(700L, stats.inputTokens)
        assertEquals(70L, stats.outputTokens)
        assertNull(stats.reasoningTokens)
        assertNull(stats.cachedInputTokens)
    }

    @Test
    fun `cost total sums the reported charges and skips messages without one`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(
                    inputTokens = 100,
                    outputTokens = 10,
                    totalTokens = 110,
                    cost = 0.0002466
                )
            ),
            // Reports counters but no charge: contributes nothing to the cost total.
            assistantMessage(
                id = 3L,
                parentId = 2L,
                usageStats = UsageStats(inputTokens = 200, outputTokens = 20, totalTokens = 220)
            ),
            assistantMessage(
                id = 4L,
                parentId = 3L,
                usageStats = UsageStats(
                    inputTokens = 300,
                    outputTokens = 30,
                    totalTokens = 330,
                    cost = 0.95
                )
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 4L, sessionToolCalls = emptyList())

        assertEquals(0.9502466, assertNotNull(stats.cost), 1e-12)
    }

    @Test
    fun `cost total is null when no message reports a charge`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(inputTokens = 100, outputTokens = 10, totalTokens = 110)
            ),
            assistantMessage(id = 3L, parentId = 2L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 3L, sessionToolCalls = emptyList())

        assertNull(stats.cost, "A branch that reports no charge has no total, not a zero total")
        assertEquals(100L, stats.inputTokens, "The token totals are unaffected")
    }

    @Test
    fun `a charge reported as zero sums to zero`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(
                    inputTokens = 100,
                    outputTokens = 10,
                    totalTokens = 110,
                    cost = 0.0
                )
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertEquals(0.0, stats.cost, "A reported zero is measured data, not an absent charge")
    }

    @Test
    fun `a counter reported as zero everywhere sums to zero`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0,
                    reasoningTokens = 0,
                    cachedTokens = 0
                )
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        // A reported zero is a measurement, so it must not be conflated with an absent counter.
        assertEquals(0L, stats.inputTokens)
        assertEquals(0L, stats.outputTokens)
        assertEquals(0L, stats.reasoningTokens)
        assertEquals(0L, stats.cachedInputTokens)
    }

    // --- Durations ---

    @Test
    fun `response durations use the arithmetic mean and the population standard deviation`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(2L, 1L, responseDurationMs = 1_000L),
            assistantMessage(3L, 2L, responseDurationMs = 2_000L),
            assistantMessage(4L, 3L, responseDurationMs = 3_000L),
            // No measurement: excluded from the total, mean and deviation.
            assistantMessage(5L, 4L)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 5L, sessionToolCalls = emptyList())

        assertEquals(6_000L, stats.responseDuration.totalMs)
        assertEquals(2_000.0, stats.responseDuration.averageMs!!, 1e-9)
        assertEquals(816.496580927726, stats.responseDuration.standardDeviationMs!!, 1e-9)
    }

    @Test
    fun `a single measurement has a zero standard deviation`() {
        val messages = listOf(assistantMessage(2L, null, responseDurationMs = 500L))

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertEquals(500L, stats.responseDuration.totalMs)
        assertEquals(500.0, stats.responseDuration.averageMs!!, 1e-9)
        assertEquals(0.0, stats.responseDuration.standardDeviationMs!!, 1e-9)
        assertTrue(stats.responseDuration.outliers.isEmpty())
    }

    @Test
    fun `a family without any measurement reports a null total`() {
        // Both families have members, but none of them carries a measurement.
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(2L, 1L),
            assistantMessage(3L, 2L)
        )
        val toolCalls = listOf(toolCall(id = 10L, messageId = 3L, durationMs = null))

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 3L, sessionToolCalls = toolCalls)

        assertNull(stats.responseDuration.totalMs)
        assertNull(stats.toolCallDuration.totalMs)
    }

    @Test
    fun `a measured zero duration counts as a measurement`() {
        val messages = listOf(assistantMessage(2L, null, responseDurationMs = 0L))

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertEquals(0L, stats.responseDuration.totalMs)
        assertEquals(0.0, stats.responseDuration.averageMs!!, 1e-9)
        assertNull(stats.averageOutputTokensPerSecond)
    }

    // --- Outliers ---

    @Test
    fun `exactly the durations above mean plus two sigma are outliers`() {
        // 100 durations spread over 0..99 plus two at 200: only the two extremes cross the threshold.
        val bulk = (1L..100L).map { id ->
            assistantMessage(id = id, parentId = id - 1, responseDurationMs = id - 1)
        }
        val extremes = listOf(
            assistantMessage(101L, 100L, responseDurationMs = 200L),
            assistantMessage(102L, 101L, responseDurationMs = 200L)
        )
        val messages = listOf(userMessage(0L, null)) + bulk + extremes

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 102L, sessionToolCalls = emptyList())

        assertEquals(5_350.0 / 102.0, stats.responseDuration.averageMs!!, 1e-9)
        assertEquals(35.38821878041612, stats.responseDuration.standardDeviationMs!!, 1e-9)
        assertEquals(setOf(101L, 102L), stats.responseDuration.outliers.toSet())
    }

    @Test
    fun `outliers are the longest ones, descending and capped at ten`() {
        // 100 short measurements plus twelve long ones, all twelve above mean + 2 sigma.
        val bulk = (1L..100L).map { id ->
            assistantMessage(id = id, parentId = if (id == 1L) null else id - 1, responseDurationMs = 1L)
        }
        val long = (0 until 12).map { index ->
            val id = 101L + index
            assistantMessage(id = id, parentId = id - 1, responseDurationMs = 3_900L + index * 100L)
        }

        val stats = ThreadStatsCalculator.compute(bulk + long, currentLeafMessageId = 112L, sessionToolCalls = emptyList())

        assertEquals(
            (112L downTo 103L).toList(),
            stats.responseDuration.outliers,
            "the ten longest measurements, longest first; the two shortest are dropped"
        )
    }

    @Test
    fun `equal durations produce no outlier`() {
        val messages = (1L..5L).map { id ->
            assistantMessage(id = id, parentId = if (id == 1L) null else id - 1, responseDurationMs = 1_000L)
        }

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 5L, sessionToolCalls = emptyList())

        assertEquals(0.0, stats.responseDuration.standardDeviationMs!!, 1e-9)
        assertTrue(stats.responseDuration.outliers.isEmpty())
    }

    @Test
    fun `tool call outliers are reported by tool call id`() {
        val messages = listOf(userMessage(1L, null), assistantMessage(2L, 1L))
        val shortCalls = (0 until 10).map { index ->
            toolCall(id = 501L + index, messageId = 2L, durationMs = index.toLong())
        }
        val slowCalls = listOf(
            toolCall(id = 511L, messageId = 2L, durationMs = 100L),
            toolCall(id = 512L, messageId = 2L, durationMs = 100L)
        )

        val stats = ThreadStatsCalculator.compute(
            messages,
            currentLeafMessageId = 2L,
            sessionToolCalls = shortCalls + slowCalls
        )

        assertEquals(12, stats.toolCalls)
        assertEquals(245L, stats.toolCallDuration.totalMs)
        assertEquals(20.416666666666668, stats.toolCallDuration.averageMs!!, 1e-9)
        assertEquals(35.68720203222562, stats.toolCallDuration.standardDeviationMs!!, 1e-9)
        assertEquals(setOf(511L, 512L), stats.toolCallDuration.outliers.toSet())
    }

    @Test
    fun `tool calls without a duration are excluded from the duration distribution`() {
        val messages = listOf(userMessage(1L, null), assistantMessage(2L, 1L))
        val toolCalls = listOf(
            toolCall(id = 10L, messageId = 2L, durationMs = 1_000L),
            toolCall(id = 11L, messageId = 2L, status = ToolCallStatus.PENDING, durationMs = null)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = toolCalls)

        assertEquals(2, stats.toolCalls)
        assertEquals(1_000L, stats.toolCallDuration.totalMs)
        assertEquals(1_000.0, stats.toolCallDuration.averageMs!!, 1e-9)
        assertEquals(0.0, stats.toolCallDuration.standardDeviationMs!!, 1e-9)
    }

    // --- Rates ---

    @Test
    fun `tool call success rate counts only successful calls`() {
        val messages = listOf(userMessage(1L, null), assistantMessage(2L, 1L))
        val toolCalls = listOf(
            toolCall(id = 10L, messageId = 2L, status = ToolCallStatus.SUCCESS),
            toolCall(id = 11L, messageId = 2L, status = ToolCallStatus.SUCCESS),
            toolCall(id = 12L, messageId = 2L, status = ToolCallStatus.ERROR),
            toolCall(id = 13L, messageId = 2L, status = ToolCallStatus.CANCELLED)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = toolCalls)

        assertEquals(0.5, stats.toolCallSuccessRate!!, 1e-9)
    }

    @Test
    fun `rates are null when their set is empty`() {
        val messages = listOf(userMessage(1L, null), assistantMessage(2L, 1L))

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertNull(stats.toolCallSuccessRate)
    }

    @Test
    fun `assistant success rate counts an in-flight message as unsuccessful`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(2L, 1L, isComplete = true),
            userMessage(3L, 2L),
            assistantMessage(4L, 3L, isComplete = false)
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 4L, sessionToolCalls = emptyList())

        assertEquals(0.5, stats.assistantMessageSuccessRate!!, 1e-9)
    }

    // --- Derived rates ---

    @Test
    fun `output tokens per second divides the output tokens by the measured seconds`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                responseDurationMs = 2_500L,
                usageStats = UsageStats(inputTokens = 10, outputTokens = 100, totalTokens = 110)
            ),
            assistantMessage(
                id = 3L,
                parentId = 2L,
                responseDurationMs = 2_500L,
                usageStats = UsageStats(inputTokens = 10, outputTokens = 100, totalTokens = 110)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 3L, sessionToolCalls = emptyList())

        // 200 output tokens over 5 measured seconds.
        assertEquals(40.0, stats.averageOutputTokensPerSecond!!, 1e-9)
    }

    @Test
    fun `output tokens per second is null without a measured response duration`() {
        val messages = listOf(
            assistantMessage(
                id = 2L,
                parentId = null,
                usageStats = UsageStats(inputTokens = 10, outputTokens = 100, totalTokens = 110)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertNull(stats.averageOutputTokensPerSecond)
    }

    @Test
    fun `output tokens per second is null when no output total was reported`() {
        val messages = listOf(assistantMessage(id = 2L, parentId = null, responseDurationMs = 1_000L))

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertEquals(1_000L, stats.responseDuration.totalMs)
        assertNull(stats.averageOutputTokensPerSecond)
    }

    @Test
    fun `output tokens per second is zero for a reported zero output total`() {
        val messages = listOf(
            assistantMessage(
                id = 2L,
                parentId = null,
                responseDurationMs = 1_000L,
                usageStats = UsageStats(inputTokens = 10, outputTokens = 0, totalTokens = 10)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertEquals(0.0, stats.averageOutputTokensPerSecond!!, 1e-9)
    }

    @Test
    fun `cache hit rate excludes the first usage carrying message and skips unreported caches`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(inputTokens = 1_000, outputTokens = 10, totalTokens = 1_010, cachedTokens = 500)
            ),
            assistantMessage(
                id = 3L,
                parentId = 2L,
                usageStats = UsageStats(inputTokens = 100, outputTokens = 10, totalTokens = 110, cachedTokens = 50)
            ),
            // Reports no cached counter: absent from the numerator, present in the denominator.
            assistantMessage(
                id = 4L,
                parentId = 3L,
                usageStats = UsageStats(inputTokens = 100, outputTokens = 10, totalTokens = 110)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 4L, sessionToolCalls = emptyList())

        assertEquals(0.25, stats.inputCacheHitRate!!, 1e-9)
    }

    @Test
    fun `cache hit rate is null when only one message carries usage`() {
        val messages = listOf(
            userMessage(1L, null),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(inputTokens = 1_000, outputTokens = 10, totalTokens = 1_010, cachedTokens = 500)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertNull(stats.inputCacheHitRate)
    }

    @Test
    fun `cache hit rate is null when the remaining messages report no input tokens`() {
        val messages = listOf(
            assistantMessage(
                id = 1L,
                parentId = null,
                usageStats = UsageStats(inputTokens = 100, outputTokens = 10, totalTokens = 110)
            ),
            assistantMessage(
                id = 2L,
                parentId = 1L,
                usageStats = UsageStats(inputTokens = 0, outputTokens = 10, totalTokens = 10, cachedTokens = 5)
            )
        )

        val stats = ThreadStatsCalculator.compute(messages, currentLeafMessageId = 2L, sessionToolCalls = emptyList())

        assertNull(stats.inputCacheHitRate)
    }
}
