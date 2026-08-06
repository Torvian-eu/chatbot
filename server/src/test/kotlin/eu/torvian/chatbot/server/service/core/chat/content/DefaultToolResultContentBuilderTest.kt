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

    /**
     * Verifies an error tool call surfaces the captured output (e.g. run_command stdout/stderr)
     * alongside the concise error message, so the LLM can recover without a blind retry.
     */
    @Test
    fun `build includes output and errorCode for errored tool calls`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 3L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "run_command",
                toolCallId = "call-3",
                input = "{\"command\":\"ls\"}",
                output = "exitCode: 2\n--- stdout ---\n\n--- stderr ---\nNo such file or directory",
                status = ToolCallStatus.ERROR,
                errorMessage = "Command exited with code 2",
                errorCode = "EXECUTION_FAILED",
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals(
            "{\"error\":\"Command exited with code 2\",\"output\":\"exitCode: 2\\n--- stdout ---\\n\\n--- stderr ---\\nNo such file or directory\",\"errorCode\":\"EXECUTION_FAILED\"}",
            result
        )
    }

    /**
     * Verifies an error tool call with blank output does not emit a redundant output key,
     * preserving the legacy error-only contract.
     */
    @Test
    fun `build omits output key when errored output is blank`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 4L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "run_command",
                toolCallId = "call-4",
                input = null,
                output = "   ",
                status = ToolCallStatus.ERROR,
                errorMessage = "Command exited with code 1",
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals("{\"error\":\"Command exited with code 1\"}", result)
    }

    /**
     * Verifies an error tool call with accumulated validation errors surfaces
     * the errorDetails field so the LLM can see all validation issues at once.
     */
    @Test
    fun `build includes errorDetails for errored tool calls with validation errors`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 5L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "run_command",
                toolCallId = "call-5",
                input = null,
                output = null,
                status = ToolCallStatus.ERROR,
                errorMessage = "Input validation failed with 2 error(s):",
                errorCode = "invalid_input",
                errorDetails = "{\"validationErrors\":[\"The 'command' field must be a single executable name without spaces. Arguments should be provided in the 'args' field. Received: \\\"echo hello world\\\".\",\"timeout must be > 0 seconds\"]}",
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals(
            "{\"error\":\"Input validation failed with 2 error(s):\",\"errorCode\":\"invalid_input\",\"errorDetails\":{\"validationErrors\":[\"The 'command' field must be a single executable name without spaces. Arguments should be provided in the 'args' field. Received: \\\"echo hello world\\\".\",\"timeout must be > 0 seconds\"]}}",
            result
        )
    }

    /**
     * Verifies that tool output is preserved as a structured JSON object when it is valid JSON,
     * enabling the LLM to parse it programmatically.
     */
    @Test
    fun `build parses valid JSON output as structured object`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 6L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "read_text_file",
                toolCallId = "call-6",
                input = "{\"path\":\"test.kt\"}",
                output = "{\"content\":\"fun main() {}\",\"length\":12}",
                status = ToolCallStatus.SUCCESS,
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        // Output should be preserved as-is for successful calls
        assertEquals("{\"content\":\"fun main() {}\",\"length\":12}", result)
    }

    /**
     * Verifies that error output is preserved as a structured JSON object when it is valid JSON.
     */
    @Test
    fun `build parses valid JSON output as structured object in error case`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 7L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "some_tool",
                toolCallId = "call-7",
                input = null,
                output = "{\"structured\":\"error output\",\"details\":[\"a\",\"b\"]}",
                status = ToolCallStatus.ERROR,
                errorMessage = "Tool failed",
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        // Output should be parsed as JSON object when valid
        assertEquals(
            "{\"error\":\"Tool failed\",\"output\":{\"structured\":\"error output\",\"details\":[\"a\",\"b\"]}}",
            result
        )
    }

    /**
     * Verifies a provisional call that never produced a result is serialized like a cancelled call.
     */
    @Test
    fun `build treats provisional calls as cancelled without a result`() {
        val builder = DefaultToolResultContentBuilder()

        val result = builder.build(
            ToolCall(
                id = 8L,
                messageId = 10L,
                toolDefinitionId = 2L,
                toolName = "weather",
                toolCallId = "call-8",
                input = "{\"city\":\"Paris\"}",
                output = null,
                status = ToolCallStatus.PENDING,
                executedAt = Instant.fromEpochMilliseconds(1234L)
            )
        )

        assertEquals("{\"cancelled\":\"Tool call was cancelled before a result was produced.\"}", result)
    }
}