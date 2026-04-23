package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.LocalMCPServerRuntimeStatusRepository
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.BeforeEach

/**
 * Verifies the app-side manager delegates draft testing and deletion to server-owned repository paths.
 */
class LocalMCPServerManagerImplRefactorTest {
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
}



