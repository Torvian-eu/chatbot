package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Unit tests for [McpRuntimeCommandExecutorImpl].
 */
class McpRuntimeCommandExecutorImplTest {
    /**
     * Verifies runtime start success maps to protocol start result.
     */
    @Test
    fun `start maps runtime success`() = runTest {
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(startResult = Unit.right()),
            configStore = InMemoryMcpServerConfigStore()
        )

        val result = executor.startServer(WorkerMcpServerStartCommandData(serverId = 10L)).rightOrError()

        assertEquals(10L, result.serverId)
    }

    /**
     * Verifies runtime stop success maps to protocol stop result.
     */
    @Test
    fun `stop maps runtime success`() = runTest {
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(stopResult = Unit.right()),
            configStore = InMemoryMcpServerConfigStore()
        )

        val result = executor.stopServer(WorkerMcpServerStopCommandData(serverId = 11L)).rightOrError()

        assertEquals(11L, result.serverId)
    }

    /**
     * Verifies runtime test-connection success maps discovered tool count and message.
     */
    @Test
    fun `test connection maps runtime success`() = runTest {
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                testResult = McpTestConnectionOutcome(
                    discoveredToolCount = 5,
                    message = "runtime ok"
                ).right()
            ),
            configStore = InMemoryMcpServerConfigStore()
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
     * Verifies runtime draft test success maps to the protocol draft result payload.
     */
    @Test
    fun `test draft connection maps runtime success`() = runTest {
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                testDraftResult = McpTestConnectionOutcome(
                    discoveredToolCount = 6,
                    message = "draft runtime ok"
                ).right()
            ),
            configStore = InMemoryMcpServerConfigStore()
        )

        val result = executor.testDraftConnection(
            WorkerMcpServerTestDraftConnectionCommandData(
                name = "draft-filesystem",
                command = "npx",
                arguments = listOf("-y", "tool"),
                workingDirectory = "C:/data"
            )
        ).rightOrError()

        assertEquals(true, result.success)
        assertEquals(6, result.discoveredToolCount)
        assertEquals("draft runtime ok", result.message)
    }

    /**
     * Verifies runtime discover result maps to protocol discover payload.
     */
    @Test
    fun `discover tools maps runtime discovered result`() = runTest {
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                discoverResult = listOf(
                    McpDiscoveredTool(
                        name = "echo",
                        description = "Echoes input",
                        inputSchema = buildJsonObject { put("type", "object") },
                        outputSchema = null
                    )
                ).right()
            ),
            configStore = InMemoryMcpServerConfigStore()
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
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                startResult = McpRuntimeError.ServerConfigMissing(serverId = 20L).left()
            ),
            configStore = InMemoryMcpServerConfigStore()
        )

        val error = executor.startServer(
            WorkerMcpServerStartCommandData(serverId = 20L)
        ).leftOrError()

        assertEquals(20L, error.serverId)
        assertEquals("SERVER_CONFIG_MISSING", error.code)
        assertEquals("No local MCP server config available for serverId=20", error.message)
    }

    /**
     * Verifies runtime-status get success maps to protocol runtime-status result.
     */
    @Test
    fun `get runtime status maps runtime success`() = runTest {
        val now = Clock.System.now()
        val status = LocalMcpServerRuntimeStatusDto(
            serverId = 30L,
            state = LocalMcpServerRuntimeStateDto.RUNNING,
            connectedAt = now,
            lastActivityAt = now
        )
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                getRuntimeStatusResult = status.right()
            ),
            configStore = InMemoryMcpServerConfigStore()
        )

        val result = executor.getRuntimeStatus(
            WorkerMcpServerGetRuntimeStatusCommandData(serverId = 30L)
        ).rightOrError()

        assertEquals(status, result.status)
    }

    /**
     * Verifies runtime-status list success maps to protocol runtime-status list result.
     */
    @Test
    fun `list runtime statuses maps runtime success`() = runTest {
        val status = LocalMcpServerRuntimeStatusDto(
            serverId = 31L,
            state = LocalMcpServerRuntimeStateDto.STOPPED,
            errorMessage = "worker disconnected"
        )
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(
                listRuntimeStatusesResult = listOf(status)
            ),
            configStore = InMemoryMcpServerConfigStore()
        )

        val result = executor.listRuntimeStatuses(
            WorkerMcpServerListRuntimeStatusesCommandData
        ).rightOrError()

        assertEquals(listOf(status), result.statuses)
    }

    /**
     * Verifies create-config sync upserts server configuration in the cache store.
     */
    @Test
    fun `create server sync upserts config store`() = runTest {
        val configStore = InMemoryMcpServerConfigStore()
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(),
            configStore = configStore
        )
        val server = testServer(serverId = 32L)

        val result = executor.createServer(WorkerMcpServerCreateCommandData(server = server)).rightOrError()

        assertEquals(32L, result.serverId)
        assertEquals(server, configStore.getServer(32L))
    }

    /**
     * Verifies update-config sync upserts server configuration in the cache store.
     */
    @Test
    fun `update server sync upserts config store`() = runTest {
        val configStore = InMemoryMcpServerConfigStore()
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(),
            configStore = configStore
        )
        val original = testServer(serverId = 33L)
        configStore.upsertServer(original)
        val updated = original.copy(name = "filesystem-v2")

        val result = executor.updateServer(WorkerMcpServerUpdateCommandData(server = updated)).rightOrError()

        assertEquals(33L, result.serverId)
        assertEquals("filesystem-v2", configStore.getServer(33L)?.name)
    }

    /**
     * Verifies delete-config sync removes server configuration from the cache store.
     */
    @Test
    fun `delete server sync removes config store entry`() = runTest {
        val configStore = InMemoryMcpServerConfigStore()
        val executor = McpRuntimeCommandExecutorImpl(
            runtimeService = FakeRuntimeService(),
            configStore = configStore
        )
        configStore.upsertServer(testServer(serverId = 34L))

        val result = executor.deleteServer(WorkerMcpServerDeleteCommandData(serverId = 34L)).rightOrError()

        assertEquals(34L, result.serverId)
        assertEquals(null, configStore.getServer(34L))
    }

    /**
     * Builds a deterministic Local MCP server DTO fixture for config-sync tests.
     *
     * @param serverId Persisted server identifier used in the fixture.
     * @return Deterministic Local MCP server DTO.
     */
    private fun testServer(serverId: Long): LocalMCPServerDto = LocalMCPServerDto(
        id = serverId,
        userId = 1L,
        workerId = 2L,
        name = "filesystem",
        command = "npx",
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )

    /**
     * Fake runtime service with configurable command outcomes.
     */
    private class FakeRuntimeService(
        private val startResult: Either<McpRuntimeError, Unit> = Unit.right(),
        private val stopResult: Either<McpRuntimeError, Unit> = Unit.right(),
        private val testResult: Either<McpRuntimeError, McpTestConnectionOutcome> =
            McpTestConnectionOutcome(discoveredToolCount = 0, message = null).right(),
        private val testDraftResult: Either<McpRuntimeError, McpTestConnectionOutcome> =
            McpTestConnectionOutcome(discoveredToolCount = 0, message = null).right(),
        private val discoverResult: Either<McpRuntimeError, List<McpDiscoveredTool>> =
            emptyList<McpDiscoveredTool>().right(),
        private val getRuntimeStatusResult: Either<McpRuntimeError, LocalMcpServerRuntimeStatusDto> =
            LocalMcpServerRuntimeStatusDto(
                serverId = 0L,
                state = LocalMcpServerRuntimeStateDto.STOPPED
            ).right(),
        private val listRuntimeStatusesResult: List<LocalMcpServerRuntimeStatusDto> = emptyList()
    ) : McpRuntimeService {
        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun startServer(serverId: Long): Either<McpRuntimeError, Unit> = startResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun stopServer(serverId: Long): Either<McpRuntimeError, Unit> = stopResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun testConnection(
            serverId: Long
        ): Either<McpRuntimeError, McpTestConnectionOutcome> = testResult

        /**
         * @param config Draft local MCP server configuration.
         * @return Configured fixture result.
         */
        override suspend fun testDraftConnection(
            config: LocalMCPServerDto
        ): Either<McpRuntimeError, McpTestConnectionOutcome> = testDraftResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun discoverTools(
            serverId: Long
        ): Either<McpRuntimeError, List<McpDiscoveredTool>> = discoverResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun getRuntimeStatus(
            serverId: Long
        ): Either<McpRuntimeError, LocalMcpServerRuntimeStatusDto> = getRuntimeStatusResult

        /**
         * @return Configured fixture runtime-status list.
         */
        override suspend fun listRuntimeStatuses(): List<LocalMcpServerRuntimeStatusDto> = listRuntimeStatusesResult

        /**
         * @param serverId Persisted local MCP server identifier.
         * @param toolName MCP tool name to invoke.
         * @param arguments JSON argument object passed to the tool.
         * @return Always returns empty success outcome (not used in these tests).
         */
        override suspend fun callTool(
            serverId: Long,
            toolName: String,
            arguments: JsonObject
        ): Either<McpRuntimeError, McpToolCallOutcome?> =
            McpToolCallOutcome(isError = false, textContent = "{}").right()
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


