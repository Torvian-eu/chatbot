package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Unit tests for [WorkerLocalMcpRuntimeServiceImpl].
 */
class WorkerLocalMcpRuntimeServiceImplTest {
    /**
     * Verifies start fails with config-missing runtime error when store has no server config.
     */
    @Test
    fun `start fails when server config is missing`() = runTest {
        val service = buildService()

        val result = service.startServer(serverId = 99L)
        val error = result.leftOrError()

        assertIs<WorkerLocalMcpRuntimeError.ServerConfigMissing>(error)
        assertEquals(99L, error.serverId)
    }

    /**
     * Verifies start succeeds when runtime client start/connect succeeds.
     */
    @Test
    fun `start succeeds when client start succeeds`() = runTest {
        val client = FakeWorkerMcpClientService()
        val service = buildService(clientService = client)

        val result = service.startServer(serverId = DEFAULT_SERVER_ID)

        assertEquals(Unit, result.rightOrError())
        assertEquals(1, client.startCalls)
    }

    /**
     * Verifies stop succeeds when runtime client stop succeeds.
     */
    @Test
    fun `stop succeeds when client stop succeeds`() = runTest {
        val client = FakeWorkerMcpClientService()
        val service = buildService(clientService = client)

        val result = service.stopServer(serverId = DEFAULT_SERVER_ID)

        assertEquals(Unit, result.rightOrError())
        assertEquals(1, client.stopCalls)
    }

    /**
     * Verifies idempotent start behavior maps already-connected client state to success.
     */
    @Test
    fun `start is idempotent when already connected`() = runTest {
        val client = FakeWorkerMcpClientService(
            startResult = WorkerMcpClientStartError.AlreadyConnected(DEFAULT_SERVER_ID).left()
        )
        val service = buildService(clientService = client)

        val result = service.startServer(serverId = DEFAULT_SERVER_ID)

        assertEquals(Unit, result.rightOrError())
    }

    /**
     * Verifies idempotent stop behavior maps not-connected client state to success.
     */
    @Test
    fun `stop is idempotent when already stopped`() = runTest {
        val client = FakeWorkerMcpClientService(
            stopResult = WorkerMcpClientStopError.NotConnected(DEFAULT_SERVER_ID).left()
        )
        val service = buildService(clientService = client)

        val result = service.stopServer(serverId = DEFAULT_SERVER_ID)

        assertEquals(Unit, result.rightOrError())
    }

    /**
     * Verifies test-connection starts temporarily, discovers tools, and stops temporary runtime.
     */
    @Test
    fun `test connection succeeds and performs temporary lifecycle`() = runTest {
        val client = FakeWorkerMcpClientService(
            discoverToolsResult = emptyList<Tool>().right(),
            connected = false
        )
        val service = buildService(clientService = client)

        val result = service.testConnection(serverId = DEFAULT_SERVER_ID).rightOrError()

        assertEquals(0, result.discoveredToolCount)
        assertEquals("Connection test succeeded", result.message)
        assertEquals(1, client.startCalls)
        assertEquals(1, client.stopCalls)
        assertEquals(1, client.discoverCalls)
    }

    /**
     * Verifies discover-tools performs runtime discovery and returns discovered metadata.
     */
    @Test
    fun `discover tools returns runtime discovered metadata`() = runTest {
        val client = FakeWorkerMcpClientService(
            discoverToolsResult = emptyList<Tool>().right(),
            connected = false
        )
        val service = buildService(clientService = client)

        val result = service.discoverTools(serverId = DEFAULT_SERVER_ID).rightOrError()

        assertEquals(0, result.size)
        assertEquals(1, client.startCalls)
        assertEquals(1, client.stopCalls)
        assertEquals(1, client.discoverCalls)
    }

    /**
     * Builds a service fixture with optional fake collaborators.
     *
     * @param configStore Config store used by runtime service.
     * @param clientService Client service used by runtime service.
     * @return Runtime service fixture.
     */
    private fun buildService(
        configStore: WorkerLocalMcpServerConfigStore = SingleServerConfigStore(),
        clientService: FakeWorkerMcpClientService = FakeWorkerMcpClientService()
    ): WorkerLocalMcpRuntimeServiceImpl {
        return WorkerLocalMcpRuntimeServiceImpl(
            configStore = configStore,
            clientService = clientService
        )
    }

    /**
     * Fake config store that exposes exactly one server entry.
     */
    private class SingleServerConfigStore : WorkerLocalMcpServerConfigStore {
        /**
         * Static server config fixture.
         */
        private var server: LocalMCPServerDto? = localServerConfig(DEFAULT_SERVER_ID)

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Stored config when IDs match.
         */
        override suspend fun getServer(serverId: Long): LocalMCPServerDto? = server?.takeIf { it.id == serverId }

        /**
         * @param config Server config replacement.
         */
        override suspend fun upsertServer(config: LocalMCPServerDto) {
            server = config
        }

        /**
         * @param serverId Server identifier to remove.
         */
        override suspend fun removeServer(serverId: Long) {
            if (server?.id == serverId) {
                server = null
            }
        }

        /**
         * @param servers Replacement list.
         */
        override suspend fun replaceAll(servers: List<LocalMCPServerDto>) {
            server = servers.firstOrNull()
        }
    }

    /**
     * Fake client service with configurable Either outcomes.
     *
     * @property startResult Result returned by [startAndConnect].
     * @property stopResult Result returned by [stopServer].
     * @property discoverToolsResult Result returned by [discoverTools].
     * @property connected Initial connected-state flag.
     */
    private class FakeWorkerMcpClientService(
        private val startResult: Either<WorkerMcpClientStartError, Unit> = Unit.right(),
        private val stopResult: Either<WorkerMcpClientStopError, Unit> = Unit.right(),
        private val discoverToolsResult: Either<WorkerMcpClientDiscoverToolsError, List<Tool>> = emptyList<Tool>().right(),
        private var connected: Boolean = false
    ) : WorkerMcpClientService {
        /**
         * Number of start calls observed.
         */
        var startCalls: Int = 0

        /**
         * Number of stop calls observed.
         */
        var stopCalls: Int = 0

        /**
         * Number of discovery calls observed.
         */
        var discoverCalls: Int = 0

        /**
         * @param config Resolved server configuration.
         * @return Configured fixture result.
         */
        override suspend fun startAndConnect(config: LocalMCPServerDto): Either<WorkerMcpClientStartError, Unit> {
            startCalls += 1
            if (startResult.isRight()) {
                connected = true
            }
            return startResult
        }

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun stopServer(serverId: Long): Either<WorkerMcpClientStopError, Unit> {
            stopCalls += 1
            if (stopResult.isRight()) {
                connected = false
            }
            return stopResult
        }

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Configured fixture result.
         */
        override suspend fun discoverTools(serverId: Long): Either<WorkerMcpClientDiscoverToolsError, List<Tool>> {
            discoverCalls += 1
            return discoverToolsResult
        }

        /**
         * @param serverId Persisted local MCP server identifier.
         * @return Current connected flag.
         */
        override fun isClientConnected(serverId: Long): Boolean = connected

        /**
         * No-op close for test fixture.
         */
        override suspend fun close() = Unit
    }

    companion object {
        /**
         * Shared server ID used by runtime test fixtures.
         */
        private const val DEFAULT_SERVER_ID: Long = 7L
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

/**
 * Builds a minimal local MCP server config fixture.
 *
 * @param serverId Persisted local MCP server identifier.
 * @return Server config fixture.
 */
private fun localServerConfig(serverId: Long): LocalMCPServerDto = LocalMCPServerDto(
    id = serverId,
    userId = 1L,
    workerId = 1L,
    name = "test-server",
    description = null,
    command = "echo",
    arguments = listOf("ok"),
    workingDirectory = null,
    isEnabled = true,
    autoStartOnEnable = false,
    autoStartOnLaunch = false,
    autoStopAfterInactivitySeconds = null,
    toolNamePrefix = null,
    environmentVariables = listOf(LocalMCPEnvironmentVariableDto("A", "B")),
    secretEnvironmentVariables = emptyList(),
    createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2025-01-01T00:00:00Z")
)


