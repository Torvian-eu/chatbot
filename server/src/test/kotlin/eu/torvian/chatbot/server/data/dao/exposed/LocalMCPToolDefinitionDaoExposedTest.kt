package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.getOrElse
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LocalMCPToolDefinitionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [LocalMCPToolDefinitionDao]:
 * - Creating linkages between tools and MCP servers
 * - Getting tool IDs by server ID
 * - Getting server ID by tool ID
 * - Deleting linkages
 * - Deleting all linkages for a server
 * - Checking if a tool is linked to a server
 * - Getting MCP tool name mappings
 * - Cascade delete behavior
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
            isEnabled = true,
            isEnabledByDefault = null
        ).getOrElse { throw IllegalStateException("Failed to create test tool") }
    }

    @Test
    fun `createLinkage - successfully creates linkage between tool and server`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val toolDef = createTestTool("test_tool")

        // When
        val result = localMCPToolDefinitionDao.createLinkage(
            toolDefinitionId = toolDef.id,
            mcpServerId = serverId,
            mcpToolName = null
        )

        // Then
        assertTrue(result.isRight())
        assertTrue(localMCPToolDefinitionDao.isLinked(toolDef.id, serverId))
    }

    @Test
    fun `getToolIdsByServerId - returns all tools for a server`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool1 = createTestTool("tool1")
        val tool2 = createTestTool("tool2")

        localMCPToolDefinitionDao.createLinkage(tool1.id, serverId)
        localMCPToolDefinitionDao.createLinkage(tool2.id, serverId)

        // When
        val toolIds = localMCPToolDefinitionDao.getToolIdsByServerId(serverId)

        // Then
        assertEquals(2, toolIds.size)
        assertTrue(toolIds.contains(tool1.id))
        assertTrue(toolIds.contains(tool2.id))
    }

    @Test
    fun `getServerIdByToolId - returns correct server ID`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId)

        // When
        val result = localMCPToolDefinitionDao.getServerIdByToolId(tool.id)

        // Then
        assertTrue(result.isRight())
        assertEquals(serverId, result.getOrNull())
    }

    @Test
    fun `deleteLinkage - successfully removes linkage`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId)

        // When
        val result = localMCPToolDefinitionDao.deleteLinkage(tool.id, serverId)

        // Then
        assertTrue(result.isRight())
        assertFalse(localMCPToolDefinitionDao.isLinked(tool.id, serverId))
    }

    @Test
    fun `deleteAllLinkagesForServer - removes all linkages for server`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool1 = createTestTool("tool1")
        val tool2 = createTestTool("tool2")
        localMCPToolDefinitionDao.createLinkage(tool1.id, serverId)
        localMCPToolDefinitionDao.createLinkage(tool2.id, serverId)

        // When
        val deletedCount = localMCPToolDefinitionDao.deleteAllLinkagesForServer(serverId)

        // Then
        assertEquals(2, deletedCount)
        val toolIds = localMCPToolDefinitionDao.getToolIdsByServerId(serverId)
        assertTrue(toolIds.isEmpty())
    }

    @Test
    fun `cascade delete - deleting server removes tool linkages`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId)

        // When
        localMCPServerDao.deleteById(serverId)

        // Then
        assertFalse(localMCPToolDefinitionDao.isLinked(tool.id, serverId))
    }

    @Test
    fun `cascade delete - deleting tool removes tool linkages`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId)

        // When
        toolDefinitionDao.deleteToolDefinition(tool.id)

        // Then
        val result = localMCPToolDefinitionDao.getServerIdByToolId(tool.id)
        assertTrue(result.isLeft())
    }

    @Test
    fun `getMcpToolName - returns null when no name mapping exists`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId, mcpToolName = null)

        // When
        val result = localMCPToolDefinitionDao.getMcpToolName(tool.id)

        // Then
        assertTrue(result.isRight())
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `getMcpToolName - returns mapped name when exists`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        val mappedName = "original_mcp_tool_name"
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId, mcpToolName = mappedName)

        // When
        val result = localMCPToolDefinitionDao.getMcpToolName(tool.id)

        // Then
        assertTrue(result.isRight())
        assertEquals(mappedName, result.getOrNull())
    }

    @Test
    fun `getMcpToolName - returns NotFound when tool is not linked`() = runTest {
        // Given
        val tool = createTestTool("unlinked_tool")

        // When
        val result = localMCPToolDefinitionDao.getMcpToolName(tool.id)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `getToolIdsByServerId - returns empty list when server has no tools`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)

        // When
        val toolIds = localMCPToolDefinitionDao.getToolIdsByServerId(serverId)

        // Then
        assertTrue(toolIds.isEmpty())
    }

    @Test
    fun `getServerIdByToolId - returns NotFound when tool is not linked`() = runTest {
        // Given
        val tool = createTestTool("unlinked_tool")

        // When
        val result = localMCPToolDefinitionDao.getServerIdByToolId(tool.id)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `deleteLinkage - returns NotFound when linkage does not exist`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        // Note: No linkage created

        // When
        val result = localMCPToolDefinitionDao.deleteLinkage(tool.id, serverId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `deleteAllLinkagesForServer - returns zero when server has no linkages`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)

        // When
        val deletedCount = localMCPToolDefinitionDao.deleteAllLinkagesForServer(serverId)

        // Then
        assertEquals(0, deletedCount)
    }

    @Test
    fun `isLinked - returns false when no linkage exists`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")

        // When
        val isLinked = localMCPToolDefinitionDao.isLinked(tool.id, serverId)

        // Then
        assertFalse(isLinked)
    }

    @Test
    fun `createLinkage - returns DuplicateLinkage error when linkage already exists`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val tool = createTestTool("test_tool")
        localMCPToolDefinitionDao.createLinkage(tool.id, serverId)

        // When
        val result = localMCPToolDefinitionDao.createLinkage(tool.id, serverId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `createLinkage - returns ReferencedEntityNotFound when tool does not exist`() = runTest {
        // Given
        val userId = 1L
        val serverId = localMCPServerDao.generateId(userId)
        val nonExistentToolId = 99999L

        // When
        val result = localMCPToolDefinitionDao.createLinkage(nonExistentToolId, serverId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `createLinkage - returns ReferencedEntityNotFound when server does not exist`() = runTest {
        // Given
        val tool = createTestTool("test_tool")
        val nonExistentServerId = 99999L

        // When
        val result = localMCPToolDefinitionDao.createLinkage(tool.id, nonExistentServerId)

        // Then
        assertTrue(result.isLeft())
    }

    @Test
    fun `multiple servers can link to same tool`() = runTest {
        // Given
        val userId = 1L
        val server1 = localMCPServerDao.generateId(userId)
        val server2 = localMCPServerDao.generateId(userId)
        val tool = createTestTool("shared_tool")

        // When
        localMCPToolDefinitionDao.createLinkage(tool.id, server1)
        localMCPToolDefinitionDao.createLinkage(tool.id, server2)

        // Then
        assertTrue(localMCPToolDefinitionDao.isLinked(tool.id, server1))
        assertTrue(localMCPToolDefinitionDao.isLinked(tool.id, server2))
    }
}

