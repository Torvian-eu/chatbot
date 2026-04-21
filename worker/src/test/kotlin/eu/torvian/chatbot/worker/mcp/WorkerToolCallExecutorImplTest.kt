package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkerToolCallExecutorImpl].
 */
class WorkerToolCallExecutorImplTest {

    /**
     * Verifies that malformed request JSON is mapped to a logical result error and does not hit the runtime service.
     */
    @Test
    fun `malformed json returns logical error without runtime service call`() = kotlinx.coroutines.test.runTest {
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(WorkerMcpToolCallOutcome(isError = false, textContent = "ok"))
        )
        val executor = WorkerToolCallExecutorImpl(runtimeService = runtimeService, json = Json { ignoreUnknownKeys = true })

        val result = executor.execute(
            LocalMCPToolCallRequest(
                toolCallId = 55,
                serverId = 10,
                toolName = "searchDocs",
                inputJson = "{invalid"
            )
        )

        assertTrue(result.isError)
        assertTrue(result.errorMessage?.contains("Malformed JSON input") == true)
        assertEquals(0, runtimeService.callCount)
    }

    /**
     * Verifies that runtime-level logical errors are propagated as tool-call errors.
     */
    @Test
    fun `runtime error becomes tool call error result`() = kotlinx.coroutines.test.runTest {
        val runtimeService = RecordingRuntimeService(
            result = Either.Left(
                WorkerLocalMcpRuntimeError.ToolCallFailed(
                    serverId = 10,
                    toolName = "searchDocs",
                    message = "Tool process not reachable"
                )
            )
        )
        val executor = WorkerToolCallExecutorImpl(runtimeService = runtimeService, json = Json { ignoreUnknownKeys = true })

        val result = executor.execute(validRequest())

        assertTrue(result.isError)
        assertEquals("Tool process not reachable", result.errorMessage)
        assertEquals(1, runtimeService.callCount)
    }

    /**
     * Verifies that successful outcomes with text content are preserved in output.
     */
    @Test
    fun `successful outcome returns text output`() = kotlinx.coroutines.test.runTest {
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(
                WorkerMcpToolCallOutcome(
                    isError = false,
                    textContent = "{\"items\":[\"ktor\"]}"
                )
            )
        )
        val executor = WorkerToolCallExecutorImpl(runtimeService = runtimeService, json = Json { ignoreUnknownKeys = true })

        val result = executor.execute(validRequest())

        assertFalse(result.isError)
        assertEquals("{\"items\":[\"ktor\"]}", result.output)
        assertEquals(1, runtimeService.callCount)
    }

    /**
     * Verifies that MCP-level error outcomes are mapped to logical tool-call errors.
     */
    @Test
    fun `error outcome maps structured content to error message`() = kotlinx.coroutines.test.runTest {
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(
                WorkerMcpToolCallOutcome(
                    isError = true,
                    structuredContent = "{\"code\":\"TOOL_TIMEOUT\"}"
                )
            )
        )
        val executor = WorkerToolCallExecutorImpl(runtimeService = runtimeService, json = Json { ignoreUnknownKeys = true })

        val result = executor.execute(validRequest())

        assertTrue(result.isError)
        assertEquals("{\"code\":\"TOOL_TIMEOUT\"}", result.errorMessage)
        assertEquals(1, runtimeService.callCount)
    }

    /**
     * Simple recording runtime service used for deterministic executor tests.
     *
     * @property result Invocation result returned by each callTool call.
     */
    private class RecordingRuntimeService(
        private val result: Either<WorkerLocalMcpRuntimeError, WorkerMcpToolCallOutcome?>
    ) : WorkerLocalMcpRuntimeService {

        /**
         * Number of times [callTool] was invoked.
         */
        var callCount: Int = 0

        /**
         * Returns the configured test result and increments [callCount].
         *
         * @param serverId Local MCP server identifier.
         * @param toolName MCP tool name.
         * @param arguments Parsed tool arguments.
         * @return Preconfigured invocation result.
         */
        override suspend fun callTool(
            serverId: Long,
            toolName: String,
            arguments: JsonObject
        ): Either<WorkerLocalMcpRuntimeError, WorkerMcpToolCallOutcome?> {
            callCount++
            return result
        }

        // Stub implementations for other runtime service methods (not used in tests)
        override suspend fun startServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit> =
            Unit.right()
        override suspend fun stopServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit> =
            Unit.right()
        override suspend fun testConnection(serverId: Long): Either<WorkerLocalMcpRuntimeError, WorkerLocalMcpTestConnectionOutcome> =
            WorkerLocalMcpTestConnectionOutcome(0).right()
        override suspend fun discoverTools(serverId: Long): Either<WorkerLocalMcpRuntimeError, List<WorkerLocalMcpDiscoveredTool>> =
            emptyList<WorkerLocalMcpDiscoveredTool>().right()
        override suspend fun getRuntimeStatus(serverId: Long): Either<WorkerLocalMcpRuntimeError, eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto> =
            eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto(
                serverId = serverId,
                state = eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto.STOPPED
            ).right()
        override suspend fun listRuntimeStatuses(): List<eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto> =
            emptyList()
    }

    /**
     * Builds a valid baseline tool-call request for test scenarios.
     *
     * @return Valid request DTO with simple JSON input.
     */
    private fun validRequest(): LocalMCPToolCallRequest {
        return LocalMCPToolCallRequest(
            toolCallId = 123,
            serverId = 10,
            toolName = "searchDocs",
            inputJson = "{\"query\":\"ktor\"}"
        )
    }
}

