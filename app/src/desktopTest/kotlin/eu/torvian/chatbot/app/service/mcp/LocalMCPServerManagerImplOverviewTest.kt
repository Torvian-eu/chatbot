package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.LocalMCPServerRepository
import eu.torvian.chatbot.app.repository.LocalMCPServerRuntimeStatusRepository
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies Local MCP overview aggregation uses runtime-status repository snapshots.
 */
class LocalMCPServerManagerImplOverviewTest {
    /**
     * Verifies overviews are enriched from runtime status snapshots instead of local client map state.
     */
    @Test
    fun `serverOverviews uses runtime status repository data`() = runTest {
        val now = Clock.System.now()
        val server = LocalMCPServerDto(
            id = 9L,
            userId = 1L,
            workerId = 2L,
            name = "Filesystem",
            command = "npx",
            createdAt = now,
            updatedAt = now
        )
        val tool = LocalMCPToolDefinition(
            id = 91L,
            name = "fs_list",
            description = "Lists files",
            inputSchema = buildJsonObject { },
            outputSchema = null,
            serverId = server.id,
            mcpToolName = "list_files",
            createdAt = now,
            updatedAt = now,
            config = buildJsonObject { },
            isEnabled = true
        )
        val runtimeStatus = LocalMcpServerRuntimeStatusDto(
            serverId = server.id,
            state = LocalMcpServerRuntimeStateDto.RUNNING,
            connectedAt = now,
            lastActivityAt = now
        )

        val serverState = MutableStateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>>(
            DataState.Success(listOf(server))
        )
        val runtimeStatusState = MutableStateFlow<DataState<RepositoryError, Map<Long, LocalMcpServerRuntimeStatusDto>>>(
            DataState.Success(mapOf(server.id to runtimeStatus))
        )
        val toolState = MutableStateFlow<DataState<RepositoryError, Map<Long, List<LocalMCPToolDefinition>>>>(
            DataState.Success(mapOf(server.id to listOf(tool)))
        )

        val serverRepository = mockk<LocalMCPServerRepository>()
        every { serverRepository.servers } returns serverState

        val runtimeStatusRepository = mockk<LocalMCPServerRuntimeStatusRepository>()
        every { runtimeStatusRepository.runtimeStatuses } returns runtimeStatusState

        val toolRepository = mockk<LocalMCPToolRepository>()
        every { toolRepository.mcpTools } returns toolState

        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.clients } returns flowOf(emptyMap<Long, MCPClient>())

        val manager = LocalMCPServerManagerImpl(
            serverRepository = serverRepository,
            runtimeStatusRepository = runtimeStatusRepository,
            toolRepository = toolRepository,
            mcpClientService = mcpClientService
        )

        val result = manager.serverOverviews
            .map { state ->
                assertIs<DataState.Success<List<LocalMCPServerOverview>>>(state)
                state.data
            }
            .first()

        assertEquals(1, result.size)
        val overview = result.single()
        assertEquals(runtimeStatus, overview.runtimeStatus)
        assertEquals(1, overview.tools?.size)
        assertTrue(overview.isConnected)

        runtimeStatusState.value = DataState.Success(
            mapOf(server.id to runtimeStatus.copy(state = LocalMcpServerRuntimeStateDto.STOPPED, connectedAt = null))
        )
        val disconnected = manager.serverOverviews
            .map { state ->
                assertIs<DataState.Success<List<LocalMCPServerOverview>>>(state)
                state.data.single().isConnected
            }
            .first()
        assertFalse(disconnected)
    }
}



