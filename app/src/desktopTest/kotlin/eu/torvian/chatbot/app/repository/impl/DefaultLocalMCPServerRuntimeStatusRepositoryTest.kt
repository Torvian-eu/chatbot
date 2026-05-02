package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests API-backed runtime-status caching behavior in [DefaultLocalMCPServerRuntimeStatusRepository].
 */
class DefaultLocalMCPServerRuntimeStatusRepositoryTest {
    /**
     * Mocked Local MCP server API.
     */
    private val api: LocalMCPServerApi = mockk()

    /**
     * Subject under test.
     */
    private val repository = DefaultLocalMCPServerRuntimeStatusRepository(api)

    /**
     * Verifies list loading updates the cache with statuses indexed by server ID.
     */
    @Test
    fun `loadRuntimeStatuses caches statuses by server id`() = runTest {
        val statuses = listOf(
            LocalMcpServerRuntimeStatusDto(serverId = 1L, state = LocalMcpServerRuntimeStateDto.RUNNING),
            LocalMcpServerRuntimeStatusDto(serverId = 2L, state = LocalMcpServerRuntimeStateDto.STOPPED)
        )
        coEvery { api.listRuntimeStatuses() } returns Either.Right(statuses)

        val result = repository.loadRuntimeStatuses()

        assertIs<Either.Right<Unit>>(result)
        val state = repository.runtimeStatuses.value
        assertIs<DataState.Success<Map<Long, LocalMcpServerRuntimeStatusDto>>>(state)
        assertEquals(2, state.data.size)
        assertEquals(LocalMcpServerRuntimeStateDto.RUNNING, state.data[1L]?.state)
    }

    /**
     * Verifies single-status loading merges data into the existing cache.
     */
    @Test
    fun `loadRuntimeStatus merges single status into cache`() = runTest {
        coEvery { api.listRuntimeStatuses() } returns Either.Right(
            listOf(LocalMcpServerRuntimeStatusDto(serverId = 1L, state = LocalMcpServerRuntimeStateDto.RUNNING))
        )
        coEvery { api.getRuntimeStatus(2L) } returns Either.Right(
            LocalMcpServerRuntimeStatusDto(serverId = 2L, state = LocalMcpServerRuntimeStateDto.ERROR)
        )

        repository.loadRuntimeStatuses()
        val singleResult = repository.loadRuntimeStatus(serverId = 2L)

        assertIs<Either.Right<LocalMcpServerRuntimeStatusDto>>(singleResult)
        val state = repository.runtimeStatuses.value
        assertIs<DataState.Success<Map<Long, LocalMcpServerRuntimeStatusDto>>>(state)
        assertEquals(2, state.data.size)
        assertEquals(LocalMcpServerRuntimeStateDto.ERROR, state.data[2L]?.state)
    }

    /**
     * Verifies API failures are mapped to repository errors.
     */
    @Test
    fun `loadRuntimeStatuses maps api errors`() = runTest {
        coEvery { api.listRuntimeStatuses() } returns Either.Left(
            eu.torvian.chatbot.app.service.api.ApiResourceError.NetworkError("offline", null)
        )

        val result = repository.loadRuntimeStatuses()

        val error = assertIs<Either.Left<RepositoryError>>(result).value
        assertIs<RepositoryError.DataFetchError>(error)
    }
}


