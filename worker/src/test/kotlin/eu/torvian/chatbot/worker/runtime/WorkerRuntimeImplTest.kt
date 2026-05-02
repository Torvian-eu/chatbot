package eu.torvian.chatbot.worker.runtime

import arrow.core.Either
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.worker.mcp.McpClientCallToolError
import eu.torvian.chatbot.worker.mcp.McpClientConnectionStatus
import eu.torvian.chatbot.worker.mcp.McpClientDiscoverToolsError
import eu.torvian.chatbot.worker.mcp.McpClientPingError
import eu.torvian.chatbot.worker.mcp.McpClientService
import eu.torvian.chatbot.worker.mcp.McpClientStartError
import eu.torvian.chatbot.worker.mcp.McpClientStopError
import eu.torvian.chatbot.worker.protocol.transport.TransportConnectionLoopRunner
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerRuntimeImplTest {

    @Test
    fun `runtime delegates runOnce flag to connection loop`() = runTest {
        val loop = RecordingConnectionLoop(Either.Right(Unit))
        val mcpClientService = RecordingMcpClientService()
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            connectionLoop = loop,
            mcpClientService = mcpClientService
        )

        val result = runtime.run(runOnce = true)

        assertTrue(result.isRight())
        assertEquals(1, loop.calls)
        assertEquals(true, loop.lastRunOnce)
    }

    @Test
    fun `runtime propagates runtime errors returned by connection loop`() = runTest {
        val expectedError = WorkerRuntimeError.Auth(WorkerAuthManagerError.BlankChallengePayload)
        val loop = RecordingConnectionLoop(Either.Left(expectedError))
        val mcpClientService = RecordingMcpClientService()
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            connectionLoop = loop,
            mcpClientService = mcpClientService
        )

        val result = runtime.run(runOnce = false)

        assertTrue(result.isLeft())
        assertEquals(expectedError, result.swap().getOrNull())
        assertEquals(false, loop.lastRunOnce)
    }

    /**
     * Verifies runtime close delegates MCP resource cleanup only once.
     */
    @Test
    fun `runtime close is idempotent and closes MCP client service once`() = runTest {
        val mcpClientService = RecordingMcpClientService()
        val runtime = WorkerRuntimeImpl(
            workerUid = "worker-7",
            connectionLoop = RecordingConnectionLoop(Either.Right(Unit)),
            mcpClientService = mcpClientService
        )

        runtime.close()
        runtime.close()

        assertEquals(1, mcpClientService.closeCalls)
    }

    /**
     * Records connection loop invocations for test assertion.
     *
     * @property result Pre-configured result to return from run calls.
     */
    private class RecordingConnectionLoop(
        private val result: Either<WorkerRuntimeError, Unit>
    ) : TransportConnectionLoopRunner {
        /**
         * Number of times run was called.
         */
        var calls: Int = 0

        /**
         * The last runOnce flag value passed to run.
         */
        var lastRunOnce: Boolean? = null

        override suspend fun run(runOnce: Boolean): Either<WorkerRuntimeError, Unit> {
            calls += 1
            lastRunOnce = runOnce
            return result
        }
    }

    /**
     * Recording MCP client service used to assert runtime shutdown behavior.
     */
    private class RecordingMcpClientService : McpClientService {
        /**
         * Number of close calls observed.
         */
        var closeCalls: Int = 0

        override suspend fun startAndConnect(config: LocalMCPServerDto): Either<McpClientStartError, Unit> =
            error("Not used in WorkerRuntimeImplTest")

        override suspend fun stopServer(serverId: Long): Either<McpClientStopError, Unit> =
            error("Not used in WorkerRuntimeImplTest")

        override suspend fun discoverTools(serverId: Long): Either<McpClientDiscoverToolsError, List<Tool>> =
            error("Not used in WorkerRuntimeImplTest")

        override suspend fun pingClient(serverId: Long): Either<McpClientPingError, Unit> =
            error("Not used in WorkerRuntimeImplTest")

        override suspend fun callTool(
            serverId: Long,
            toolName: String,
            arguments: JsonObject
        ): Either<McpClientCallToolError, CallToolResult> =
            error("Not used in WorkerRuntimeImplTest")

        override fun isClientConnected(serverId: Long): Boolean =
            error("Not used in WorkerRuntimeImplTest")

        override fun getConnectionStatus(serverId: Long): McpClientConnectionStatus =
            error("Not used in WorkerRuntimeImplTest")

        override suspend fun close() {
            closeCalls += 1
        }
    }
}


