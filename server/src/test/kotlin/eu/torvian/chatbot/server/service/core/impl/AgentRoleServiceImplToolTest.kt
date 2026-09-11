package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Tests for [AgentRoleServiceImpl] attached tool ids: ownership validation and join-table persistence. */
class AgentRoleServiceImplToolTest : AgentRoleServiceImplTestBase() {

    @Test
    fun `createRole should reject a tool id that does not exist`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns no tools at all, so any attached id is missing/foreign.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns emptyList()

        val request = validRequest().copy(toolIds = setOf(99L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }
    @Test
    fun `createRole should reject a server built-in tool owned by another user`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns their own server built-in row (54L) but not the foreign 55L.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(
            ServerBuiltInToolDefinition(
                id = 54L,
                name = "list_agent_roles",
                description = "Lists agent roles",
                config = buildJsonObject { },
                inputSchema = buildJsonObject { put("type", "object") },
                outputSchema = null,
                isEnabled = true,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                userId = userId,
                builtInToolName = "list_agent_roles"
            )
        )

        val request = validRequest().copy(toolIds = setOf(55L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }
    @Test
    fun `createRole should reject an MCP tool of another user's server`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns MCP tool 1L (their own server); 2L belongs to another user's server.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(
            LocalMCPToolDefinition(
                id = 1L,
                name = "tool",
                description = "A tool",
                config = buildJsonObject { },
                inputSchema = buildJsonObject { },
                outputSchema = null,
                isEnabled = true,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                serverId = 1L,
                mcpToolName = "tool"
            )
        )

        val request = validRequest().copy(toolIds = setOf(2L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }
    @Test
    fun `createRole should reject a built-in tool of another user's worker`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The caller owns worker built-in 3L (their own worker); 4L belongs to another user's worker.
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(
            BuiltInWorkerToolDefinition(
                id = 3L,
                name = "read_text_file",
                description = "Reads a file",
                config = buildJsonObject { },
                inputSchema = buildJsonObject { put("type", "object") },
                outputSchema = null,
                isEnabled = true,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                workerId = 1L,
                builtInToolName = "read_text_file"
            )
        )

        val request = validRequest().copy(toolIds = setOf(4L))
        val result = service.createRole(userId, request)

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.ToolNotFound>(result.leftOrNull())
    }
    @Test
    fun `updateRole should persist the tool set via the join table on success`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        val tool = LocalMCPToolDefinition(
            id = 1L,
            name = "tool",
            description = "A tool",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            serverId = 1L,
            mcpToolName = "tool"
        )
        coEvery { toolDefinitionDao.getToolsForUser(userId) } returns listOf(tool, tool.copy(id = 2L))
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            displayName = "Architect",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = setOf(1L, 2L),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // The update rewrites the whole tool set (full-replacement semantics preserved).
        coVerify(exactly = 1) { agentRoleToolDao.replaceToolsForRole(1L, setOf(1L, 2L)) }
    }
}
