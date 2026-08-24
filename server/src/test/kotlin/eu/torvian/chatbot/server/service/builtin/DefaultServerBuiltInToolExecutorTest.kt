package eu.torvian.chatbot.server.service.builtin

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [DefaultServerBuiltInToolExecutor].
 *
 * Covers only the executor's general contract: dispatch by the canonical
 * [ServerBuiltInToolDefinition.builtInToolName] (never the public LLM-emitted name), tool-input
 * parsing, and the mapping of a tool's result or failure into the terminal [ToolCall]. Tool-specific
 * behavior (parameter validation, ownership denials, output shapes, patch semantics) is tested per
 * tool in `eu.torvian.chatbot.server.service.builtin.tools`, so this suite uses a [StubTool] double
 * instead of the real implementations.
 */
class DefaultServerBuiltInToolExecutorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val userId = 7L

    private fun toolCall(
        id: Long = 1L,
        toolName: String = "stub_tool",
        input: String? = "{}"
    ): ToolCall = ToolCall(
        id = id,
        messageId = 100L,
        toolDefinitionId = 50L,
        toolName = toolName,
        toolCallId = "call-$id",
        input = input,
        output = null,
        status = ToolCallStatus.EXECUTING,
        errorMessage = null,
        denialReason = null,
        executedAt = now,
        durationMs = null
    )

    /**
     * Minimal [ServerBuiltInTool] double used to exercise the executor without any real tool logic.
     *
     * Records the arguments it was invoked with so tests can verify delegation; the caller supplies
     * the canned [Either] result via [result].
     */
    private class StubTool(
        override val name: String = "stub_tool",
        private val result: (Long, JsonObject) -> Either<ServerBuiltInToolHandlerError, String>
    ) : ServerBuiltInTool {

        override val description: String = "Stub tool for executor tests"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }

        /** User id passed to the most recent [execute] call. */
        var lastUserId: Long? = null

        /** Parsed input object passed to the most recent [execute] call. */
        var lastInput: JsonObject? = null

        override suspend fun execute(
            userId: Long,
            input: JsonObject
        ): Either<ServerBuiltInToolHandlerError, String> {
            lastUserId = userId
            lastInput = input
            return result(userId, input)
        }
    }

    /**
     * Builds a resolved [ServerBuiltInToolDefinition] carrying the canonical name that the executor
     * dispatches on.
     *
     * @param id Definition id.
     * @param name Public (possibly prefixed) name recorded from the LLM.
     * @param builtInToolName Canonical dispatch name.
     */
    private fun serverBuiltInToolDefinition(
        id: Long = 50L,
        name: String = "chatbot-stub_tool",
        builtInToolName: String = "stub_tool"
    ): ServerBuiltInToolDefinition = ServerBuiltInToolDefinition(
        id = id,
        name = name,
        description = "Stub tool definition",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { put("type", "object") },
        outputSchema = null,
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        userId = userId,
        builtInToolName = builtInToolName
    )

    private fun executor(tool: ServerBuiltInTool): DefaultServerBuiltInToolExecutor =
        DefaultServerBuiltInToolExecutor(
            json = json,
            tools = mapOf(tool.name to tool)
        )

    private fun assertErrorJson(toolCall: ToolCall, code: String) {
        assertEquals(ToolCallStatus.ERROR, toolCall.status)
        val message = toolCall.errorMessage.orEmpty()
        assertTrue(message.contains("\"error\":\"$code\""), "Expected error code '$code' in: $message")
    }

    // --- Dispatch / parsing ---

    @Test
    fun `unknown canonical name produces a terminal error listing supported canonical names`() = runTest {
        val builtExecutor = executor(StubTool { _, _ -> "unused".right() })

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(builtInToolName = "future_tool"),
            toolCall(toolName = "chatbot-future_tool")
        )

        assertEquals(ToolCallStatus.ERROR, result.status)
        assertTrue(result.errorMessage.orEmpty().contains("Unsupported server built-in tool"))
        // The unsupported canonical name is reported (not the public name).
        assertTrue(result.errorMessage.orEmpty().contains("future_tool"))
        // The supported set is listed so the LLM can pick a valid tool next time.
        assertTrue(result.errorMessage.orEmpty().contains("stub_tool"))
    }

    @Test
    fun `malformed input produces a terminal invalid-input error`() = runTest {
        val builtExecutor = executor(StubTool { _, _ -> "unused".right() })

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(),
            toolCall(input = "not json")
        )

        assertErrorJson(result, "invalid_input")
    }

    @Test
    fun `non-object input produces a terminal invalid-input error`() = runTest {
        val builtExecutor = executor(StubTool { _, _ -> "unused".right() })

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(),
            toolCall(input = "[1,2,3]")
        )

        assertErrorJson(result, "invalid_input")
    }

    @Test
    fun `delegates to the matching tool with the parsed input and user id`() = runTest {
        val stub = StubTool { _, _ -> """{"ok":true}""".right() }
        val builtExecutor = executor(stub)

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(),
            toolCall(input = """{"a":1}""")
        )

        assertEquals(userId, stub.lastUserId)
        assertEquals(buildJsonObject { put("a", 1) }, stub.lastInput)
        assertEquals(ToolCallStatus.SUCCESS, result.status)
    }

    @Test
    fun `dispatches by builtInToolName regardless of the public LLM-emitted name`() = runTest {
        // The registry is keyed by canonical name "stub_tool"; the LLM emitted a prefixed public
        // name that does not match any registry key. Dispatch must still find the handler.
        val stub = StubTool { _, _ -> """{"ok":true}""".right() }
        val builtExecutor = executor(stub)

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(name = "acme-stub_tool", builtInToolName = "stub_tool"),
            toolCall(toolName = "acme-stub_tool")
        )

        assertEquals(ToolCallStatus.SUCCESS, result.status)
        assertEquals(userId, stub.lastUserId)
        // The stub was invoked exactly once: dispatch found the handler via the canonical name
        // instead of falling into the unknown-name branch (lastUserId stays null if never called).
        assertNotNull(stub.lastUserId, "the stub tool should have been executed")
    }

    // --- Result mapping ---

    @Test
    fun `maps tool success to a terminal SUCCESS call`() = runTest {
        val builtExecutor = executor(StubTool { _, _ -> """{"ok":true}""".right() })

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(),
            toolCall()
        )

        assertEquals(ToolCallStatus.SUCCESS, result.status)
        assertEquals("""{"ok":true}""", result.output)
        assertNull(result.errorMessage)
        assertNotNull(result.durationMs, "duration should be recorded for successful calls")
    }

    @Test
    fun `maps tool failure to a terminal ERROR call with LLM-readable JSON`() = runTest {
        val builtExecutor = executor(
            StubTool { _, _ ->
                ServerBuiltInToolHandlerError.InvalidInput("role_id is required").left()
            }
        )

        val result = builtExecutor.executeTool(
            userId,
            serverBuiltInToolDefinition(),
            toolCall()
        )

        assertErrorJson(result, "invalid_input")
        assertTrue(result.errorMessage.orEmpty().contains("role_id is required"))
        assertNull(result.output)
    }
}
