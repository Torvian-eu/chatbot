package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpDiscoveredToolData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.LocalMCPToolDefinitionService
import eu.torvian.chatbot.server.service.core.RefreshResult
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

