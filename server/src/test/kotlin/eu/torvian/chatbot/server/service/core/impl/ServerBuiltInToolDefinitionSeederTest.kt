package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [ServerBuiltInToolDefinitionSeeder].
 *
 * Verifies that one instance per [ServerBuiltInToolCatalog] spec is seeded per user, that seeding is
 * idempotent (re-runs neither duplicate rows nor clobber user edits), and that the startup
 * [isInitialized] reconciliation covers every existing user.
 */
class ServerBuiltInToolDefinitionSeederTest {

    private lateinit var container: DIContainer
    private lateinit var seeder: ServerBuiltInToolDefinitionSeeder
    private lateinit var serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        seeder = container.get()
        serverBuiltInToolDefinitionDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.TOOL_DEFINITIONS,
                Table.SERVER_BUILTIN_TOOL_DEFINITIONS
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
    fun `ensureForUser seeds one server built-in tool per catalog spec`() = runTest {
        val result = seeder.ensureForUser(TestDefaults.user1.id)

        assertTrue(result.isRight(), "seeding failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        assertEquals(ServerBuiltInToolCatalog.allTools.map { it.name }.toSet(), tools.map { it.name }.toSet())
        tools.forEach { tool ->
            assertIs<ServerBuiltInToolDefinition>(tool)
            assertEquals(ToolType.BUILTIN_SERVER, tool.type)
            assertEquals(TestDefaults.user1.id, tool.userId)
            assertTrue(tool.isEnabled)
            // The persisted schema must be the real catalog schema so the LLM can call the tool.
            val spec = ServerBuiltInToolCatalog.allTools.first { it.name == tool.name }
            assertEquals(spec.inputSchema, tool.inputSchema)
            assertEquals(spec.description, tool.description)
        }
    }

    @Test
    fun `ensureForUser is idempotent and preserves user edits`() = runTest {
        val first = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!

        // Simulate a user edit (e.g. disabled one tool); re-seeding must not clobber it.
        val edited = first.first().copy(isEnabled = false, description = "custom description")
        container.get<eu.torvian.chatbot.server.service.core.ToolService>().updateTool(edited)

        val second = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!

        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        val after = second.first { it.id == edited.id }
        assertEquals(false, after.isEnabled)
        assertEquals("custom description", after.description)
    }

    @Test
    fun `seeding is per user - each user gets their own instances`() = runTest {
        val user1Tools = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val user2Tools = seeder.ensureForUser(TestDefaults.user2.id).getOrNull()!!

        assertEquals(TestDefaults.user1.id, user1Tools.first().userId)
        assertEquals(TestDefaults.user2.id, user2Tools.first().userId)
        assertTrue(user1Tools.map { it.id }.none { it in user2Tools.map { tool -> tool.id } })
    }

    @Test
    fun `isInitialized reports true only when every user has a full server built-in set`() = runTest {
        // No instances seeded yet -> not initialized.
        assertTrue(!seeder.isInitialized())

        seeder.ensureForUser(TestDefaults.user1.id)
        // User 2 still lacks instances -> not initialized.
        assertTrue(!seeder.isInitialized())

        seeder.ensureForUser(TestDefaults.user2.id)
        assertTrue(seeder.isInitialized())
    }

    @Test
    fun `initialize reconciles all users`() = runTest {
        val result = seeder.initialize()

        assertTrue(result.isRight(), "initialization failed: ${result.leftOrNull()}")
        assertTrue(seeder.isInitialized())
        val allUsers = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id) +
                serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user2.id)
        assertEquals(2 * ServerBuiltInToolCatalog.allTools.size, allUsers.size)
    }

    @Test
    fun `resetToDefaults creates missing tools`() = runTest {
        // Nothing seeded yet: reset acts as a full seed.
        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        val tool = tools.first { it.name == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }
        assertEquals(ServerBuiltInToolCatalog.allTools.first().description, tool.description)
        assertEquals(ServerBuiltInToolCatalog.allTools.first().inputSchema, tool.inputSchema)
        assertTrue(tool.isEnabled)
    }

    @Test
    fun `resetToDefaults repairs catalog fields but preserves enabled state`() = runTest {
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val seededTool = seeded.first { it.name == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }

        // Simulate a user edit that drifts from the catalog: custom description and disabled.
        val edited = seededTool.copy(
            description = "custom description",
            isEnabled = false
        )
        container.get<eu.torvian.chatbot.server.service.core.ToolService>().updateTool(edited)

        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        val after = tools.first { it.id == seededTool.id }
        // Catalog-derived fields are repaired...
        assertEquals(ServerBuiltInToolCatalog.allTools.first().description, after.description)
        assertEquals(ServerBuiltInToolCatalog.allTools.first().inputSchema, after.inputSchema)
        // ...but the user's enabled/disabled choice survives and no duplicate row is created.
        assertTrue(!after.isEnabled)
        assertEquals(seededTool.id, after.id)
    }

    @Test
    fun `resetToDefaults is idempotent`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)
        val first = seeder.resetToDefaults(TestDefaults.user1.id).getOrNull()!!
        val second = seeder.resetToDefaults(TestDefaults.user1.id).getOrNull()!!

        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        assertEquals(ServerBuiltInToolCatalog.allTools.size, second.size)
    }

    @Test
    fun `resetToDefaults prunes stale rows whose catalog spec no longer exists`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)

        // Simulate a catalog entry removed in a later version: an extra per-user instance whose
        // name is not part of the current catalog. It must be pruned by the reset.
        val toolService = container.get<eu.torvian.chatbot.server.service.core.ToolService>()
        val stale = toolService.createTool(
            name = "obsolete_tool",
            description = "Removed from the catalog",
            type = ToolType.BUILTIN_SERVER,
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
            outputSchema = null,
            isEnabled = true
        ).getOrNull()!!
        serverBuiltInToolDefinitionDao.insertTool(
            toolDefinitionId = stale.id,
            userId = TestDefaults.user1.id
        )

        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        // The stale instance is gone; every catalog-backed row survives.
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        assertTrue(tools.none { it.name == "obsolete_tool" })
    }
}
