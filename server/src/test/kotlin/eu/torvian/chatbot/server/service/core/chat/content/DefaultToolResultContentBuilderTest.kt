package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies the legacy tool-result serialization contract exposed by [DefaultToolResultContentBuilder].
 */
class DefaultToolResultContentBuilderTest {
    /**
     * Verifies successful tool calls keep their original output when it is present.
     */
    @Test
    fun `build returns existing output for successful tool calls`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 1L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "weather",
                toolCallId = "call-1",
                input = "{\"city\":\"Paris\"}",
                output = "{\"temperature\":21}",
                status = ToolCallStatus.SUCCESS,
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals("{\"temperature\":21}", result)
    }

    /**
     * Verifies blank successful output falls back to an empty JSON object.
     */
    @Test
    fun `build returns empty object when successful output is blank`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 1L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "weather",
                toolCallId = "call-1",
                input = null,
                output = "   ",
                status = ToolCallStatus.SUCCESS,
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals("{}", result)
    }

    /**
     * Verifies error and user-denied outcomes are serialized with the historical fallback keys.
     */
    @Test
    fun `build serializes error and denial fallbacks`() {
        val builder = DefaultToolResultContentBuilder()

        val errorResult = builder.build(
            ToolCall(
                id = 1L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "weather",
                toolCallId = "call-1",
                input = null,
                output = null,
                status = ToolCallStatus.ERROR,
                errorMessage = null,
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )
        val deniedResult = builder.build(
            ToolCall(
                id = 2L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "weather",
                toolCallId = "call-2",
                input = null,
                output = null,
                status = ToolCallStatus.USER_DENIED,
                denialReason = null,
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals("{\"error\":\"Unknown error\"}", errorResult)
        assertEquals(
            "{\"user_denied\":\"Tool call was denied by user.\",\"reason\":\"No reason provided\"}",
            deniedResult
        )
    }
}