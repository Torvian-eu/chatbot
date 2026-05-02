package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.LocalMCPServerRuntimeStatusRepository
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.mcp.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Verifies the app-side manager delegates operations to server-owned repository paths
 * and refreshes local caches where appropriate.
 */
class LocalMCPServerManagerImplOperationsTest {
    private val serverRepository: LocalMCPServerRepository = mockk()
    private val runtimeStatusRepository: LocalMCPServerRuntimeStatusRepository = mockk()
    private val toolRepository: LocalMCPToolRepository = mockk(relaxed = true)

    private lateinit var manager: LocalMCPServerManagerImpl

    @BeforeEach
    fun setUp() {
        every { serverRepository.servers } returns MutableStateFlow<DataState<RepositoryError, List<eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto>>>(
            DataState.Success(emptyList())
        )
        every { runtimeStatusRepository.runtimeStatuses } returns MutableStateFlow<DataState<RepositoryError, Map<Long, eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto>>>(
            DataState.Success(emptyMap())
        )
        every { toolRepository.mcpTools } returns MutableStateFlow<DataState<RepositoryError, Map<Long, List<eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition>>>>(
            DataState.Success(emptyMap())
        )

        manager = LocalMCPServerManagerImpl(
            serverRepository = serverRepository,
            runtimeStatusRepository = runtimeStatusRepository,
            toolRepository = toolRepository
        )
    }

    /**
     * Verifies draft connection testing is delegated to the repository with the shared request payload.
     */
    @Test
    fun `testConnectionForNewServer delegates to repository draft test`() = runTest {
        val request = TestLocalMCPServerDraftConnectionRequest(
            workerId = 41L,
            name = "Draft Filesystem",
            command = "npx",
            arguments = listOf("-y", "tool"),
            workingDirectory = "C:/data",
            environmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_URL", "https://example.test")),
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("TOKEN", "secret"))
        )
        val response = TestLocalMCPServerConnectionResponse(
            serverId = null,
            success = true,
            discoveredToolCount = 7,
            message = "draft ok"
        )
        coEvery { serverRepository.testConnectionForNewServer(request) } returns Either.Right(response)

        val result = manager.testConnectionForNewServer(
            workerId = request.workerId,
            name = request.name,
            command = request.command,
            arguments = request.arguments,
            environmentVariables = request.environmentVariables,
            secretEnvironmentVariables = request.secretEnvironmentVariables,
            workingDirectory = request.workingDirectory
        )

        assertIs<Either.Right<Int>>(result)
        assertEquals(7, result.value)
        coVerify(exactly = 1) { serverRepository.testConnectionForNewServer(request) }
    }

    /**
     * Verifies delete only calls the repository and clears the local tool cache.
     */
    @Test
    fun `deleteServer delegates to repository and clears tool cache`() = runTest {
        coEvery { serverRepository.deleteServer(44L) } returns Either.Right(Unit)

        val result = manager.deleteServer(44L)

        assertIs<Either.Right<Unit>>(result)
        coVerify(exactly = 1) { serverRepository.deleteServer(44L) }
        coVerify(exactly = 1) { toolRepository.removeToolsFromCache(44L) }
    }

    /**
     * Verifies startServer delegates to the repository and refreshes the runtime status.
     */
    @Test
    fun `startServer delegates to repository and loads runtime status`() = runTest {
        coEvery { serverRepository.startServer(55L) } returns Either.Right(Unit)
        coEvery { runtimeStatusRepository.loadRuntimeStatus(55L) } returns Either.Right(
            LocalMcpServerRuntimeStatusDto(
                serverId = 55L,
                state = LocalMcpServerRuntimeStateDto.RUNNING,
                connectedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            )
        )

        val result = manager.startServer(55L)

        assertIs<Either.Right<Unit>>(result)
        coVerify(exactly = 1) { serverRepository.startServer(55L) }
        coVerify(exactly = 1) { runtimeStatusRepository.loadRuntimeStatus(55L) }
    }

    /**
     * Verifies startServer still succeeds when runtime status refresh fails.
     */
    @Test
    fun `startServer returns success even when runtime status load fails`() = runTest {
        coEvery { serverRepository.startServer(55L) } returns Either.Right(Unit)
        coEvery { runtimeStatusRepository.loadRuntimeStatus(55L) } returns Either.Left(
            RepositoryError.OtherError("network error")
        )

        val result = manager.startServer(55L)

        assertIs<Either.Right<Unit>>(result)
        coVerify(exactly = 1) { serverRepository.startServer(55L) }
        coVerify(exactly = 1) { runtimeStatusRepository.loadRuntimeStatus(55L) }
    }

    /**
     * Verifies stopServer delegates to the repository and refreshes the runtime status.
     */
    @Test
    fun `stopServer delegates to repository and loads runtime status`() = runTest {
        coEvery { serverRepository.stopServer(66L) } returns Either.Right(Unit)
        coEvery { runtimeStatusRepository.loadRuntimeStatus(66L) } returns Either.Right(
            LocalMcpServerRuntimeStatusDto(
                serverId = 66L,
                state = LocalMcpServerRuntimeStateDto.STOPPED,
                stoppedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            )
        )

        val result = manager.stopServer(66L)

        assertIs<Either.Right<Unit>>(result)
        coVerify(exactly = 1) { serverRepository.stopServer(66L) }
        coVerify(exactly = 1) { runtimeStatusRepository.loadRuntimeStatus(66L) }
    }

    /**
     * Verifies stopServer still succeeds when runtime status refresh fails.
     */
    @Test
    fun `stopServer returns success even when runtime status load fails`() = runTest {
        coEvery { serverRepository.stopServer(66L) } returns Either.Right(Unit)
        coEvery { runtimeStatusRepository.loadRuntimeStatus(66L) } returns Either.Left(
            RepositoryError.OtherError("network error")
        )

        val result = manager.stopServer(66L)

        assertIs<Either.Right<Unit>>(result)
        coVerify(exactly = 1) { serverRepository.stopServer(66L) }
        coVerify(exactly = 1) { runtimeStatusRepository.loadRuntimeStatus(66L) }
    }
}
