package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionCommandData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [WorkerMcpServerControlCommandExecutorImpl].
 */
class WorkerMcpServerControlCommandExecutorImplTest {
    /**
     * Verifies runtime start success maps to protocol start result.
     */
    @Test
    fun `start maps runtime success`() = runTest {
        val executor = WorkerMcpServerControlCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                startResult = Unit.right()
            )
        )

        val result = executor.startServer(WorkerMcpServerStartCommandData(serverId = 10L)).rightOrError()

        assertEquals(10L, result.serverId)
    }

    /**
     * Verifies runtime stop success maps to protocol stop result.
     */
    @Test
    fun `stop maps runtime success`() = runTest {
        val executor = WorkerMcpServerControlCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                stopResult = Unit.right()
            )
        )

        val result = executor.stopServer(WorkerMcpServerStopCommandData(serverId = 11L)).rightOrError()

        assertEquals(11L, result.serverId)
    }

    /**
     * Verifies runtime test-connection success maps discovered tool count and message.
     */
    @Test
    fun `test connection maps runtime success`() = runTest {
        val executor = WorkerMcpServerControlCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                testResult = WorkerLocalMcpTestConnectionOutcome(
                    discoveredToolCount = 5,
                    message = "runtime ok"
                ).right()
            )
        )

        val result = executor.testConnection(
            WorkerMcpServerTestConnectionCommandData(serverId = 12L)
        ).rightOrError()

        assertEquals(12L, result.serverId)
        assertEquals(true, result.success)
        assertEquals(5, result.discoveredToolCount)
        assertEquals("runtime ok", result.message)
    }

    /**
     * Verifies runtime discover result maps to protocol discover payload.
     */
    @Test
    fun `discover tools maps runtime discovered result`() = runTest {
        val executor = WorkerMcpServerControlCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                discoverResult = listOf(
                    WorkerLocalMcpDiscoveredTool(
                        name = "echo",
                        description = "Echoes input",
                        inputSchema = buildJsonObject { put("type", "object") },
                        outputSchema = null
                    )
                ).right()
            )
        )

        val result = executor.discoverTools(
            WorkerMcpServerDiscoverToolsCommandData(serverId = 13L)
        ).rightOrError()

        assertEquals(13L, result.serverId)
        assertEquals(1, result.tools.size)
        assertEquals(
            WorkerMcpDiscoveredToolData(
                name = "echo",
                description = "Echoes input",
                inputSchema = buildJsonObject { put("type", "object") },
                outputSchema = null
            ),
            result.tools.single()
        )
    }

    /**
     * Verifies runtime failures are mapped to protocol error result data.
     */
    @Test
    fun `runtime failure maps to protocol error payload`() = runTest {
        val executor = WorkerMcpServerControlCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                startResult = WorkerLocalMcpRuntimeError.ServerConfigMissing(serverId = 20L).left()
            )
        )

        val error = executor.startServer(
            WorkerMcpServerStartCommandData(serverId = 20L)
        ).leftOrError()

        assertEquals(20L, error.serverId)
        assertEquals("SERVER_CONFIG_MISSING", error.code)
        assertEquals("No local MCP server config available for serverId=20", error.message)
    }

    /**
     * Fake runtime service with configurable command outcomes.
     */
    private class FakeRuntimeService(
        private val startResult: Either<WorkerLocalMcpRuntimeError, Unit> = Unit.right(),
        private val stopResult: Either<WorkerLocalMcpRuntimeError, Unit> = Unit.right(),
        private val testResult: Either<WorkerLocalMcpRuntimeError, WorkerLocalMcpTestConnectionOutcome> =
            WorkerLocalMcpTestConnectionOutcome(discoveredToolCount = 0, message = null).right(),
        private val discoverResult: Either<WorkerLocalMcpRuntimeError, List<WorkerLocalMcpDiscoveredTool>> =
            emptyList<WorkerLocalMcpDiscoveredTool>().right()
    ) : WorkerLocalMcpRuntimeService {
        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun startServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit> = startResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun stopServer(serverId: Long): Either<WorkerLocalMcpRuntimeError, Unit> = stopResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun testConnection(
            serverId: Long
        ): Either<WorkerLocalMcpRuntimeError, WorkerLocalMcpTestConnectionOutcome> = testResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun discoverTools(
            serverId: Long
        ): Either<WorkerLocalMcpRuntimeError, List<WorkerLocalMcpDiscoveredTool>> = discoverResult
    }
}

/**
 * Returns Right value from a test Either or fails fast.
 *
 * @receiver Either under assertion.
 * @return Right value.
 */
private fun <L, R> Either<L, R>.rightOrError(): R = getOrElse {
    error("Expected Right but got Left: $it")
}

/**
 * Returns Left value from a test Either or fails fast.
 *
 * @receiver Either under assertion.
 * @return Left value.
 */
private fun <L, R> Either<L, R>.leftOrError(): L = fold(
    ifLeft = { it },
    ifRight = { rightValue -> error("Expected Left but got Right: $rightValue") }
)


