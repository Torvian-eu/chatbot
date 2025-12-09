package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LocalMCPToolDefinitionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [LocalMCPToolDefinitionDao]:
 * - Creating local MCP tools with linkage to MCP servers
 * - Getting local MCP tools by ID and by server ID
 * - Updating MCP-specific fields
 * - Deleting local MCP tools
 * - Deleting all local MCP tools for a server
 * - Cascade delete behavior
 * - MCP tool name mappings
 * - isEnabledByDefault flag handling
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class LocalMCPToolDefinitionDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var localMCPToolDefinitionDao: LocalMCPToolDefinitionDao
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var localMCPServerDao: LocalMCPServerDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        localMCPToolDefinitionDao = container.get()
        toolDefinitionDao = container.get()
        localMCPServerDao = container.get()
        testDataManager = container.get()

        // Create required tables (must be explicit - setup() only creates tables it needs)
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.LOCAL_MCP_SERVERS,
                Table.TOOL_DEFINITIONS,
                Table.LOCAL_MCP_TOOL_DEFINITIONS
            )
        )

        // Setup test data with users (required for foreign key constraints)
        testDataManager.setup(
            TestDataSet(
                users = listOf(TestDefaults.user1, TestDefaults.user2)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // Helper function to create a test tool definition
    private suspend fun createTestTool(name: String): ToolDefinition {
        return toolDefinitionDao.insertToolDefinition(
            name = name,
            description = "Test tool: $name",
            type = ToolType.MCP_LOCAL,
            config = buildJsonObject {},
            inputSchema = buildJsonObject {},
            outputSchema = null,
            isEnabled = true
        )
    }

    @Test
    fun `createLocalMCPTool - successfully creates local MCP tool with linkage`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")

        // When
        val result = localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = null,
            isEnabledByDefault = null
        )

        // Then
        assertTrue(result.isRight())
        val tool = localMCPToolDefinitionDao.getToolById(toolDef.id)
        assertTrue(tool.isRight())
        assertEquals(serverId, tool.getOrNull()?.serverId)
        assertEquals(toolDef.name, tool.getOrNull()?.name)
    }

    @Test
    fun `createLocalMCPTool - successfully creates local MCP tool with mcpToolName`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")
        val mcpToolName = "original_mcp_name"

        // When
        val result = localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = mcpToolName,
            isEnabledByDefault = null
        )

        // Then
        assertTrue(result.isRight())
        val tool = localMCPToolDefinitionDao.getToolById(toolDef.id).getOrNull()
        assertNotNull(tool)
        assertEquals(mcpToolName, tool.mcpToolName)
    }

    @Test
    fun `createLocalMCPTool - successfully creates local MCP tool with isEnabledByDefault true`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")

        // When
        val result = localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = null,
            isEnabledByDefault = true
        )

        // Then
        assertTrue(result.isRight())
        val tool = localMCPToolDefinitionDao.getToolById(toolDef.id).getOrNull()
        assertNotNull(tool)
        assertEquals(true, tool.isEnabledByDefault)
    }

    @Test
    fun `createLocalMCPTool - successfully creates local MCP tool with isEnabledByDefault false`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")

        // When
        val result = localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = null,
            isEnabledByDefault = false
        )

        // Then
        assertTrue(result.isRight())
        val tool = localMCPToolDefinitionDao.getToolById(toolDef.id).getOrNull()
        assertNotNull(tool)
        assertEquals(false, tool.isEnabledByDefault)
    }

    @Test
    fun `createLocalMCPTool - isEnabledByDefault defaults to null when not specified`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")

        // When
        val result = localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = null,
            isEnabledByDefault = null
        )

        // Then
        assertTrue(result.isRight())
        val tool = localMCPToolDefinitionDao.getToolById(toolDef.id).getOrNull()
        assertNotNull(tool)
        assertNull(tool.isEnabledByDefault)
    }

    @Test
    fun `getLocalMCPToolsByServerId - returns all tools for a server`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool1 = createTestTool("tool1")
        val tool2 = createTestTool("tool2")

        localMCPToolDefinitionDao.insertTool(tool1.id, serverId)
        localMCPToolDefinitionDao.insertTool(tool2.id, serverId)

        // When
        val tools = localMCPToolDefinitionDao.getToolsByServerId(serverId)

        // Then
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.id == tool1.id })
        assertTrue(tools.any { it.id == tool2.id })
    }

    @Test
    fun `getLocalMCPToolById - returns correct tool`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")
        val mcpToolName = "original_name"
        localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = mcpToolName,
            isEnabledByDefault = true
        )

        // When
        val result = localMCPToolDefinitionDao.getToolById(toolDef.id)

        // Then
        assertTrue(result.isRight())
        val tool = result.getOrNull()
        assertNotNull(tool)
        assertEquals(toolDef.id, tool.id)
        assertEquals(serverId, tool.serverId)
        assertEquals(mcpToolName, tool.mcpToolName)
        assertEquals(true, tool.isEnabledByDefault)
    }

    @Test
    fun `deleteLocalMCPToolsByServerId - removes all tools for server`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool1 = createTestTool("tool1")
        val tool2 = createTestTool("tool2")
        localMCPToolDefinitionDao.insertTool(tool1.id, serverId)
        localMCPToolDefinitionDao.insertTool(tool2.id, serverId)

        // When
        val deletedCount = localMCPToolDefinitionDao.deleteToolsByServerId(serverId)

        // Then
        assertEquals(2, deletedCount)
        val tools = localMCPToolDefinitionDao.getToolsByServerId(serverId)
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `cascade delete - deleting server removes local MCP tools`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.insertTool(tool.id, serverId)

        // When
        localMCPServerDao.deleteById(serverId)

        // Then
        val result = localMCPToolDefinitionDao.getToolById(tool.id)
        assertTrue(result.isLeft())
    }

    @Test
    fun `cascade delete - deleting tool removes local MCP tools`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.insertTool(tool.id, serverId)

        // When
        toolDefinitionDao.deleteToolDefinition(tool.id)

        // Then
        val result = localMCPToolDefinitionDao.getToolById(tool.id)
        assertTrue(result.isLeft())
    }

    @Test
    fun `getLocalMCPToolsByServerId - returns empty list when server has no tools`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)

        // When
        val tools = localMCPToolDefinitionDao.getToolsByServerId(serverId)

        // Then
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `getLocalMCPToolById - returns NotFound when local MCP tool does not exist`() = runTest {
        // Given
        val tool = createTestTool("unlinked_tool")

        // When
        val result = localMCPToolDefinitionDao.getToolById(tool.id)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `deleteLocalMCPToolsByServerId - returns zero when server has no tools`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)

        // When
        val deletedCount = localMCPToolDefinitionDao.deleteToolsByServerId(serverId)

        // Then
        assertEquals(0, deletedCount)
    }

    @Test
    fun `createLocalMCPTool - returns DuplicateLinkage error when tool already linked`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.insertTool(tool.id, serverId)

        // When
        val result = localMCPToolDefinitionDao.insertTool(tool.id, serverId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `createLocalMCPTool - returns ReferencedEntityNotFound when tool does not exist`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val nonExistentToolId = 99999L

        // When
        val result = localMCPToolDefinitionDao.insertTool(nonExistentToolId, serverId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `createLocalMCPTool - returns ReferencedEntityNotFound when server does not exist`() = runTest {
        // Given
        val tool = createTestTool("test_tool")
        val nonExistentServerId = 99999L

        // When
        val result = localMCPToolDefinitionDao.insertTool(tool.id, nonExistentServerId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `updateLocalMCPToolFields - successfully updates MCP-specific fields`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = tool.id,
            mcpServerId = serverId,
            mcpToolName = "old_name",
            isEnabledByDefault = false
        )

        // When
        val result = localMCPToolDefinitionDao.updateTool(
            toolDefinitionId = tool.id,
            mcpToolName = "new_name",
            isEnabledByDefault = true
        )

        // Then
        assertTrue(result.isRight())
        val updated = localMCPToolDefinitionDao.getToolById(tool.id).getOrNull()
        assertNotNull(updated)
        assertEquals("new_name", updated.mcpToolName)
        assertEquals(true, updated.isEnabledByDefault)
    }

    @Test
    fun `updateLocalMCPToolFields - returns NotFound when tool does not exist`() = runTest {
        // Given
        val tool = createTestTool("test_tool")

        // When
        val result = localMCPToolDefinitionDao.updateTool(
            toolDefinitionId = tool.id,
            mcpToolName = "new_name",
            isEnabledByDefault = true
        )

        // Then
        assertTrue(result.isLeft())
    }
}

