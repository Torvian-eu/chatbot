package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolError
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
 * Tests for [ServerBuiltInToolDefinitionServiceImpl].
 *
 * Verifies user-scoped listing, ownership enforcement on updates, the immutable-name guarantee, and
 * catalog reconciliation on reset. The service is the HTTP-facing seam behind the Server Built-In
 * Tools settings tab.
 */
class ServerBuiltInToolDefinitionServiceTest {

    private lateinit var container: DIContainer
    private lateinit var service: ServerBuiltInToolDefinitionService
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        service = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                // The seeder resolves the per-user prefix preference, so the preferences table
                // (and its device FK) must exist.
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
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
    fun `getServerBuiltInToolsForUser returns only the calling user's tools`() = runTest {
        val seeder = container.get<ServerBuiltInToolDefinitionSeeder>()
        seeder.ensureForUser(TestDefaults.user1.id)
        seeder.ensureForUser(TestDefaults.user2.id)

        val user1Tools = service.getServerBuiltInToolsForUser(TestDefaults.user1.id)
        val user2Tools = service.getServerBuiltInToolsForUser(TestDefaults.user2.id)

        // Each user owns the full catalog set (one row per spec), not shared rows.
        assertEquals(ServerBuiltInToolCatalog.allTools.size, user1Tools.size)
        assertEquals(ServerBuiltInToolCatalog.allTools.size, user2Tools.size)
        assertTrue(user1Tools.all { it.userId == TestDefaults.user1.id })
        assertTrue(user2Tools.all { it.userId == TestDefaults.user2.id })
        assertTrue(user1Tools.map { it.id }.none { it in user2Tools.map { other -> other.id } })
        // Public names carry the default prefix; the canonical name is persisted separately.
        user1Tools.forEach { tool ->
            assertEquals(
                ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX + tool.builtInToolName,
                tool.name
            )
        }
    }

    @Test
    fun `updateServerBuiltInTool toggles enabled state for the owning user`() = runTest {
        val seeder = container.get<ServerBuiltInToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val original = seeded.first()
        assertTrue(original.isEnabled)

        val result = service.updateServerBuiltInTool(
            userId = TestDefaults.user1.id,
            tool = original.copy(isEnabled = false)
        )

        assertTrue(result.isRight(), "update failed: ${result.leftOrNull()}")
        val updated = result.getOrNull()!!
        assertTrue(!updated.isEnabled)
        assertEquals(original.id, updated.id)
        assertEquals(TestDefaults.user1.id, updated.userId)
        // The catalog name is immutable and survives the update unchanged, and the canonical
        // dispatch name is preserved even if the request attempted to change it.
        assertEquals(original.name, updated.name)
        assertEquals(original.builtInToolName, updated.builtInToolName)
    }

    @Test
    fun `updateServerBuiltInTool preserves builtInToolName even when the request carries a foreign one`() = runTest {
        val seeder = container.get<ServerBuiltInToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val original = seeded.first()

        // A client attempting to smuggle a different canonical name must be ignored: the persisted
        // row's builtInToolName is the dispatch identity.
        val result = service.updateServerBuiltInTool(
            userId = TestDefaults.user1.id,
            tool = original.copy(
                builtInToolName = "list_models",
                description = "edited"
            )
        )

        assertTrue(result.isRight(), "update failed: ${result.leftOrNull()}")
        val updated = result.getOrNull()!!
        assertEquals(original.builtInToolName, updated.builtInToolName)
        assertEquals(original.name, updated.name)
        assertEquals("edited", updated.description)
    }

    @Test
    fun `updateServerBuiltInTool by non-owner returns Forbidden`() = runTest {
        val seeder = container.get<ServerBuiltInToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val original = seeded.first()

        val result = service.updateServerBuiltInTool(
            userId = TestDefaults.user2.id,
            tool = original.copy(isEnabled = false)
        )

        assertTrue(result.isLeft())
        assertIs<UpdateServerBuiltInToolError.Forbidden>(result.leftOrNull())
        // The owner's row must not be modified by the foreign update attempt.
        val after = service.getServerBuiltInToolsForUser(TestDefaults.user1.id).first { it.id == original.id }
        assertTrue(after.isEnabled)
    }

    @Test
    fun `resetServerBuiltInToolsToDefaults reconciles the user's tools`() = runTest {
        val seeder = container.get<ServerBuiltInToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val edited = seeded.first().copy(
            description = "drifted description",
            isEnabled = false
        )
        container.get<ToolService>().updateTool(edited)

        val result = service.resetServerBuiltInToolsToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val after = result.getOrNull()!!.first { it.id == edited.id }
        val catalogSpec = ServerBuiltInToolCatalog.specFor(edited.builtInToolName)!!
        assertEquals(catalogSpec.description, after.description)
        // Enabled choice is preserved across a reset.
        assertTrue(!after.isEnabled)
    }
}
