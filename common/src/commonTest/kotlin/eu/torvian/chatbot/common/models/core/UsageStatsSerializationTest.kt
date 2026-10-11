package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the wire contract of the persisted usage statistics of an assistant message.
 *
 * The field is additive and nullable, so a payload produced before it existed must still decode as "no usage",
 * and an optional value the provider omitted must survive a round trip as absent rather than as zero.
 */
class UsageStatsSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val assistantWithoutUsage = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = 1L,
        content = "Answer",
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        modelId = 5L,
        settingsId = 6L
    )

    @Test
    fun `UsageStats round-trips with every counter`() {
        val usage = UsageStats(
            inputTokens = 120,
            outputTokens = 30,
            totalTokens = 150,
            reasoningTokens = 12,
            cachedTokens = 8,
            cacheWriteTokens = 4
        )

        val wire = json.encodeToString(UsageStats.serializer(), usage)

        // The stored/wire names are the Kotlin property names and the order is fixed, because the JSON object is
        // what the database column holds and what a client decodes.
        assertTrue(wire.startsWith("{\"inputTokens\":120,\"outputTokens\":30,\"totalTokens\":150"))
        assertEquals(usage, json.decodeFromString(UsageStats.serializer(), wire))
    }

    @Test
    fun `an omitted optional counter stays absent`() {
        val usage = UsageStats(inputTokens = 120, outputTokens = 30, totalTokens = 150)

        val wire = json.encodeToString(UsageStats.serializer(), usage)

        assertEquals(usage, json.decodeFromString(UsageStats.serializer(), wire))
        assertNull(usage.reasoningTokens)
        assertNull(usage.cachedTokens)
        assertNull(usage.cacheWriteTokens)
        assertNull(usage.cost)
    }

    @Test
    fun `a reported cost round-trips last in the payload`() {
        val usage = UsageStats(
            inputTokens = 120,
            outputTokens = 30,
            totalTokens = 150,
            cost = 0.95
        )

        val wire = json.encodeToString(UsageStats.serializer(), usage)

        assertTrue(wire.endsWith("\"cost\":0.95}"), "Cost is the trailing key: $wire")
        assertEquals(usage, json.decodeFromString(UsageStats.serializer(), wire))
    }

    @Test
    fun `a sub-cent cost round-trips exactly as the provider stated it`() {
        val usage = UsageStats(inputTokens = 120, outputTokens = 30, totalTokens = 150, cost = 0.0002466)

        val wire = json.encodeToString(UsageStats.serializer(), usage)

        val decoded = json.decodeFromString(UsageStats.serializer(), wire)
        assertEquals(
            0.0002466,
            decoded.cost,
            "The charge must survive storage without being rounded or rescaled: $wire"
        )
    }

    @Test
    fun `a stored payload without the cost key decodes as no cost`() {
        val payload = """
            {
              "inputTokens": 120,
              "outputTokens": 30,
              "totalTokens": 150,
              "reasoningTokens": 12
            }
        """.trimIndent()

        val decoded = json.decodeFromString(UsageStats.serializer(), payload)

        assertEquals(120, decoded.inputTokens)
        assertEquals(12, decoded.reasoningTokens)
        assertNull(decoded.cost, "A payload written before cost existed carries no charge")
    }

    @Test
    fun `an assistant payload with a cost round-trips it`() {
        val usage = UsageStats(
            inputTokens = 120,
            outputTokens = 30,
            totalTokens = 150,
            cost = 0.0002466
        )
        val message = assistantWithoutUsage.copy(usageStats = usage)

        val wire = json.encodeToString(ChatMessage.AssistantMessage.serializer(), message)

        assertEquals(0.0002466, json.decodeFromString(ChatMessage.AssistantMessage.serializer(), wire).usageStats?.cost)
    }

    @Test
    fun `an assistant payload without usage decodes as no usage`() {
        val wire = json.encodeToString(ChatMessage.AssistantMessage.serializer(), assistantWithoutUsage)

        val decoded = json.decodeFromString(ChatMessage.AssistantMessage.serializer(), wire)

        assertNull(decoded.usageStats, "A payload that predates the field carries no usage")
    }

    @Test
    fun `an assistant payload without the field at all decodes as no usage`() {
        val payload = """
            {
              "id": 2,
              "sessionId": 1,
              "content": "Answer",
              "createdAt": "2023-11-14T22:13:20Z",
              "updatedAt": "2023-11-14T22:13:20Z",
              "parentMessageId": null,
              "modelId": 5,
              "settingsId": 6
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ChatMessage.AssistantMessage.serializer(), payload)

        assertNull(decoded.usageStats)
    }

    @Test
    fun `an assistant payload with usage round-trips the counters`() {
        val usage = UsageStats(
            inputTokens = 120,
            outputTokens = 30,
            totalTokens = 150,
            reasoningTokens = 12,
            cachedTokens = 8,
            cacheWriteTokens = 4
        )
        val message = assistantWithoutUsage.copy(usageStats = usage)

        val wire = json.encodeToString(ChatMessage.AssistantMessage.serializer(), message)

        assertEquals(usage, json.decodeFromString(ChatMessage.AssistantMessage.serializer(), wire).usageStats)
    }
}
