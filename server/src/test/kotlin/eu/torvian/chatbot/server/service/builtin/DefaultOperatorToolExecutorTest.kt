package eu.torvian.chatbot.server.service.builtin

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.agent.AgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.error.agent.SpawnRequestBuildError
import eu.torvian.chatbot.server.service.core.toolcall.OperatorToolExecutionResult
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [DefaultOperatorToolExecutor].
 *
 * Verifies the supported-tool guard (only `spawn_agent` may be executed; anything else fails fast
 * with a readable tool error and never reaches the payload builder or the relay), and the happy path
 * (payload built, relay event emitted with the tool name, result awaited and mapped to the terminal
 * tool call).
 */
class DefaultOperatorToolExecutorTest {

    private val json = Json
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun toolCall(
        id: Long = 1L,
        toolName: String = OperatorToolCatalog.SPAWN_AGENT_NAME,
        input: String = """{"agent_role_name":"writer","prompt":"Write a summary"}"""
    ): ToolCall = ToolCall(
        id = id,
        messageId = 100L,
        toolDefinitionId = 9L,
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

    @Test
    fun `unsupported tool name fails immediately without payload build or relay`() = runTest {
        val builder = mockk<AgentSpawnRequestBuilder>()
        val executor = DefaultOperatorToolExecutor(builder, json)
        val unsupported = toolCall(toolName = "future_tool")

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            userId = 1L,
            requestingAgentRoleId = 1L,
            toolCall = unsupported,
            emitEvent = { event ->
                if (event is ToolCallExecutionEvent.OperatorToolExecutionRequested) relayed = event
            },
            operatorToolResultFlow = flowOf()
        )

        assertEquals(ToolCallStatus.ERROR, result.status)
        assertEquals(unsupported.id, result.id)
        val message = result.errorMessage.orEmpty()
        assertTrue(message.contains("Unsupported operator tool"))
        assertTrue(message.contains("future_tool"))
        assertTrue(message.contains(OperatorToolCatalog.SPAWN_AGENT_NAME))
        // No payload was built and no relay event was emitted for the unsupported name.
        coVerify(exactly = 0) { builder.build(any(), any(), any()) }
        assertEquals(null, relayed)
    }

    /**
     * Verifies that a built request, including its subject, is serialized unchanged through the relay.
     */
    @Test
    fun `supported tool name builds payload, relays and awaits the result`() = runTest {
        val request = AgentSpawnRequest(
            agentRoleToSpawn = AgentRoleDto(id = 5L, name = "writer", modelId = 1L, modelSettingsId = 2L),
            subject = "Summary task",
            conversation = listOf(AgentSpawnMessage.User("Write a summary")),
            toolCallId = 1L
        )
        val builder = mockk<AgentSpawnRequestBuilder>()
        coEvery { builder.build(1L, any(), any()) } returns request.right()

        val executor = DefaultOperatorToolExecutor(builder, json)
        val supported = toolCall()

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            userId = 1L,
            requestingAgentRoleId = 5L,
            toolCall = supported,
            emitEvent = { event ->
                if (event is ToolCallExecutionEvent.OperatorToolExecutionRequested) relayed = event
            },
            operatorToolResultFlow = flowOf(
                OperatorToolExecutionResult(
                    toolCallId = supported.id,
                    output = "FINAL SUMMARY",
                    isError = false,
                    errorMessage = null
                )
            )
        )

        assertEquals(ToolCallStatus.SUCCESS, result.status)
        assertEquals("FINAL SUMMARY", result.output)

        // The relay event carries the tool name as the payload discriminator.
        val relay = assertIs<ToolCallExecutionEvent.OperatorToolExecutionRequested>(relayed)
        assertEquals(supported.id, relay.toolCallId)
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, relay.toolName)
        // The payload serialized into the envelope is a decodable AgentSpawnRequest.
        val decoded = json.decodeFromString(AgentSpawnRequest.serializer(), relay.payloadJson)
        assertEquals(request, decoded)
    }

    @Test
    fun `payload build failure maps to a readable tool error`() = runTest {
        val builder = mockk<AgentSpawnRequestBuilder>()
        coEvery { builder.build(1L, any(), any()) } returns SpawnRequestBuildError.RoleNotFound("writer").left()

        val executor = DefaultOperatorToolExecutor(builder, json)
        val result = executor.executeTool(
            userId = 1L,
            requestingAgentRoleId = 5L,
            toolCall = toolCall(),
            emitEvent = {},
            operatorToolResultFlow = flowOf()
        )

        assertEquals(ToolCallStatus.ERROR, result.status)
        assertTrue(result.errorMessage.orEmpty().contains("writer"))
    }
}
