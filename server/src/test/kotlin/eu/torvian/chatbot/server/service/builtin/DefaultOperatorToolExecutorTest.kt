package eu.torvian.chatbot.server.service.builtin

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.SendMessageRequest
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.agent.AgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.agent.SendMessageRequestBuilder
import eu.torvian.chatbot.server.service.core.error.agent.SendMessageRequestBuildError
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
 * Verifies the supported-tool dispatch: only `spawn_agent` and `send_message` may be executed
 * (anything else fails fast with a readable tool error and never reaches a payload builder or the
 * relay), plus the happy paths for both tools (payload built, relay event emitted with the tool
 * name, result awaited and mapped to the terminal tool call) and build-failure mapping.
 */
class DefaultOperatorToolExecutorTest {

    private val json = Json
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /**
     * Builds the execution context forwarded to [OperatorToolExecutor.executeTool].
     *
     * @param userId Caller identity; forwarded to the payload builders.
     * @param agentRoleId Source role id from the validated session.
     * @param projectId Session project scope (unassociated when `null`).
     * @return A fully-populated context for executor tests.
     */
    private fun context(
        userId: Long = 1L,
        agentRoleId: Long = 5L,
        projectId: Long? = null
    ): ToolCallExecutionContext = ToolCallExecutionContext(
        userId = userId,
        sessionId = 10L,
        sessionName = "Session",
        agentRoleId = agentRoleId,
        projectId = projectId
    )

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
        val spawnBuilder = mockk<AgentSpawnRequestBuilder>()
        val sendBuilder = mockk<SendMessageRequestBuilder>()
        val executor = DefaultOperatorToolExecutor(spawnBuilder, sendBuilder, json)
        val unsupported = toolCall(toolName = "future_tool")

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            context = context(),
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
        assertTrue(message.contains(OperatorToolCatalog.SEND_MESSAGE_NAME))
        // No payload was built and no relay event was emitted for the unsupported name.
        coVerify(exactly = 0) { spawnBuilder.build(any(), any()) }
        coVerify(exactly = 0) { sendBuilder.build(any(), any()) }
        assertEquals(null, relayed)
    }

    /**
     * Verifies that a built request, including its subject and spawn project, is serialized
     * unchanged through the relay.
     */
    @Test
    fun `supported spawn tool name builds payload, relays and awaits the result`() = runTest {
        val request = AgentSpawnRequest(
            agentRoleToSpawn = AgentRoleDto(id = 5L, name = "writer", modelId = 1L, modelSettingsId = 2L),
            subject = "Summary task",
            projectId = 7L,
            conversation = listOf(AgentSpawnMessage.User("Write a summary")),
            toolCallId = 1L
        )
        val spawnBuilder = mockk<AgentSpawnRequestBuilder>()
        coEvery { spawnBuilder.build(any(), any()) } returns request.right()

        val executor = DefaultOperatorToolExecutor(spawnBuilder, mockk(), json)
        val supported = toolCall()

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            context = context(),
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

    /**
     * Verifies that the turn's project scope is forwarded to the spawn builder, so a
     * project-attached session resolves its target role within the project.
     */
    @Test
    fun `spawn dispatch forwards the session project scope to the builder`() = runTest {
        val request = AgentSpawnRequest(
            agentRoleToSpawn = AgentRoleDto(id = 5L, name = "writer", modelId = 1L, modelSettingsId = 2L),
            subject = "Summary task",
            conversation = listOf(AgentSpawnMessage.User("Write a summary")),
            toolCallId = 1L
        )
        val spawnBuilder = mockk<AgentSpawnRequestBuilder>()
        val projectContext = context(projectId = 7L)
        coEvery { spawnBuilder.build(projectContext, any()) } returns request.right()

        val executor = DefaultOperatorToolExecutor(spawnBuilder, mockk(), json)

        val result = executor.executeTool(
            context = projectContext,
            toolCall = toolCall(),
            emitEvent = {},
            operatorToolResultFlow = flowOf(
                OperatorToolExecutionResult(
                    toolCallId = 1L,
                    output = "OK",
                    isError = false,
                    errorMessage = null
                )
            )
        )

        assertEquals(ToolCallStatus.SUCCESS, result.status)
        // The exact context instance (carrying the project scope) must reach the spawn builder.
        coVerify(exactly = 1) { spawnBuilder.build(projectContext, any()) }
    }

    @Test
    fun `spawn payload build failure maps to a readable tool error`() = runTest {
        val spawnBuilder = mockk<AgentSpawnRequestBuilder>()
        coEvery { spawnBuilder.build(any(), any()) } returns SpawnRequestBuildError.RoleNotFound("writer").left()

        val executor = DefaultOperatorToolExecutor(spawnBuilder, mockk(), json)
        val result = executor.executeTool(
            context = context(),
            toolCall = toolCall(),
            emitEvent = {},
            operatorToolResultFlow = flowOf()
        )

        assertEquals(ToolCallStatus.ERROR, result.status)
        assertTrue(result.errorMessage.orEmpty().contains("writer"))
    }

    /**
     * Verifies that an awaited empty (output = null) success result maps to a terminal SUCCESS tool
     * call, and that the shared `mode` (fire-and-forget here) survives the generic payload relay
     * unchanged (the server side is payload-agnostic).
     */
    @Test
    fun `awaited empty result maps to SUCCESS with null output and relays fire and forget mode`() = runTest {
        val request = AgentSpawnRequest(
            agentRoleToSpawn = AgentRoleDto(id = 5L, name = "writer", modelId = 1L, modelSettingsId = 2L),
            subject = "Summary task",
            mode = OperatorToolMode.FIRE_AND_FORGET,
            conversation = listOf(AgentSpawnMessage.User("Write a summary")),
            toolCallId = 1L
        )
        val spawnBuilder = mockk<AgentSpawnRequestBuilder>()
        coEvery { spawnBuilder.build(any(), any()) } returns request.right()

        val executor = DefaultOperatorToolExecutor(spawnBuilder, mockk(), json)
        val supported = toolCall()

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            context = context(),
            toolCall = supported,
            emitEvent = { event ->
                if (event is ToolCallExecutionEvent.OperatorToolExecutionRequested) relayed = event
            },
            operatorToolResultFlow = flowOf(
                OperatorToolExecutionResult(
                    toolCallId = supported.id,
                    output = null,
                    isError = false,
                    errorMessage = null
                )
            )
        )

        // An empty-success operator result becomes a terminal SUCCESS tool call with null output.
        assertEquals(ToolCallStatus.SUCCESS, result.status)
        assertEquals(null, result.output)

        // The relayed payload is a decodable AgentSpawnRequest carrying the fire-and-forget mode.
        val relay = assertIs<ToolCallExecutionEvent.OperatorToolExecutionRequested>(relayed)
        assertEquals(supported.id, relay.toolCallId)
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, relay.toolName)
        val decoded = json.decodeFromString(AgentSpawnRequest.serializer(), relay.payloadJson)
        assertEquals(request, decoded)
        assertEquals(OperatorToolMode.FIRE_AND_FORGET, decoded.mode)
    }

    /**
     * Verifies that a `send_message` call is dispatched to the send builder, relayed with the
     * `send_message` tool name, and its awaited result mapped to the terminal SUCCESS tool call —
     * without consulting the spawn builder.
     */
    @Test
    fun `send_message builds payload relays and maps the awaited result`() = runTest {
        val sendRequest = SendMessageRequest(
            chatSessionId = 7L,
            message = "Continue please",
            toolCallId = 3L
        )
        val sendBuilder = mockk<SendMessageRequestBuilder>()
        coEvery { sendBuilder.build(1L, any()) } returns sendRequest.right()
        val spawnBuilder = mockk<AgentSpawnRequestBuilder>()

        val executor = DefaultOperatorToolExecutor(spawnBuilder, sendBuilder, json)
        val sendCall = toolCall(
            id = 3L,
            toolName = OperatorToolCatalog.SEND_MESSAGE_NAME,
            input = """{"chat_session_id":7,"message":"Continue please"}"""
        )

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            context = context(),
            toolCall = sendCall,
            emitEvent = { event ->
                if (event is ToolCallExecutionEvent.OperatorToolExecutionRequested) relayed = event
            },
            operatorToolResultFlow = flowOf(
                OperatorToolExecutionResult(
                    toolCallId = sendCall.id,
                    output = "TARGET RESPONSE",
                    isError = false,
                    errorMessage = null
                )
            )
        )

        assertEquals(ToolCallStatus.SUCCESS, result.status)
        assertEquals("TARGET RESPONSE", result.output)

        val relay = assertIs<ToolCallExecutionEvent.OperatorToolExecutionRequested>(relayed)
        assertEquals(sendCall.id, relay.toolCallId)
        assertEquals(OperatorToolCatalog.SEND_MESSAGE_NAME, relay.toolName)
        val decoded = json.decodeFromString(SendMessageRequest.serializer(), relay.payloadJson)
        assertEquals(sendRequest, decoded)
        // The send branch must not reach the spawn builder.
        coVerify(exactly = 0) { spawnBuilder.build(any(), any()) }
    }

    /**
     * Verifies that a `send_message` build failure (e.g. an unknown target session) maps to a
     * readable tool-level error without emitting a relay event.
     */
    @Test
    fun `send_message build failure maps to a readable tool error`() = runTest {
        val sendBuilder = mockk<SendMessageRequestBuilder>()
        coEvery { sendBuilder.build(1L, any()) } returns
            SendMessageRequestBuildError.SessionNotFound(7L).left()

        val executor = DefaultOperatorToolExecutor(mockk(), sendBuilder, json)
        val sendCall = toolCall(
            id = 3L,
            toolName = OperatorToolCatalog.SEND_MESSAGE_NAME,
            input = """{"chat_session_id":7,"message":"Continue please"}"""
        )

        var relayed: ToolCallExecutionEvent.OperatorToolExecutionRequested? = null
        val result = executor.executeTool(
            context = context(),
            toolCall = sendCall,
            emitEvent = { event ->
                if (event is ToolCallExecutionEvent.OperatorToolExecutionRequested) relayed = event
            },
            operatorToolResultFlow = flowOf()
        )

        assertEquals(ToolCallStatus.ERROR, result.status)
        assertTrue(result.errorMessage.orEmpty().contains("Chat session 7 not found or not owned"))
        // The failure is mapped before any relay event is emitted.
        assertEquals(null, relayed)
    }
}