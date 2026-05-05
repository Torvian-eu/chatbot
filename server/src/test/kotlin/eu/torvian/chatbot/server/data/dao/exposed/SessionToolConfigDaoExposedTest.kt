package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.SetToolEnabledError
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SessionToolConfigDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [SessionToolConfigDao]:
 * - Setting tool enabled/disabled for sessions
 * - Getting enabled tools for a session
 * - Checking if a tool is enabled for a session
 * - Getting sessions using a tool
 * - Clearing session tool configurations
 * - Testing UPSERT behavior
 * - Respecting both global and session-level enabled flags
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class SessionToolConfigDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var sessionToolConfigDao: SessionToolConfigDao
    private lateinit var sessionDao: SessionDao
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var testDataManager: TestDataManager

    // Test fixtures
    private var testSessionId1: Long = 0
    private var testSessionId2: Long = 0
    private lateinit var testTool1: ToolDefinition
    private lateinit var testTool2: ToolDefinition
    private lateinit var testTool3Disabled: ToolDefinition

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        sessionToolConfigDao = container.get()
        sessionDao = container.get()
        toolDefinitionDao = container.get()
        testDataManager = container.get()

        // Create all necessary tables
        testDataManager.createAllTables()

        // Create test sessions
        testSessionId1 = sessionDao.insertSession(
            name = "Test Session 1",
            groupId = null,
            currentModelId = null,
            currentSettingsId = null
        ).getOrElse { throw IllegalStateException("Failed to create test session 1") }.id

        testSessionId2 = sessionDao.insertSession(
            name = "Test Session 2",
            groupId = null,
            currentModelId = null,
            currentSettingsId = null
        ).getOrElse { throw IllegalStateException("Failed to create test session 2") }.id

        // Create test tool definitions
        testTool1 = createTestTool(name = "web_search", isEnabled = true)
        testTool2 = createTestTool(name = "calculator", type = ToolType.CALCULATOR, isEnabled = true)
        testTool3Disabled = createTestTool(name = "disabled_tool", isEnabled = false)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    private suspend fun createTestTool(
        name: String,
        type: ToolType = ToolType.WEB_SEARCH,
        isEnabled: Boolean = true
    ): ToolDefinition {
        val config = buildJsonObject { put("test", "value") }
        val inputSchema = buildJsonObject { put("type", "object") }

        return toolDefinitionDao.insertToolDefinition(
            name = name,
            description = "Test tool: $name",
            type = type,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = isEnabled
        )
    }

    @Test
    fun `setToolEnabledForSession enables tool for session`() = runTest {
        // Execute: Enable tool for session
        val result = sessionToolConfigDao.setToolEnabledForSession(
            sessionId = testSessionId1,
            toolDefinitionId = testTool1.id,
            enabled = true
        )

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify tool is now enabled for the session
        val isEnabled = sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id)
        assertTrue(isEnabled, "Expected tool to be enabled for session")
    }

    @Test
    fun `setToolEnabledForSession updates existing config`() = runTest {
        // Setup: Enable tool first
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, true)
        assertTrue(sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id))

        // Execute: Update to disable (UPSERT behavior)
        val result = sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, false)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify tool is now disabled
        val isEnabled = sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id)
        assertFalse(isEnabled, "Expected tool to be disabled for session")
    }

    @Test
    fun `isToolEnabledForSession returns true when enabled`() = runTest {
        // Setup: Enable tool
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, true)

        // Execute & Verify
        val result = sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id)
        assertTrue(result, "Expected true for enabled tool")
    }

    @Test
    fun `isToolEnabledForSession returns false when not configured`() = runTest {
        // Execute & Verify: No configuration exists for this session-tool pair
        val result = sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id)
        assertFalse(result, "Expected false when no configuration exists")
    }

    @Test
    fun `isToolEnabledForSession returns false when disabled`() = runTest {
        // Setup: Explicitly disable tool
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, false)

        // Execute & Verify
        val result = sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id)
        assertFalse(result, "Expected false for disabled tool")
    }

    @Test
    fun `getEnabledToolsForSession returns only enabled tools`() = runTest {
        // Setup: Enable tool1, disable tool2, don't configure tool3
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, true)
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool2.id, false)
        // testTool3Disabled is not configured for the session

        // Execute
        val result = sessionToolConfigDao.getEnabledToolsForSession(testSessionId1)

        // Verify
        assertEquals(1, result.size, "Expected only 1 enabled tool")
        assertEquals(testTool1.id, result.first().id, "Expected testTool1")
    }

    @Test
    fun `getEnabledToolsForSession respects global enabled flag`() = runTest {
        // Setup: Enable globally-disabled tool for session
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool3Disabled.id, true)

        // Execute
        val result = sessionToolConfigDao.getEnabledToolsForSession(testSessionId1)

        // Verify: Should NOT include testTool3Disabled because it's globally disabled
        assertEquals(0, result.size, "Expected no tools (testTool3 is globally disabled)")
    }

    @Test
    fun `getEnabledToolsForSession respects session enabled flag`() = runTest {
        // Setup: Enable globally-enabled tool but disable it for this session
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, false)

        // Execute
        val result = sessionToolConfigDao.getEnabledToolsForSession(testSessionId1)

        // Verify: Should NOT include testTool1 because it's disabled for this session
        assertEquals(0, result.size, "Expected no tools (testTool1 is disabled for this session)")
    }

    @Test
    fun `getSessionsUsingTool returns correct session IDs`() = runTest {
        // Setup: Enable tool1 for both sessions, tool2 for only session1
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, true)
        sessionToolConfigDao.setToolEnabledForSession(testSessionId2, testTool1.id, true)
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool2.id, true)

        // Execute: Get sessions using tool1
        val result1 = sessionToolConfigDao.getSessionsUsingTool(testTool1.id)

        // Verify: Should return both sessions
        assertEquals(2, result1.size, "Expected 2 sessions using tool1")
        assertTrue(result1.contains(testSessionId1), "Expected testSessionId1")
        assertTrue(result1.contains(testSessionId2), "Expected testSessionId2")

        // Execute: Get sessions using tool2
        val result2 = sessionToolConfigDao.getSessionsUsingTool(testTool2.id)

        // Verify: Should return only session1
        assertEquals(1, result2.size, "Expected 1 session using tool2")
        assertTrue(result2.contains(testSessionId1), "Expected testSessionId1")
    }

    @Test
    fun `clearSessionToolConfig removes all configs for session`() = runTest {
        // Setup: Enable multiple tools for the session
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool1.id, true)
        sessionToolConfigDao.setToolEnabledForSession(testSessionId1, testTool2.id, true)

        // Verify tools are enabled
        assertTrue(sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id))
        assertTrue(sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool2.id))

        // Execute: Clear all configs for session1
        val result = sessionToolConfigDao.clearSessionToolConfig(testSessionId1)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify all tools are now disabled/not configured
        assertFalse(sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool1.id))
        assertFalse(sessionToolConfigDao.isToolEnabledForSession(testSessionId1, testTool2.id))
    }

    @Test
    fun `setToolEnabledForSession with invalid sessionId returns ForeignKeyViolation error`() = runTest {
        // Execute: Try to enable tool for non-existent session
        val result = sessionToolConfigDao.setToolEnabledForSession(
            sessionId = 999L,
            toolDefinitionId = testTool1.id,
            enabled = true
        )

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertIs<SetToolEnabledError.ForeignKeyViolation>(error, "Expected ForeignKeyViolation error but got $error")
        }
    }

    @Test
    fun `setToolEnabledForSession with invalid toolDefinitionId returns ForeignKeyViolation error`() = runTest {
        // Execute: Try to enable non-existent tool for session
        val result = sessionToolConfigDao.setToolEnabledForSession(
            sessionId = testSessionId1,
            toolDefinitionId = 999L,
            enabled = true
        )

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertIs<SetToolEnabledError.ForeignKeyViolation>(error, "Expected ForeignKeyViolation error but got $error")
        }
    }
}

