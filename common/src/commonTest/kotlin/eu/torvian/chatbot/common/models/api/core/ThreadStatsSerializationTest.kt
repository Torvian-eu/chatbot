package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the [ThreadStats] wire shape: the field names are the contract, an explicit `null` survives
 * a round trip instead of turning into a zero, and the outlier id list keeps its order.
 */
class ThreadStatsSerializationTest {

    private val json = Json

    private val populated = ThreadStats(
        totalMessages = 5,
        userMessages = 2,
        assistantMessages = 3,
        toolCalls = 4,
        reasoningItems = 3,
        inputTokens = 1_500L,
        outputTokens = 400L,
        reasoningTokens = 120L,
        cachedInputTokens = 900L,
        responseDuration = DurationStats(
            totalMs = 9_000L,
            averageMs = 3_000.0,
            standardDeviationMs = 1414.213562373095,
            outliers = listOf(7L, 3L)
        ),
        toolCallDuration = DurationStats(
            totalMs = 250L,
            averageMs = 62.5,
            standardDeviationMs = 0.0,
            outliers = emptyList()
        ),
        toolCallSuccessRate = 0.75,
        assistantMessageSuccessRate = 0.6666666666666666,
        averageOutputTokensPerSecond = 44.44444444444444,
        inputCacheHitRate = 0.987
    )

    @Test
    fun `populated stats round-trip unchanged`() {
        val wire = json.encodeToString(ThreadStats.serializer(), populated)

        assertEquals(populated, json.decodeFromString(ThreadStats.serializer(), wire))
    }

    @Test
    fun `empty thread keeps null totals and rates`() {
        val empty = ThreadStats(
            totalMessages = 0,
            userMessages = 0,
            assistantMessages = 0,
            toolCalls = 0,
            reasoningItems = 0,
            inputTokens = null,
            outputTokens = null,
            reasoningTokens = null,
            cachedInputTokens = null,
            responseDuration = DurationStats(
                totalMs = null,
                averageMs = null,
                standardDeviationMs = null,
                outliers = emptyList()
            ),
            toolCallDuration = DurationStats(
                totalMs = null,
                averageMs = null,
                standardDeviationMs = null,
                outliers = emptyList()
            ),
            toolCallSuccessRate = null,
            assistantMessageSuccessRate = null,
            averageOutputTokensPerSecond = null,
            inputCacheHitRate = null
        )

        val wire = json.encodeToString(ThreadStats.serializer(), empty)

        // A missing measurement must stay distinguishable from a measured zero on the wire.
        assertTrue(wire.contains("\"toolCallSuccessRate\":null"))
        assertTrue(wire.contains("\"inputCacheHitRate\":null"))
        assertTrue(wire.contains("\"inputTokens\":null"))
        assertTrue(wire.contains("\"outputTokens\":null"))
        assertTrue(wire.contains("\"reasoningTokens\":null"))
        assertTrue(wire.contains("\"cachedInputTokens\":null"))
        assertTrue(wire.contains("\"averageMs\":null"))
        assertTrue(wire.contains("\"totalMs\":null"))
        assertEquals(empty, json.decodeFromString(ThreadStats.serializer(), wire))
    }

    @Test
    fun `a token counter reported as zero survives as zero`() {
        val reportedZeros = populated.copy(
            inputTokens = 0L,
            outputTokens = 0L,
            reasoningTokens = 0L,
            cachedInputTokens = 0L
        )

        val wire = json.encodeToString(ThreadStats.serializer(), reportedZeros)
        val decoded = json.decodeFromString(ThreadStats.serializer(), wire)

        assertTrue(wire.contains("\"inputTokens\":0"))
        assertTrue(wire.contains("\"cachedInputTokens\":0"))
        assertEquals(0L, decoded.inputTokens)
        assertEquals(0L, decoded.outputTokens)
        assertEquals(0L, decoded.reasoningTokens)
        assertEquals(0L, decoded.cachedInputTokens)
    }

    @Test
    fun `a measured zero total survives the round trip`() {
        val measuredZero = populated.copy(
            responseDuration = DurationStats(
                totalMs = 0L,
                averageMs = 0.0,
                standardDeviationMs = 0.0,
                outliers = emptyList()
            )
        )

        val wire = json.encodeToString(ThreadStats.serializer(), measuredZero)

        assertTrue(wire.contains("\"totalMs\":0"))
        assertEquals(0L, json.decodeFromString(ThreadStats.serializer(), wire).responseDuration.totalMs)
    }

    @Test
    fun `explicit null rate decodes as null`() {
        val decoded = json.decodeFromString(
            ThreadStats.serializer(),
            """
            {
              "totalMessages": 1,
              "userMessages": 1,
              "assistantMessages": 0,
              "toolCalls": 0,
              "reasoningItems": 0,
              "inputTokens": null,
              "outputTokens": null,
              "reasoningTokens": null,
              "cachedInputTokens": null,
              "responseDuration": {"totalMs": null, "averageMs": null, "standardDeviationMs": null, "outliers": []},
              "toolCallDuration": {"totalMs": 0, "averageMs": null, "standardDeviationMs": null, "outliers": []},
              "toolCallSuccessRate": null,
              "assistantMessageSuccessRate": null,
              "averageOutputTokensPerSecond": null,
              "inputCacheHitRate": null
            }
            """.trimIndent()
        )

        assertNull(decoded.toolCallSuccessRate)
        assertNull(decoded.assistantMessageSuccessRate)
        assertNull(decoded.averageOutputTokensPerSecond)
        assertNull(decoded.inputCacheHitRate)
        // An unreported counter stays absent, while a measured zero stays a zero.
        assertNull(decoded.inputTokens)
        assertNull(decoded.outputTokens)
        assertNull(decoded.reasoningTokens)
        assertNull(decoded.cachedInputTokens)
        assertNull(decoded.responseDuration.totalMs)
        assertEquals(0L, decoded.toolCallDuration.totalMs)
    }

    @Test
    fun `outlier ids keep their order`() {
        val stats = populated.copy(
            responseDuration = populated.responseDuration.copy(outliers = listOf(9L, 4L, 2L))
        )

        val wire = json.encodeToString(ThreadStats.serializer(), stats)

        assertTrue(wire.contains("\"outliers\":[9,4,2]"))
        assertEquals(
            listOf(9L, 4L, 2L),
            json.decodeFromString(ThreadStats.serializer(), wire).responseDuration.outliers
        )
    }
}
