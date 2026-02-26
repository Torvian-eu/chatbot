package eu.torvian.chatbot.server.service.setup

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [ToolDefinitionInitializer].
 *
 * This test suite verifies:
 * - Tool definition initialization
 * - Default web search tool creation
 * - Idempotency of initialization
 * - Tool configuration and schema validation
 */
@Disabled
class ToolDefinitionInitializerTest {
    private lateinit var container: DIContainer
    private lateinit var toolDefinitionInitializer: ToolDefinitionInitializer
    private lateinit var toolService: ToolService
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        toolDefinitionInitializer = container.get()
        toolService = container.get()
        toolDefinitionDao = container.get()
        testDataManager = container.get()

        // Create necessary tables
        testDataManager.createTables(
            setOf(
                Table.TOOL_DEFINITIONS,
                Table.TOOL_CALLS,
                Table.SESSION_TOOL_CONFIG
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `isInitialized should return false when no tools exist`() = runTest {
        val isInitialized = toolDefinitionInitializer.isInitialized()
        assertFalse(isInitialized, "Should not be initialized when no tools exist")
    }

    @Test
    fun `isInitialized should return true when tools exist`() = runTest {
        // Create a tool manually
        toolService.createTool(
            name = "test_tool",
            description = "Test tool",
            type = ToolType.CUSTOM,
            config = kotlinx.serialization.json.buildJsonObject {},
            inputSchema = kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            },
            outputSchema = null,
            isEnabled = true
        )

        val isInitialized = toolDefinitionInitializer.isInitialized()
        assertTrue(isInitialized, "Should be initialized when tools exist")
    }

    @Test
    fun `initialize should create web search tool successfully`() = runTest {
        val result = toolDefinitionInitializer.initialize()

        assertTrue(result.isRight(), "Expected successful initialization")

        // Verify tools were created
        val tools = toolService.getAllTools()
        assertEquals(2, tools.size, "Expected exactly two tools (web_search and get_weather)")

        val webSearchTool = tools.firstOrNull { it.name == "web_search" }
        assertNotNull(webSearchTool, "Expected web_search tool to be created")
        assertEquals(
            "Search the web for current information using DuckDuckGo",
            webSearchTool.description,
            "Expected correct description"
        )
        assertEquals(ToolType.WEB_SEARCH, webSearchTool.type, "Expected WEB_SEARCH type")
        assertTrue(webSearchTool.isEnabled, "Web search tool should be enabled by default")

        // Verify weather tool was also created
        val weatherTool = tools.firstOrNull { it.name == "get_weather" }
        assertNotNull(weatherTool, "Expected get_weather tool to be created")
        assertEquals(ToolType.WEATHER, weatherTool.type, "Expected WEATHER type")
        assertTrue(weatherTool.isEnabled, "Weather tool should be enabled by default")
    }

    @Test
    fun `initialize should create tool with correct configuration`() = runTest {
        toolDefinitionInitializer.initialize()

        val tools = toolService.getAllTools()
        val webSearchTool = tools.firstOrNull { it.name == "web_search" }
        assertNotNull(webSearchTool, "Expected web_search tool to exist")

        // Verify config
        val config = webSearchTool.config
        assertTrue(config.containsKey("search_engine"), "Config should have search_engine")
        assertTrue(config.containsKey("max_results"), "Config should have max_results")

        val searchEngine = config["search_engine"]?.jsonPrimitive?.content
        assertEquals("duckduckgo", searchEngine, "Should use DuckDuckGo as search engine")

        val maxResults = config["max_results"]?.jsonPrimitive?.content
        assertEquals("5", maxResults, "Should have max 5 results")
    }

    @Test
    fun `initialize should create tool with valid input schema`() = runTest {
        toolDefinitionInitializer.initialize()

        val tools = toolService.getAllTools()
        val webSearchTool = tools.firstOrNull { it.name == "web_search" }
        assertNotNull(webSearchTool, "Expected web_search tool to exist")

        // Verify input schema has required JSON Schema properties
        val inputSchema = webSearchTool.inputSchema
        assertTrue(inputSchema.containsKey("type"), "Input schema should have 'type'")
        assertTrue(inputSchema.containsKey("properties"), "Input schema should have 'properties'")
        assertTrue(inputSchema.containsKey("required"), "Input schema should have 'required'")

        // Verify 'query' property exists
        val properties = inputSchema["properties"] as? kotlinx.serialization.json.JsonObject
        assertNotNull(properties, "Properties should be a JsonObject")
        assertTrue(properties.containsKey("query"), "Properties should have 'query'")

        // Verify 'query' is in required array
        val required = inputSchema["required"] as? kotlinx.serialization.json.JsonArray
        assertNotNull(required, "Required should be a JsonArray")
        assertTrue(
            required.any { it.jsonPrimitive.content == "query" },
            "Query should be in required array"
        )
    }

    @Test
    fun `initialize should create tool with valid output schema`() = runTest {
        toolDefinitionInitializer.initialize()

        val tools = toolService.getAllTools()
        val webSearchTool = tools.firstOrNull { it.name == "web_search" }
        assertNotNull(webSearchTool, "Expected web_search tool to exist")

        // Verify output schema exists
        val outputSchema = webSearchTool.outputSchema
        assertNotNull(outputSchema, "Output schema should not be null")

        assertTrue(outputSchema.containsKey("type"), "Output schema should have 'type'")
        assertTrue(outputSchema.containsKey("properties"), "Output schema should have 'properties'")

        // Verify 'results' property exists
        val properties = outputSchema["properties"] as? kotlinx.serialization.json.JsonObject
        assertNotNull(properties, "Properties should be a JsonObject")
        assertTrue(properties.containsKey("results"), "Properties should have 'results'")
    }

    @Test
    fun `initialize should be idempotent`() = runTest {
        // Initialize twice
        val firstResult = toolDefinitionInitializer.initialize()
        val secondResult = toolDefinitionInitializer.initialize()

        assertTrue(firstResult.isRight(), "Expected successful first initialization")
        assertTrue(secondResult.isRight(), "Expected successful second initialization")

        // Verify only two tools exist (web_search and get_weather)
        val tools = toolService.getAllTools()
        assertEquals(2, tools.size, "Expected exactly two tools after repeated initialization")
        assertTrue(tools.any { it.name == "web_search" }, "Expected web_search tool")
        assertTrue(tools.any { it.name == "get_weather" }, "Expected get_weather tool")
    }

    @Test
    fun `initialize should skip when already initialized`() = runTest {
        // First initialization
        toolDefinitionInitializer.initialize()
        assertTrue(toolDefinitionInitializer.isInitialized(), "Should be initialized after first run")

        // Get initial tool count and IDs
        val toolsAfterFirst = toolService.getAllTools()
        val firstToolIds = toolsAfterFirst.map { it.id }.toSet()

        // Second initialization
        toolDefinitionInitializer.initialize()

        // Should still have the same tools
        val toolsAfterSecond = toolService.getAllTools()
        assertEquals(2, toolsAfterSecond.size, "Expected exactly two tools")
        val secondToolIds = toolsAfterSecond.map { it.id }.toSet()
        assertEquals(firstToolIds, secondToolIds, "Expected same tool IDs")
    }

    @Test
    fun `initialize should not fail if tools already exist`() = runTest {
        // Create a different tool first
        toolService.createTool(
            name = "existing_tool",
            description = "Existing tool",
            type = ToolType.CUSTOM,
            config = kotlinx.serialization.json.buildJsonObject {},
            inputSchema = kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            },
            outputSchema = null,
            isEnabled = true
        )

        // Now try to initialize - should skip
        val result = toolDefinitionInitializer.initialize()
        assertTrue(result.isRight(), "Expected successful initialization even with existing tools")

        // Should not have created web_search tool since tools already existed
        val tools = toolService.getAllTools()
        assertEquals(1, tools.size, "Should still have only the existing tool")
        assertEquals("existing_tool", tools.first().name, "Should have the existing tool")
    }

    @Test
    fun `web search tool should have all required schema fields`() = runTest {
        toolDefinitionInitializer.initialize()

        val tools = toolService.getAllTools()
        val webSearchTool = tools.first()

        // Input schema: query property should have type and description
        val inputProps = webSearchTool.inputSchema["properties"] as kotlinx.serialization.json.JsonObject
        val queryProp = inputProps["query"] as kotlinx.serialization.json.JsonObject
        assertTrue(queryProp.containsKey("type"), "Query property should have type")
        assertTrue(queryProp.containsKey("description"), "Query property should have description")

        // Output schema: results should be an array with items
        val outputProps = webSearchTool.outputSchema!!["properties"] as kotlinx.serialization.json.JsonObject
        val resultsProp = outputProps["results"] as kotlinx.serialization.json.JsonObject
        assertTrue(resultsProp.containsKey("type"), "Results property should have type")
        assertTrue(resultsProp.containsKey("items"), "Results property should have items")

        // Items should define title, url, snippet
        val itemsProp = resultsProp["items"] as kotlinx.serialization.json.JsonObject
        val itemProps = itemsProp["properties"] as kotlinx.serialization.json.JsonObject
        assertTrue(itemProps.containsKey("title"), "Items should have title")
        assertTrue(itemProps.containsKey("url"), "Items should have url")
        assertTrue(itemProps.containsKey("snippet"), "Items should have snippet")
    }
}

