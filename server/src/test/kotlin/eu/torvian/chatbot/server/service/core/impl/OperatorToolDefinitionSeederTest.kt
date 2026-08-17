package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.OperatorToolDefinitionDao
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [OperatorToolDefinitionSeeder].
 *
 * Verifies that one `spawn_agent` instance is seeded per user, that seeding is idempotent (re-runs
 * neither duplicate rows nor clobber user edits), and that the startup [isInitialized] reconciliation
 * covers every existing user.
 */
class OperatorToolDefinitionSeederTest {

    private lateinit var container: DIContainer
    private lateinit var seeder: OperatorToolDefinitionSeeder
    private lateinit var operatorToolDefinitionDao: OperatorToolDefinitionDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        seeder = container.get()
        operatorToolDefinitionDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.TOOL_DEFINITIONS,
                Table.OPERATOR_TOOL_DEFINITIONS
            )
        )

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

    @Test
    fun `ensureForUser seeds one operator tool per catalog spec`() = runTest {
        val result = seeder.ensureForUser(TestDefaults.user1.id)

        assertTrue(result.isRight(), "seeding failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(OperatorToolCatalog.allTools.size, tools.size)
        val tool = tools.single()
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, tool.name)
        assertIs<OperatorToolDefinition>(tool)
        assertEquals(ToolType.OPERATOR, tool.type)
        assertEquals(TestDefaults.user1.id, tool.userId)
        assertTrue(tool.isEnabled)
        // The persisted schema must be the real catalog schema so the LLM can call the tool.
        assertEquals(OperatorToolCatalog.allTools.single().inputSchema, tool.inputSchema)
    }

    @Test
    fun `ensureForUser is idempotent and preserves user edits`() = runTest {
        val first = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!

        // Simulate a user edit (e.g. disabled the tool); re-seeding must not clobber it.
        val edited = first.single().copy(isEnabled = false, description = "custom description")
        container.get<eu.torvian.chatbot.server.service.core.ToolService>().updateTool(edited)

        val second = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!

        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        val after = second.single()
        assertEquals(false, after.isEnabled)
        assertEquals("custom description", after.description)
    }

    @Test
    fun `seeding is per user - each user gets their own instance`() = runTest {
        val user1Tools = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val user2Tools = seeder.ensureForUser(TestDefaults.user2.id).getOrNull()!!

        assertEquals(TestDefaults.user1.id, user1Tools.single().userId)
        assertEquals(TestDefaults.user2.id, user2Tools.single().userId)
        assertTrue(user1Tools.single().id != user2Tools.single().id)
    }

    @Test
    fun `isInitialized reports true only when every user has a full operator-tool set`() = runTest {
        // No instances seeded yet -> not initialized.
        assertTrue(!seeder.isInitialized())

        seeder.ensureForUser(TestDefaults.user1.id)
        // User 2 still lacks an instance -> not initialized.
        assertTrue(!seeder.isInitialized())

        seeder.ensureForUser(TestDefaults.user2.id)
        assertTrue(seeder.isInitialized())
    }

    @Test
    fun `initialize reconciles all users`() = runTest {
        val result = seeder.initialize()

        assertTrue(result.isRight(), "initialization failed: ${result.leftOrNull()}")
        assertTrue(seeder.isInitialized())
        val allUsers = operatorToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id) +
                operatorToolDefinitionDao.getToolsByUserId(TestDefaults.user2.id)
        assertEquals(2 * OperatorToolCatalog.allTools.size, allUsers.size)
    }

    @Test
    fun `resetToDefaults creates missing tools`() = runTest {
        // Nothing seeded yet: reset acts as a full seed.
        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(OperatorToolCatalog.allTools.size, tools.size)
        val tool = tools.single()
        assertEquals(OperatorToolCatalog.SPAWN_AGENT_NAME, tool.name)
        assertEquals(OperatorToolCatalog.allTools.single().description, tool.description)
        assertEquals(OperatorToolCatalog.allTools.single().inputSchema, tool.inputSchema)
        assertTrue(tool.isEnabled)
    }

    @Test
    fun `resetToDefaults repairs catalog fields but preserves enabled state`() = runTest {
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val seededId = seeded.single().id

        // Simulate a user edit that drifts from the catalog: custom description and disabled.
        val edited = seeded.single().copy(
            description = "custom description",
            isEnabled = false
        )
        container.get<eu.torvian.chatbot.server.service.core.ToolService>().updateTool(edited)

        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(1, tools.size)
        val after = tools.single()
        // Catalog-derived fields are repaired...
        assertEquals(OperatorToolCatalog.allTools.single().description, after.description)
        assertEquals(OperatorToolCatalog.allTools.single().inputSchema, after.inputSchema)
        // ...but the user's enabled/disabled choice survives and no duplicate row is created.
        assertEquals(seededId, after.id)
        assertTrue(!after.isEnabled)
    }

    @Test
    fun `resetToDefaults is idempotent`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)
        val first = seeder.resetToDefaults(TestDefaults.user1.id).getOrNull()!!
        val second = seeder.resetToDefaults(TestDefaults.user1.id).getOrNull()!!

        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        assertEquals(1, second.size)
    }
}
