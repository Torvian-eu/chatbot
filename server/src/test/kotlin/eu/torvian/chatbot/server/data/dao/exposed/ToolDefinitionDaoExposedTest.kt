package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.tables.UsersTable
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Clock

/**
 * Tests for [ToolDefinitionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ToolDefinitionDao]:
 * - Getting tool definitions by ID
 * - Getting all tool definitions
 * - Getting only enabled tool definitions
 * - Inserting new tool definitions
 * - Updating existing tool definitions
 * - Deleting tool definitions
 * - Handling error cases (not found, duplicate name, etc.)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ToolDefinitionDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var localMCPToolDefinitionDao: LocalMCPToolDefinitionDao
    private lateinit var localMCPServerDao: LocalMCPServerDao
    private lateinit var operatorToolDefinitionDao: OperatorToolDefinitionDao
    private lateinit var serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao
    private lateinit var builtInToolDefinitionDao: BuiltInToolDefinitionDao
    private lateinit var workerDao: WorkerDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        toolDefinitionDao = container.get()
        localMCPToolDefinitionDao = container.get()
        localMCPServerDao = container.get()
        operatorToolDefinitionDao = container.get()
        serverBuiltInToolDefinitionDao = container.get()
        builtInToolDefinitionDao = container.get()
        workerDao = container.get()
        transactionScope = container.get()
        testDataManager = container.get()

        // Create the tool_definitions table
        testDataManager.createAllTables()
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Creates a user-owned MCP server and returns its id, so MCP_LOCAL tools can be linked to it.
     *
     * The joined [ToolDefinitionDao] queries require a linkage row in
     * `local_mcp_tool_definitions` (and therefore a parent MCP server) to reconstruct a
     * [LocalMCPToolDefinition]; without it the join columns are NULL and mapping throws.
     */
    private suspend fun createTestMcpServer(): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        val userId = transactionScope.transaction {
            UsersTable.insert {
                it[username] = "mcp_owner_${now}_${Math.random()}"
                it[passwordHash] = "hash"
                it[email] = "mcp_owner_${now}_${Math.random()}@example.com"
                it[status] = UserStatus.ACTIVE
                it[createdAt] = now
                it[updatedAt] = now
            } get UsersTable.id
        }

        return localMCPServerDao.createServer(
            CreateLocalMCPServerEntity(
                userId = userId.value,
                workerId = 1L,
                name = "test_server",
                description = null,
                command = "echo",
                arguments = emptyList(),
                workingDirectory = null,
                isEnabled = true,
                autoStartOnEnable = false,
                autoStartOnLaunch = false,
                autoStopAfterInactivitySeconds = null,
                toolNamePrefix = null,
                environmentVariables = emptyList(),
                secretEnvironmentVariables = emptyList()
            )
        ).id
    }

    // Helper function to create a test tool definition.
    // Inserts with MCP_LOCAL and links it to a test MCP server, so the joined mapper
    // returns a fully typed LocalMCPToolDefinition.
    private suspend fun createTestTool(
        name: String = "test_tool",
        description: String = "Search the web for information",
        type: ToolType = ToolType.MCP_LOCAL,
        isEnabled: Boolean = true
    ): LocalMCPToolDefinition {
        val config = buildJsonObject {
            put("searchEngine", "duckduckgo")
            put("maxResults", 5)
        }
        val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The search query")
                })
            })
            put("required", "[\"query\"]")
        }

        val entity = toolDefinitionDao.insertToolDefinition(
            name = name,
            description = description,
            type = type,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = isEnabled
        )

        // Link MCP_LOCAL tools to a server so the joined lookup can reconstruct the subtype.
        val serverId = createTestMcpServer()
        localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = entity.id,
            mcpServerId = serverId,
            mcpToolName = name
        )

        return toolDefinitionDao.getToolDefinitionById(entity.id).getOrElse {
            throw AssertionError("Expected created tool to be retrievable as LocalMCPToolDefinition")
        } as LocalMCPToolDefinition
    }

    @Test
    fun `getAllToolDefinitions returns all tools`() = runTest {
        // Setup: Create multiple tools
        createTestTool(name = "web_search")
        createTestTool(name = "calculator", description = "Perform calculations", type = ToolType.MCP_LOCAL)

        // Execute
        val result = toolDefinitionDao.getAllToolDefinitions()

        // Verify
        assertEquals(2, result.size, "Expected 2 tool definitions")
        assertTrue(result.any { it.name == "web_search" }, "Expected web_search tool")
        assertTrue(result.any { it.name == "calculator" }, "Expected calculator tool")
    }

    @Test
    fun `getToolDefinitionById returns tool when exists`() = runTest {
        // Setup
        val created = createTestTool(name = "web_search")

        // Execute
        val result = toolDefinitionDao.getToolDefinitionById(created.id)

        // Verify
        val tool = result.getOrElse { throw AssertionError("Expected Right but got Left: $it") }
        assertEquals(created.id, tool.id)
        assertEquals("web_search", tool.name)
        assertEquals("Search the web for information", tool.description)
        assertEquals(ToolType.MCP_LOCAL, tool.type)
        assertTrue(tool.isEnabled)
    }

    @Test
    fun `getToolDefinitionById returns NotFound when not exists`() = runTest {
        // Execute
        val result = toolDefinitionDao.getToolDefinitionById(999L)

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertIs<ToolDefinitionError.NotFound>(error, "Expected NotFound error")
            assertEquals(999L, error.id)
        }
    }

    @Test
    fun `insertToolDefinition creates new tool`() = runTest {
        // Setup
        val config = buildJsonObject { put("key", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }

        // Execute: insertToolDefinition returns a flat ToolDefinitionEntity (no linkage yet).
        val tool = toolDefinitionDao.insertToolDefinition(
            name = "test_tool",
            description = "A test tool",
            type = ToolType.MCP_LOCAL,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true
        )

        // Verify
        assertTrue(tool.id > 0, "Expected valid ID")
        assertEquals("test_tool", tool.name)
        assertEquals("A test tool", tool.description)
        assertEquals(ToolType.MCP_LOCAL, tool.type)
        assertTrue(tool.isEnabled)
        assertNotNull(tool.createdAt)
        assertNotNull(tool.updatedAt)
    }

    @Test
    fun `insertToolDefinition allows duplicate names`() = runTest {
        // Setup: Create initial tool
        val first = createTestTool(name = "web_search")

        // Execute: Create another tool with the same name (now allowed)
        val config = buildJsonObject { put("key", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }
        val second = toolDefinitionDao.insertToolDefinition(
            name = "web_search",
            description = "Another web search",
            type = ToolType.MCP_LOCAL,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true
        )

        // Link the second tool to a server so the joined read query can reconstruct it.
        val secondServerId = createTestMcpServer()
        localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = second.id,
            mcpServerId = secondServerId,
            mcpToolName = "web_search"
        )

        // Verify: Should succeed since duplicate names are now allowed
        assertEquals("web_search", second.name)
        assertTrue(second.id != first.id, "Should have different IDs")

        // Verify both tools exist
        val allTools = toolDefinitionDao.getAllToolDefinitions()
        assertEquals(2, allTools.count { it.name == "web_search" }, "Expected 2 tools with name 'web_search'")
    }

    @Test
    fun `updateToolDefinition updates all fields`() = runTest {
        // Setup: Create initial tool
        val created = createTestTool(name = "web_search")

        // Execute: Update the tool
        val updatedConfig = buildJsonObject { put("updated", true) }
        val updatedInputSchema = buildJsonObject { put("type", "string") }
        val updatedOutputSchema = buildJsonObject { put("type", "array") }

        val updated = created.copy(
            description = "Updated description",
            config = updatedConfig,
            inputSchema = updatedInputSchema,
            outputSchema = updatedOutputSchema,
            isEnabled = false
        )

        val result = toolDefinitionDao.updateToolDefinition(updated)

        // Verify update succeeded
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify changes were persisted
        val retrieved = toolDefinitionDao.getToolDefinitionById(created.id).getOrElse {
            throw AssertionError("Failed to retrieve updated tool")
        }
        assertEquals("Updated description", retrieved.description)
        assertFalse(retrieved.isEnabled)
        assertNotNull(retrieved.outputSchema)
    }

    @Test
    fun `updateToolDefinition can set nullable fields to null`() = runTest {
        // Setup: Create tool with outputSchema and link it to a server so the joined
        // lookup can reconstruct a typed LocalMCPToolDefinition for the update.
        val config = buildJsonObject { put("key", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }
        val outputSchema = buildJsonObject { put("type", "string") }

        val entity = toolDefinitionDao.insertToolDefinition(
            name = "test_tool",
            description = "Test",
            type = ToolType.MCP_LOCAL,
            config = config,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            isEnabled = true
        )
        val serverId = createTestMcpServer()
        localMCPToolDefinitionDao.insertTool(
            toolDefinitionId = entity.id,
            mcpServerId = serverId,
            mcpToolName = "test_tool"
        )
        val created = toolDefinitionDao.getToolDefinitionById(entity.id).getOrElse {
            throw AssertionError("Expected created tool to be retrievable as LocalMCPToolDefinition")
        } as LocalMCPToolDefinition

        // Execute: Update to set outputSchema to null
        val updated = created.copy(outputSchema = null)
        val result = toolDefinitionDao.updateToolDefinition(updated)

        // Verify
        assertTrue(result.isRight(), "Expected Right (success)")
        val retrieved = toolDefinitionDao.getToolDefinitionById(created.id).getOrElse {
            throw AssertionError("Failed to retrieve updated tool")
        }
        assertEquals(null, retrieved.outputSchema, "Expected outputSchema to be null")
    }

    @Test
    fun `updateToolDefinition returns NotFound when ID not exists`() = runTest {
        // Setup: Create a tool definition object with non-existent ID
        val config = buildJsonObject { put("key", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }
        val nonExistentTool = LocalMCPToolDefinition(
            id = 999L,
            name = "test",
            description = "Test",
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            serverId = 1L,
            mcpToolName = "test"
        )

        // Execute
        val result = toolDefinitionDao.updateToolDefinition(nonExistentTool)

        // Verify
        assertIs<ToolDefinitionError.NotFound>(result.leftOrNull(), "Expected NotFound error")
    }

    @Test
    fun `updateToolDefinition allows duplicate names`() = runTest {
        // Setup: Create two tools
        createTestTool(name = "web_search")
        val tool2 = createTestTool(name = "calculator", type = ToolType.MCP_LOCAL)

        // Execute: Rename tool2 to tool1's name (now allowed)
        val updated = tool2.copy(name = "web_search")
        val result = toolDefinitionDao.updateToolDefinition(updated)

        // Verify: Should succeed since duplicate names are now allowed
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify the update was persisted
        val retrieved = toolDefinitionDao.getToolDefinitionById(tool2.id).getOrElse {
            throw AssertionError("Failed to retrieve updated tool")
        }
        assertEquals("web_search", retrieved.name)

        // Verify both tools have the same name
        val allTools = toolDefinitionDao.getAllToolDefinitions()
        assertEquals(2, allTools.count { it.name == "web_search" }, "Expected 2 tools with name 'web_search'")
    }

    @Test
    fun `deleteToolDefinition removes tool`() = runTest {
        // Setup: Create a tool
        val created = createTestTool(name = "web_search")

        // Execute: Delete the tool
        val deleteResult = toolDefinitionDao.deleteToolDefinition(created.id)

        // Verify deletion succeeded
        assertTrue(deleteResult.isRight(), "Expected Right (success)")

        // Verify tool is gone
        val getResult = toolDefinitionDao.getToolDefinitionById(created.id)
        assertTrue(getResult.isLeft(), "Expected tool to not exist after deletion")
    }

    @Test
    fun `deleteToolDefinition returns NotFound when ID not exists`() = runTest {
        // Execute
        val result = toolDefinitionDao.deleteToolDefinition(999L)

        // Verify
        assertIs<ToolDefinitionError.NotFound>(result.leftOrNull(), "Expected NotFound error")
    }

    @Test
    fun `getEnabledToolDefinitions returns only enabled tools`() = runTest {
        // Setup: Create enabled and disabled tools
        createTestTool(name = "enabled1", isEnabled = true)
        createTestTool(name = "disabled1", isEnabled = false)
        createTestTool(name = "enabled2", isEnabled = true, type = ToolType.MCP_LOCAL)

        // Execute
        val result = toolDefinitionDao.getEnabledToolDefinitions()

        // Verify
        assertEquals(2, result.size, "Expected only 2 enabled tools")
        assertTrue(result.all { it.isEnabled }, "All returned tools should be enabled")
        assertTrue(result.any { it.name == "enabled1" }, "Expected enabled1 tool")
        assertTrue(result.any { it.name == "enabled2" }, "Expected enabled2 tool")
        assertFalse(result.any { it.name == "disabled1" }, "Should not include disabled1 tool")
    }

    /**
     * Creates an OPERATOR tool definition linked to the given user.
     *
     * @param name Public tool name (defaults to the catalog `spawn_agent` name).
     * @param userId Owning user.
     * @return The persisted [OperatorToolDefinition].
     */
    private suspend fun createOperatorTool(
        name: String = OperatorToolCatalog.SPAWN_AGENT_NAME,
        userId: Long
    ): OperatorToolDefinition {
        val entity = toolDefinitionDao.insertToolDefinition(
            name = name,
            description = "Spawns an agent",
            type = ToolType.OPERATOR,
            config = buildJsonObject { },
            inputSchema = OperatorToolCatalog.allTools.single().inputSchema,
            outputSchema = null,
            isEnabled = true
        )
        operatorToolDefinitionDao.insertTool(entity.id, userId)
        return toolDefinitionDao.getToolDefinitionById(entity.id).getOrElse {
            throw AssertionError("Expected operator tool to be retrievable")
        } as OperatorToolDefinition
    }

    @Test
    fun `OPERATOR tool maps to OperatorToolDefinition reading userId from the side table`() = runTest {
        // The linkage table enforces a user FK, so the owning user must exist first.
        transactionScope.transaction {
            UsersTable.insert {
                it[id] = 42L
                it[username] = "operator_owner"
                it[passwordHash] = "hash"
                it[email] = "operator_owner@example.com"
                it[status] = UserStatus.ACTIVE
                it[createdAt] = Clock.System.now().toEpochMilliseconds()
                it[updatedAt] = Clock.System.now().toEpochMilliseconds()
            }
        }
        val tool = createOperatorTool(userId = 42L)

        val retrieved = toolDefinitionDao.getToolDefinitionById(tool.id).getOrElse {
            throw AssertionError("Expected tool to be retrievable")
        }

        assertIs<OperatorToolDefinition>(retrieved)
        assertEquals(ToolType.OPERATOR, retrieved.type)
        assertEquals(42L, retrieved.userId)
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, retrieved.name)
    }

    @Test
    fun `getToolsForUser returns only the user's own operator tools`() = runTest {
        val user1 = TestDefaults.user1
        val user2 = TestDefaults.user2
        transactionScope.transaction {
            UsersTable.insert {
                it[id] = user1.id
                it[username] = user1.username
                it[passwordHash] = "hash"
                it[email] = user1.email
                it[status] = UserStatus.ACTIVE
                it[createdAt] = user1.createdAt.toEpochMilliseconds()
                it[updatedAt] = user1.updatedAt.toEpochMilliseconds()
            }
            UsersTable.insert {
                it[id] = user2.id
                it[username] = user2.username
                it[passwordHash] = "hash"
                it[email] = user2.email
                it[status] = UserStatus.ACTIVE
                it[createdAt] = user2.createdAt.toEpochMilliseconds()
                it[updatedAt] = user2.updatedAt.toEpochMilliseconds()
            }
        }

        val user1Tool = createOperatorTool(userId = user1.id)
        createOperatorTool(userId = user2.id)

        val user1Tools = toolDefinitionDao.getToolsForUser(user1.id)
        val user2Tools = toolDefinitionDao.getToolsForUser(user2.id)

        // No cross-user leak: each user sees only their own operator instance.
        assertTrue(user1Tools.any { it.id == user1Tool.id })
        assertTrue(user2Tools.none { it.id == user1Tool.id })
    }

    @Test
    fun `getToolsForUser does not leak built-in tools of workers owned by other users`() = runTest {
        val user1 = TestDefaults.user1
        val user2 = TestDefaults.user2
        transactionScope.transaction {
            UsersTable.insert {
                it[id] = user1.id
                it[username] = user1.username
                it[passwordHash] = "hash"
                it[email] = user1.email
                it[status] = UserStatus.ACTIVE
                it[createdAt] = user1.createdAt.toEpochMilliseconds()
                it[updatedAt] = user1.updatedAt.toEpochMilliseconds()
            }
            UsersTable.insert {
                it[id] = user2.id
                it[username] = user2.username
                it[passwordHash] = "hash"
                it[email] = user2.email
                it[status] = UserStatus.ACTIVE
                it[createdAt] = user2.createdAt.toEpochMilliseconds()
                it[updatedAt] = user2.updatedAt.toEpochMilliseconds()
            }
        }

        // A worker owned by user1 exposes a built-in tool; user2 must never see it.
        val worker = workerDao.createWorker(
            ownerUserId = user1.id,
            workerUid = "owner-leak-worker",
            displayName = "owner-leak-worker",
            certificatePem = "pem",
            certificateFingerprint = "fp",
            allowedScopes = emptyList()
        ).getOrNull()!!
        val builtInEntity = toolDefinitionDao.insertToolDefinition(
            name = "read_text_file",
            description = "Read a file",
            type = ToolType.BUILTIN_WORKER,
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", "object") },
            outputSchema = null,
            isEnabled = true
        )
        builtInToolDefinitionDao.insertTool(builtInEntity.id, worker.id, "read_text_file")

        val user1Tools = toolDefinitionDao.getToolsForUser(user1.id)
        val user2Tools = toolDefinitionDao.getToolsForUser(user2.id)

        assertTrue(user1Tools.any { it.id == builtInEntity.id }, "Owner should see their worker's built-in tool")
        assertTrue(user2Tools.none { it.id == builtInEntity.id }, "Other users must not see the built-in tool")
    }

    /**
     * Creates a BUILTIN_SERVER tool definition linked to the given user.
     *
     * @param name Public tool name (defaults to the first catalog spec).
     * @param userId Owning user.
     * @return The persisted [eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition].
     */
    private suspend fun createServerBuiltInTool(
        name: String = eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME,
        userId: Long
    ): eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition {
        val spec = eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.allTools.first { it.name == name }
        val entity = toolDefinitionDao.insertToolDefinition(
            name = name,
            description = spec.description,
            type = ToolType.BUILTIN_SERVER,
            config = buildJsonObject { },
            inputSchema = spec.inputSchema,
            outputSchema = null,
            isEnabled = true
        )
        serverBuiltInToolDefinitionDao.insertTool(entity.id, userId)
        return toolDefinitionDao.getToolDefinitionById(entity.id).getOrElse {
            throw AssertionError("Expected server built-in tool to be retrievable")
        } as eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
    }

    @Test
    fun `BUILTIN_SERVER tool maps to ServerBuiltInToolDefinition reading userId from the side table`() = runTest {
        // The linkage table enforces a user FK, so the owning user must exist first.
        transactionScope.transaction {
            UsersTable.insert {
                it[id] = 77L
                it[username] = "server_builtin_owner"
                it[passwordHash] = "hash"
                it[email] = "server_builtin_owner@example.com"
                it[status] = UserStatus.ACTIVE
                it[createdAt] = Clock.System.now().toEpochMilliseconds()
                it[updatedAt] = Clock.System.now().toEpochMilliseconds()
            }
        }
        val tool = createServerBuiltInTool(userId = 77L)

        val retrieved = toolDefinitionDao.getToolDefinitionById(tool.id).getOrElse {
            throw AssertionError("Expected tool to be retrievable")
        }

        assertIs<eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition>(retrieved)
        assertEquals(ToolType.BUILTIN_SERVER, retrieved.type)
        assertEquals(77L, retrieved.userId)
        assertEquals(eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME, retrieved.name)
    }

    @Test
    fun `getToolsForUser returns only the user's own server built-in tools`() = runTest {
        val user1 = TestDefaults.user1
        val user2 = TestDefaults.user2
        transactionScope.transaction {
            UsersTable.insert {
                it[id] = user1.id
                it[username] = user1.username
                it[passwordHash] = "hash"
                it[email] = user1.email
                it[status] = UserStatus.ACTIVE
                it[createdAt] = user1.createdAt.toEpochMilliseconds()
                it[updatedAt] = user1.updatedAt.toEpochMilliseconds()
            }
            UsersTable.insert {
                it[id] = user2.id
                it[username] = user2.username
                it[passwordHash] = "hash"
                it[email] = user2.email
                it[status] = UserStatus.ACTIVE
                it[createdAt] = user2.createdAt.toEpochMilliseconds()
                it[updatedAt] = user2.updatedAt.toEpochMilliseconds()
            }
        }

        val user1Tool = createServerBuiltInTool(userId = user1.id)
        createServerBuiltInTool(userId = user2.id)

        val user1Tools = toolDefinitionDao.getToolsForUser(user1.id)
        val user2Tools = toolDefinitionDao.getToolsForUser(user2.id)

        // No cross-user leak: each user sees only their own server built-in instance.
        assertTrue(user1Tools.any { it.id == user1Tool.id })
        assertTrue(user2Tools.none { it.id == user1Tool.id })
    }
}
