package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.LocalMCPToolDefinitionService
import eu.torvian.chatbot.server.service.core.RefreshResult
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies server-owned Local MCP runtime-control orchestration behavior.
 */
class DefaultLocalMCPRuntimeControlServiceTest {
    /**
     * Local MCP server service fixture.
     */
    private val localMCPServerService: LocalMCPServerService = mockk()

    /**
     * Worker runtime command dispatch fixture.
     */
    private val commandDispatchService: LocalMCPRuntimeCommandDispatchService = mockk()

    /**
     * Persisted MCP tool definition service fixture.
     */
    private val toolDefinitionService: LocalMCPToolDefinitionService = mockk()

    /**
     * Subject under test.
     */
    private val service = DefaultLocalMCPRuntimeControlService(
        localMCPServerService = localMCPServerService,
        localMCPRuntimeCommandDispatchService = commandDispatchService,
        localMCPToolDefinitionService = toolDefinitionService
    )

    /**
     * Verifies `refreshTools` delegates runtime discovery to worker and persists refresh diffs on the server.
     */
    @Test
    fun `refresh tools orchestrates worker discovery then server persistence`() = runTest {
        val server = localServer(toolNamePrefix = "fs_")
        val discoveredTool = WorkerMcpDiscoveredToolData(
            name = "list_files",
            description = "Lists files",
            inputSchema = buildJsonObject {
                put("type", "object")
            },
            outputSchema = null
        )

        coEvery { localMCPServerService.getServerById(userId = 9L, serverId = 7L) } returns server.right()
        coEvery { commandDispatchService.discoverTools(workerId = 3L, serverId = 7L) } returns
            WorkerMcpServerDiscoverToolsResultData(
                serverId = 7L,
                tools = listOf(discoveredTool)
            ).right()

        coEvery {
            toolDefinitionService.refreshMCPTools(
                serverId = 7L,
                currentTools = any()
            )
        } answers {
            val currentTools = secondArg<List<LocalMCPToolDefinition>>()
            RefreshResult(
                addedTools = currentTools,
                updatedTools = emptyList(),
                deletedTools = emptyList()
            ).right()
        }

        val response = service.refreshTools(userId = 9L, serverId = 7L).requireRight()

        assertEquals(1, response.addedTools.size)
        assertEquals("fs_list_files", response.addedTools.single().name)
        assertEquals("list_files", response.addedTools.single().mcpToolName)
        assertEquals(7L, response.addedTools.single().serverId)
        assertEquals(emptyJson(), response.addedTools.single().config)
        assertTrue(response.updatedTools.isEmpty())
        assertTrue(response.deletedTools.isEmpty())

        coVerify(exactly = 1) { commandDispatchService.discoverTools(workerId = 3L, serverId = 7L) }
        coVerify(exactly = 1) { toolDefinitionService.refreshMCPTools(serverId = 7L, currentTools = any()) }
    }

    /**
     * Verifies single runtime-status read delegates to worker dispatch and returns worker status data.
     */
    @Test
    fun `get runtime status delegates to worker and returns typed status`() = runTest {
        val server = localServer(toolNamePrefix = null)
        val status = LocalMcpServerRuntimeStatusDto(
            serverId = server.id,
            state = LocalMcpServerRuntimeStateDto.RUNNING,
            connectedAt = Instant.parse("2025-01-01T00:05:00Z")
        )

        coEvery { localMCPServerService.getServerById(userId = 9L, serverId = 7L) } returns server.right()
        coEvery { commandDispatchService.getRuntimeStatus(workerId = 3L, serverId = 7L) } returns
            eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusResultData(
                status = status
            ).right()

        val result = service.getRuntimeStatus(userId = 9L, serverId = 7L).requireRight()

        assertEquals(status, result)
    }

    /**
     * Verifies list runtime-status reads apply deterministic fallback entries when worker is unavailable.
     */
    @Test
    fun `list runtime statuses returns fallback for unavailable worker dispatch`() = runTest {
        val server = localServer(toolNamePrefix = null)
        coEvery { localMCPServerService.getServersByUserId(userId = 9L) } returns listOf(server).right()
        coEvery { commandDispatchService.listRuntimeStatuses(workerId = 3L) } returns
            LocalMCPRuntimeCommandDispatchError.DispatchFailed(
                WorkerCommandDispatchError.WorkerNotConnected(workerId = 3L)
            ).left()

        val result = service.listRuntimeStatuses(userId = 9L).requireRight()

        assertEquals(1, result.size)
        assertEquals(server.id, result.single().serverId)
        assertEquals(LocalMcpServerRuntimeStateDto.STOPPED, result.single().state)
        assertTrue(result.single().errorMessage?.contains("not connected") == true)
    }

    /**
     * Builds a deterministic Local MCP server fixture.
     *
     * @param toolNamePrefix Optional prefix used for persisted tool naming.
     * @return Server fixture.
     */
    private fun localServer(toolNamePrefix: String?): LocalMCPServerDto = LocalMCPServerDto(
        id = 7L,
        userId = 9L,
        workerId = 3L,
        name = "filesystem",
        description = null,
        command = "npx",
        arguments = listOf("-y", "@modelcontextprotocol/server-filesystem"),
        workingDirectory = null,
        isEnabled = true,
        autoStartOnEnable = false,
        autoStartOnLaunch = false,
        autoStopAfterInactivitySeconds = null,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = emptyList(),
        secretEnvironmentVariables = emptyList(),
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2025-01-01T00:00:00Z")
    )

    /**
     * Builds an empty JSON object fixture for stable assertions.
     *
     * @return Empty JSON object.
     */
    private fun emptyJson(): JsonObject = buildJsonObject { }

    /**
     * Returns right value from an [Either] fixture or fails fast.
     *
     * @receiver Either value under assertion.
     * @return Right value.
     */
    private fun <L, R> Either<L, R>.requireRight(): R = fold(
        ifLeft = { error("Expected Right but was Left: $it") },
        ifRight = { it }
    )
}

