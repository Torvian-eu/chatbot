package eu.torvian.chatbot.server.service.mcp

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.toolcall.LocalMCPToolCallDispatchError
import eu.torvian.chatbot.server.worker.mcp.toolcall.LocalMCPToolCallDispatchService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies that the chat Local MCP executor resolves the assigned worker and translates worker outcomes.
 */
class LocalMCPExecutorTest {
    private val localMCPServerDao: LocalMCPServerDao = mockk()
    private val dispatchService: LocalMCPToolCallDispatchService = mockk()
    private val executor = LocalMCPExecutor(localMCPServerDao, dispatchService)

    private val toolDefinition = LocalMCPToolDefinition(
        id = 9L,
        name = "Filesystem.List",
        description = "List files",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        outputSchema = null,
        isEnabled = true,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        serverId = 33L,
        mcpToolName = "list_files"
    )

    private val serverEntity = LocalMCPServerEntity(
        id = toolDefinition.serverId,
        userId = 7L,
        workerId = 17L,
        name = "Filesystem",
        description = null,
        command = "npx",
        arguments = emptyList(),
        workingDirectory = null,
        isEnabled = true,
        autoStartOnEnable = false,
        autoStartOnLaunch = false,
        autoStopAfterInactivitySeconds = null,
        toolNamePrefix = null,
        environmentVariables = emptyList(),
        secretEnvironmentVariables = emptyList(),
        createdAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(1_000)
    )

    /** Persisted tool-call fixture used to verify request relaying into the worker command adapter. */
    private val toolCall = ToolCall(
        id = 99L,
        messageId = 55L,
        toolDefinitionId = toolDefinition.id,
        toolName = toolDefinition.name,
        input = "{\"path\":\".\"}",
        output = null,
        status = ToolCallStatus.EXECUTING,
        errorMessage = null,
        denialReason = null,
        executedAt = Instant.fromEpochMilliseconds(1_500),
        durationMs = null
    )

    /** App-approved Local MCP authorization snapshot that should be copied into the worker request. */
    private val authorization = LocalMCPToolExecutionAuthorization(
        toolCallId = toolCall.id,
        sessionId = 77L,
        messageId = toolCall.messageId,
        toolDefinitionId = toolDefinition.id,
        toolName = toolCall.toolName,
        serverId = toolDefinition.serverId,
        mcpToolName = toolDefinition.mcpToolName,
        input = toolCall.input,
        approved = true,
        denialReason = null
    )

    /**
     * Verifies that a successful dispatch yields a tool-result event and preserves the tool call id.
     */
    @Test
    fun `execute tool dispatches to assigned worker`() = runTest {
        val requestSlot = slot<eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest>()
        val result = LocalMCPToolCallResult(
            toolCallId = 99L,
            output = "{\"files\":[]}",
            isError = false
        )

        coEvery { localMCPServerDao.getServerById(toolDefinition.serverId) } returns serverEntity.right()
        coEvery { dispatchService.dispatchToolCall(17L, capture(requestSlot)) } returns result.right()

        val event = executor.executeTool(
            sessionId = authorization.sessionId,
            toolDefinition = toolDefinition,
            toolCall = toolCall,
            authorization = authorization,
            signedAuthorization = signedRequest()
        )

        assertIs<LocalMCPExecutorEvent.ToolExecutionResult>(event)
        assertEquals(99L, event.result.toolCallId)
        assertEquals(result, event.result)
        assertEquals(toolCall.id, requestSlot.captured.toolCallId)
        assertEquals(toolCall.messageId, requestSlot.captured.messageId)
        assertEquals(toolDefinition.id, requestSlot.captured.toolDefinitionId)
        assertEquals(toolCall.toolName, requestSlot.captured.toolName)
        assertEquals(toolDefinition.mcpToolName, requestSlot.captured.mcpToolName)
        assertEquals(toolCall.input, requestSlot.captured.inputJson)
        assertEquals(true, requestSlot.captured.approved)
        assertEquals(signedRequest(), requestSlot.captured.signedAuthorization)
        coVerify(exactly = 1) { localMCPServerDao.getServerById(toolDefinition.serverId) }
        coVerify(exactly = 1) { dispatchService.dispatchToolCall(17L, any()) }
    }

    /**
     * Verifies that a missing worker assignment becomes a structured execution error.
     */
    @Test
    fun `execute tool maps missing assignment to execution error`() = runTest {
        coEvery { localMCPServerDao.getServerById(toolDefinition.serverId) } returns
                LocalMCPServerError.NotFound(toolDefinition.serverId).left()

        val missingToolCall = toolCall.copy(id = 100L, input = null)
        val event = executor.executeTool(
            sessionId = authorization.sessionId,
            toolDefinition = toolDefinition,
            toolCall = missingToolCall,
            authorization = authorization.copy(toolCallId = 100L, input = null),
            signedAuthorization = signedRequest()
        )

        assertIs<LocalMCPExecutorEvent.ToolExecutionError>(event)
        assertEquals(100L, event.toolCallId)
        assertEquals(
            "Local MCP server ${toolDefinition.serverId} was not found",
            event.error.message
        )
        coVerify(exactly = 0) { dispatchService.dispatchToolCall(any(), any()) }
    }

    /**
     * Verifies that worker dispatch timeouts are converted into execution errors instead of throwing.
     */
    @Test
    fun `execute tool maps worker timeout to execution error`() = runTest {
        coEvery { localMCPServerDao.getServerById(toolDefinition.serverId) } returns serverEntity.copy(workerId = 18L)
            .right()
        coEvery { dispatchService.dispatchToolCall(18L, any()) } returns
                LocalMCPToolCallDispatchError.DispatchFailed(
                    WorkerCommandDispatchError.TimedOut(
                        workerId = 18L,
                        interactionId = "interaction-9",
                        commandType = "mcp.tool.call",
                        timeout = 30.seconds
                    )
                ).left()

        val timedOutToolCall = toolCall.copy(id = 101L, input = "{}")
        val event = executor.executeTool(
            sessionId = authorization.sessionId,
            toolDefinition = toolDefinition,
            toolCall = timedOutToolCall,
            authorization = authorization.copy(toolCallId = 101L, input = "{}"),
            signedAuthorization = signedRequest()
        )

        assertIs<LocalMCPExecutorEvent.ToolExecutionError>(event)
        assertIs<LocalMCPExecutorError.Timeout>(event.error)
        assertEquals("Tool execution timed out after 30 seconds", event.error.message)
    }

    /**
     * Builds deterministic detached signed-request metadata for Local MCP executor tests.
     *
     * @return Signed request fixture.
     */
    private fun signedRequest(): SignedRequest = SignedRequest(
        payload = "{\"toolCallId\":99}",
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}



