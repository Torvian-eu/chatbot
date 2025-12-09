package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [ToolDefinitionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ToolDefinitionDao]:
 * - Getting tool definitions by ID
 * - Getting tool definitions by name
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
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        toolDefinitionDao = container.get()
        testDataManager = container.get()

        // Create the tool_definitions table
        testDataManager.createAllTables()
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // Helper function to create a test tool definition
    private suspend fun createTestTool(
        name: String = "web_search",
        description: String = "Search the web for information",
        type: ToolType = ToolType.WEB_SEARCH,
        isEnabled: Boolean = true
    ): MiscToolDefinition {
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

        return toolDefinitionDao.insertToolDefinition(
            name = name,
            description = description,
            type = type,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = isEnabled
        )
    }

    @Test
    fun `getAllToolDefinitions returns all tools`() = runTest {
        // Setup: Create multiple tools
        createTestTool(name = "web_search")
        createTestTool(name = "calculator", description = "Perform calculations", type = ToolType.CALCULATOR)

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
        assertEquals(ToolType.WEB_SEARCH, tool.type)
        assertTrue(tool.isEnabled)
    }

    @Test
    fun `getToolDefinitionById returns NotFound when not exists`() = runTest {
        // Execute
        val result = toolDefinitionDao.getToolDefinitionById(999L)

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertTrue(error is ToolDefinitionError.NotFound, "Expected NotFound error")
            assertEquals(999L, (error as ToolDefinitionError.NotFound).id)
        }
    }

    @Test
    fun `getToolDefinitionByName returns tool when exists`() = runTest {
        // Setup
        val created = createTestTool(name = "web_search")

        // Execute
        val result = toolDefinitionDao.getToolDefinitionByName("web_search")

        // Verify
        val tool = result.getOrElse { throw AssertionError("Expected Right but got Left: $it") }
        assertEquals(created.id, tool.id)
        assertEquals("web_search", tool.name)
    }

    @Test
    fun `getToolDefinitionByName returns NameNotFound when not exists`() = runTest {
        // Execute
        val result = toolDefinitionDao.getToolDefinitionByName("nonexistent")

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertTrue(error is ToolDefinitionError.NameNotFound, "Expected NameNotFound error")
            assertEquals("nonexistent", (error as ToolDefinitionError.NameNotFound).name)
        }
    }

    @Test
    fun `insertToolDefinition creates new tool`() = runTest {
        // Setup
        val config = buildJsonObject { put("key", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }

        // Execute
        val tool = toolDefinitionDao.insertToolDefinition(
            name = "test_tool",
            description = "A test tool",
            type = ToolType.CUSTOM,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true
        )

        // Verify
        assertTrue(tool.id > 0, "Expected valid ID")
        assertEquals("test_tool", tool.name)
        assertEquals("A test tool", tool.description)
        assertEquals(ToolType.CUSTOM, tool.type)
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
            type = ToolType.WEB_SEARCH,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true
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
        // Setup: Create tool with outputSchema
        val config = buildJsonObject { put("key", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }
        val outputSchema = buildJsonObject { put("type", "string") }

        val created = toolDefinitionDao.insertToolDefinition(
            name = "test_tool",
            description = "Test",
            type = ToolType.CUSTOM,
            config = config,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            isEnabled = true
        )

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
        val nonExistentTool = MiscToolDefinition(
            id = 999L,
            name = "test",
            description = "Test",
            type = ToolType.CUSTOM,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        // Execute
        val result = toolDefinitionDao.updateToolDefinition(nonExistentTool)

        // Verify
        assertTrue(result.leftOrNull() is ToolDefinitionError.NotFound, "Expected NotFound error")
    }

    @Test
    fun `updateToolDefinition allows duplicate names`() = runTest {
        // Setup: Create two tools
        createTestTool(name = "web_search")
        val tool2 = createTestTool(name = "calculator", type = ToolType.CALCULATOR)

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
        assertTrue(result.leftOrNull() is ToolDefinitionError.NotFound, "Expected NotFound error")
    }

    @Test
    fun `getEnabledToolDefinitions returns only enabled tools`() = runTest {
        // Setup: Create enabled and disabled tools
        createTestTool(name = "enabled1", isEnabled = true)
        createTestTool(name = "disabled1", isEnabled = false)
        createTestTool(name = "enabled2", isEnabled = true, type = ToolType.CALCULATOR)

        // Execute
        val result = toolDefinitionDao.getEnabledToolDefinitions()

        // Verify
        assertEquals(2, result.size, "Expected only 2 enabled tools")
        assertTrue(result.all { it.isEnabled }, "All returned tools should be enabled")
        assertTrue(result.any { it.name == "enabled1" }, "Expected enabled1 tool")
        assertTrue(result.any { it.name == "enabled2" }, "Expected enabled2 tool")
        assertFalse(result.any { it.name == "disabled1" }, "Should not include disabled1 tool")
    }
}

