package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests API-backed behavior of [DefaultLocalMCPServerRepository].
 */
class DefaultLocalMCPServerRepositoryTest {
    private val api: LocalMCPServerApi = mockk()
    private val repository = DefaultLocalMCPServerRepository(api)
    private val now = Clock.System.now()

    /**
     * Builds a Local MCP server fixture used by repository tests.
     *
     * @param id Server identifier.
     * @param enabled Whether the server starts enabled.
     * @return Test server configuration with regular and secret env vars.
     */
    private fun server(id: Long, enabled: Boolean = true): LocalMCPServerDto = LocalMCPServerDto(
        id = id,
        userId = 1L,
        workerId = 7L,
        name = "Server-$id",
        command = "npx",
        arguments = listOf("-y", "tool"),
        environmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_URL", "https://example.test")),
        secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("TOKEN", "abc")),
        isEnabled = enabled,
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun `loadServers fetches from API and caches result`() = runTest {
        val expected = listOf(server(11L), server(12L))
        coEvery { api.getServers() } returns Either.Right(expected)

        repository.loadServers(userId = 1L)

        val state = repository.servers.value
        assertIs<DataState.Success<List<LocalMCPServerDto>>>(state)
        assertEquals(expected, state.data)
        coVerify(exactly = 1) { api.getServers() }
    }

    @Test
    fun `create update delete mutate in-memory cache using server responses`() = runTest {
        val existing = server(20L)
        val created = server(21L)
        val updated = created.copy(
            name = "Updated",
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("TOKEN", "changed"))
        )

        coEvery { api.getServers() } returns Either.Right(listOf(existing))
        coEvery { api.createServer(any()) } returns Either.Right(created)
        coEvery { api.updateServer(created.id, any()) } returns Either.Right(updated)
        coEvery { api.deleteServer(updated.id) } returns Either.Right(Unit)

        repository.loadServers(userId = 1L)
        repository.createServer(
            workerId = created.workerId,
            name = created.name,
            description = created.description,
            command = created.command,
            arguments = created.arguments,
            environmentVariables = created.environmentVariables,
            secretEnvironmentVariables = created.secretEnvironmentVariables,
            workingDirectory = created.workingDirectory,
            isEnabled = created.isEnabled,
            autoStartOnEnable = created.autoStartOnEnable,
            autoStartOnLaunch = created.autoStartOnLaunch,
            autoStopAfterInactivitySeconds = created.autoStopAfterInactivitySeconds,
            toolNamePrefix = created.toolNamePrefix
        )
        repository.updateServer(created)
        repository.deleteServer(updated.id)

        val state = repository.servers.value
        assertIs<DataState.Success<List<LocalMCPServerDto>>>(state)
        assertEquals(listOf(existing), state.data)
    }

    @Test
    fun `loadServers maps API errors to repository errors`() = runTest {
        val apiError = ApiResourceError.ServerError(
            apiError = apiError(CommonApiErrorCodes.NOT_FOUND, "No servers for user")
        )
        coEvery { api.getServers() } returns Either.Left(apiError)

        repository.loadServers(userId = 1L)

        val state = repository.servers.value
        assertIs<DataState.Error<RepositoryError>>(state)
        assertIs<RepositoryError.DataFetchError>(state.error)
        assertTrue(state.error.message.contains("Failed to load MCP servers"))
    }

    /**
     * Ensures runtime-control repository methods delegate to matching API methods.
     */
    @Test
    fun `runtime control methods delegate to server api`() = runTest {
        val testResponse = TestLocalMCPServerConnectionResponse(
            serverId = 22L,
            success = true,
            discoveredToolCount = 3,
            message = "dummy"
        )
        val refreshResponse = RefreshMCPToolsResponse(
            addedTools = emptyList(),
            updatedTools = emptyList(),
            deletedTools = emptyList()
        )

        coEvery { api.startServer(22L) } returns Either.Right(Unit)
        coEvery { api.stopServer(22L) } returns Either.Right(Unit)
        coEvery { api.testConnection(22L) } returns Either.Right(testResponse)
        coEvery { api.refreshTools(22L) } returns Either.Right(refreshResponse)

        val startResult = repository.startServer(22L)
        val stopResult = repository.stopServer(22L)
        val testResult = repository.testConnection(22L)
        val refreshResult = repository.refreshTools(22L)

        assertIs<Either.Right<Unit>>(startResult)
        assertIs<Either.Right<Unit>>(stopResult)
        assertIs<Either.Right<TestLocalMCPServerConnectionResponse>>(testResult)
        assertIs<Either.Right<RefreshMCPToolsResponse>>(refreshResult)
        assertEquals(3, testResult.value.discoveredToolCount)
        assertEquals(0, refreshResult.value.addedTools.size)

        coVerify(exactly = 1) { api.startServer(22L) }
        coVerify(exactly = 1) { api.stopServer(22L) }
        coVerify(exactly = 1) { api.testConnection(22L) }
        coVerify(exactly = 1) { api.refreshTools(22L) }
    }

    /**
     * Verifies draft connection testing delegates to the server-owned draft-test API.
     */
    @Test
    fun `testConnectionForNewServer delegates to draft test api`() = runTest {
        val request = TestLocalMCPServerDraftConnectionRequest(
            workerId = 77L,
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
            discoveredToolCount = 4,
            message = "draft ok"
        )
        coEvery { api.testDraftConnection(request) } returns Either.Right(response)

        val result = repository.testConnectionForNewServer(request)

        assertIs<Either.Right<TestLocalMCPServerConnectionResponse>>(result)
        assertEquals(response, result.value)
        coVerify(exactly = 1) { api.testDraftConnection(request) }
    }
}

