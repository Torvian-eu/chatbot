package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixResolver
import eu.torvian.chatbot.server.service.core.ToolService
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
 * Verifies that one instance per [ServerBuiltInToolCatalog] spec is seeded per user, that public
 * names carry the user's effective prefix (default `"chatbot-"`, custom per user, blank = none)
 * while the canonical `builtInToolName` stays unprefixed, that seeding is idempotent (re-runs
 * neither duplicate rows nor clobber user edits), and that the prefix-aware [isInitialized]
 * reconciliation covers every existing user including prefix drift.
 */
class ServerBuiltInToolDefinitionSeederTest {

    private lateinit var container: DIContainer
    private lateinit var seeder: ServerBuiltInToolDefinitionSeeder
    private lateinit var serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao
    private lateinit var userPreferenceDao: UserPreferenceDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        seeder = container.get()
        serverBuiltInToolDefinitionDao = container.get()
        userPreferenceDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                // USER_DEVICES is required for the user_preferences FK; the prefix resolver reads
                // the global preference rows, so the preferences table must exist too.
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
                Table.TOOL_DEFINITIONS,
                // Needed by the initialize regression test to assert that approval preferences
                // survive the startup reconcile.
                Table.USER_TOOL_APPROVAL_PREFERENCES,
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
    fun `ensureForUser seeds one server built-in tool per catalog spec with the default prefix`() = runTest {
        val result = seeder.ensureForUser(TestDefaults.user1.id)

        assertTrue(result.isRight(), "seeding failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        // No preference set: public names use the hardcoded default prefix, canonical names persist.
        assertEquals(
            ServerBuiltInToolCatalog.allTools.map { ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX + it.name }
                .toSet(),
            tools.map { it.name }.toSet()
        )
        tools.forEach { tool ->
            assertIs<ServerBuiltInToolDefinition>(tool)
            assertEquals(ToolType.BUILTIN_SERVER, tool.type)
            assertEquals(TestDefaults.user1.id, tool.userId)
            assertTrue(tool.isEnabled)
            // The canonical, unprefixed name is persisted next to the prefixed public name.
            assertEquals(
                ServerBuiltInToolNamePrefixResolver.DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX + tool.builtInToolName,
                tool.name
            )
            val spec = ServerBuiltInToolCatalog.allTools.first { it.name == tool.builtInToolName }
            // The persisted schema must be the real catalog schema so the LLM can call the tool.
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
    fun `per user prefix - custom prefix user differs from default user but canonical names match`() = runTest {
        // User 1 gets a custom global prefix; user 2 has none (default prefix applies).
        userPreferenceDao.upsertPreference(
            userId = TestDefaults.user1.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
            value = "acme-"
        )

        val user1Tools = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val user2Tools = seeder.ensureForUser(TestDefaults.user2.id).getOrNull()!!

        for (tool in user1Tools) {
            assertEquals("acme-${tool.builtInToolName}", tool.name)
        }
        for (tool in user2Tools) {
            assertEquals("chatbot-${tool.builtInToolName}", tool.name)
        }
        // The canonical names are identical across users regardless of the prefix.
        assertEquals(
            user1Tools.map { it.builtInToolName }.toSet(),
            user2Tools.map { it.builtInToolName }.toSet()
        )
    }

    @Test
    fun `blank prefix preference produces canonical public names`() = runTest {
        userPreferenceDao.upsertPreference(
            userId = TestDefaults.user1.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
            value = ""
        )

        val tools = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!

        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        tools.forEach { tool ->
            assertEquals(tool.builtInToolName, tool.name)
        }
    }

    @Test
    fun `isInitialized reports true only when every user has a full correctly-prefixed set`() = runTest {
        // No instances seeded yet -> not initialized.
        assertTrue(!seeder.isInitialized())

        seeder.ensureForUser(TestDefaults.user1.id)
        // User 2 still lacks instances -> not initialized.
        assertTrue(!seeder.isInitialized())

        seeder.ensureForUser(TestDefaults.user2.id)
        assertTrue(seeder.isInitialized())
    }

    @Test
    fun `isInitialized detects public-name drift and initialize repairs it`() = runTest {
        // Both users must be seeded for the global isInitialized check to pass.
        seeder.ensureForUser(TestDefaults.user1.id)
        seeder.ensureForUser(TestDefaults.user2.id)
        assertTrue(seeder.isInitialized())

        // Simulate a prefix drift (e.g. a manual DB edit or a future config-default change): rename
        // one row's public name so it no longer matches prefix + canonical.
        val drifted = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id).first()
        serverBuiltInToolDefinitionDao.updatePublicName(drifted.id, "wrong-${drifted.builtInToolName}")

        assertTrue(!seeder.isInitialized())

        // The startup reconcile renames the drifted row back to the effective prefix.
        val result = seeder.initialize()
        assertTrue(result.isRight(), "initialization failed: ${result.leftOrNull()}")
        assertTrue(seeder.isInitialized())
        val repaired = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
            .first { it.id == drifted.id }
        assertEquals("chatbot-${drifted.builtInToolName}", repaired.name)
        // Id and canonical name survive the repair.
        assertEquals(drifted.id, repaired.id)
        assertEquals(drifted.builtInToolName, repaired.builtInToolName)
    }

    @Test
    fun `initialize after a prefix change renames drift but preserves user-edited descriptions enabled flags and approval preferences`() = runTest {
        val toolService = container.get<ToolService>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        // User 2 must exist so the global isInitialized check passes after the reconcile.
        seeder.ensureForUser(TestDefaults.user2.id)

        // Simulate a user edit on one tool: custom description, disabled, and a custom input schema
        // that deliberately differs from the catalog schema. The schema edit is what makes the
        // schema-preservation assertion discriminating: under the old buggy startup (which ran the
        // full resetToDefaults repair) the catalog schema would have been written back.
        val customInputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("custom_field", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
            })
        }
        val edited = seeded.first { it.builtInToolName == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }
            .copy(description = "custom description", isEnabled = false, inputSchema = customInputSchema)
        toolService.updateTool(edited)

        // Record an approval preference for the edited tool.
        val approval = toolService.setToolApprovalPreference(
            userId = TestDefaults.user1.id,
            toolDefinitionId = edited.id,
            autoApprove = true
        ).getOrNull()!!
        assertEquals(edited.id, approval.toolDefinitionId)

        // Prefix change: store the new prefix while the persisted rows still carry the old
        // "chatbot-*" public names, so every row drifts from the new effective prefix.
        userPreferenceDao.upsertPreference(
            userId = TestDefaults.user1.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
            value = "acme-"
        )
        assertTrue(!seeder.isInitialized())

        val result = seeder.initialize()

        assertTrue(result.isRight(), "initialization failed: ${result.leftOrNull()}")
        assertTrue(seeder.isInitialized())
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        val repaired = after.first { it.id == edited.id }
        // The public name is renamed to the new effective prefix...
        assertEquals("acme-${edited.builtInToolName}", repaired.name)
        // ...but the user-edited description, input schema, and enabled choice survive the startup
        // reconcile (the startup path is name-only and must never run the full resetToDefaults
        // repair). The schema assertion is discriminating because the persisted schema now differs
        // from the catalog schema, so it would fail if a future regression clobbered schemas here.
        assertEquals("custom description", repaired.description)
        assertEquals(customInputSchema, repaired.inputSchema)
        assertTrue(!repaired.isEnabled)
        // The approval preference survives too: it keys on tool_definition_id, which the rename
        // never changes.
        val storedApproval = toolService.getToolApprovalPreference(TestDefaults.user1.id, edited.id).getOrNull()
        assertTrue(storedApproval != null && storedApproval.autoApprove)
    }

    @Test
    fun `initialize prunes stale server built-in linkages while surviving rows are renamed per the effective prefix`() = runTest {
        val toolService = container.get<ToolService>()
        // Seed user 1 so there is a full catalog-backed set whose rows must survive the reconcile.
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!

        // Simulate a user edit on a surviving row so the test can prove the prune leaves it untouched.
        val edited = seeded.first { it.builtInToolName == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }
            .copy(description = "custom description", isEnabled = false)
        toolService.updateTool(edited)

        // Insert a stale linkage: a canonical name that no longer exists in the current catalog
        // (e.g. a spec removed in a later version). Its public name matches the default prefix so
        // the row alone would not trip isInitialized(); the prefix change below is what makes the
        // startup reconcile actually run, exactly like the real first-startup-after-prefix-change.
        val stale = toolService.createTool(
            name = "chatbot-obsolete_tool",
            description = "Removed from the catalog",
            type = ToolType.BUILTIN_SERVER,
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
            outputSchema = null,
            isEnabled = true
        ).getOrNull()!!
        serverBuiltInToolDefinitionDao.insertTool(
            toolDefinitionId = stale.id,
            userId = TestDefaults.user1.id,
            builtInToolName = "obsolete_tool"
        )

        // Change the prefix while the persisted rows still carry the old "chatbot-*" names: the
        // drift makes isInitialized() false, so initialize() runs the name-only reconcile for user 1.
        userPreferenceDao.upsertPreference(
            userId = TestDefaults.user1.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
            value = "acme-"
        )
        assertTrue(!seeder.isInitialized())

        val result = seeder.initialize()

        assertTrue(result.isRight(), "initialization failed: ${result.leftOrNull()}")
        assertTrue(seeder.isInitialized())
        val after = serverBuiltInToolDefinitionDao.getToolsByUserId(TestDefaults.user1.id)
        // The stale linkage is gone from the user's set...
        assertTrue(after.none { it.builtInToolName == "obsolete_tool" })
        // ...and its base definition row is gone too: the prune deletes the base tool_definition
        // via the service, and the linkage (and any approval preferences) cascade via FK ON DELETE.
        val staleBase = serverBuiltInToolDefinitionDao.getToolById(stale.id)
        assertIs<ServerBuiltInToolDefinitionError.NotFound>(staleBase.leftOrNull())
        // Every catalog spec still has exactly one row for the user, and the surviving rows keep
        // their id, description, and enabled state while their public names are renamed to the new
        // effective prefix by the same name-only reconcile that pruned the stale row.
        assertEquals(ServerBuiltInToolCatalog.allTools.size, after.size)
        val survived = after.first { it.id == edited.id }
        assertEquals(edited.id, survived.id)
        assertEquals("acme-${edited.builtInToolName}", survived.name)
        assertEquals("custom description", survived.description)
        assertTrue(!survived.isEnabled)
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
    fun `resetToDefaults creates missing tools with the effective prefix`() = runTest {
        // Nothing seeded yet: reset acts as a full seed.
        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        val tool = tools.first { it.builtInToolName == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }
        assertEquals("chatbot-" + ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME, tool.name)
        assertEquals(ServerBuiltInToolCatalog.allTools.first().description, tool.description)
        assertEquals(ServerBuiltInToolCatalog.allTools.first().inputSchema, tool.inputSchema)
        assertTrue(tool.isEnabled)
    }

    @Test
    fun `resetToDefaults repairs catalog fields but preserves enabled state`() = runTest {
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val seededTool = seeded.first { it.builtInToolName == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }

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
    fun `resetToDefaults renames rows to the effective prefix keeping id and enabled`() = runTest {
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val seededTool = seeded.first { it.builtInToolName == ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME }
        val disabled = seededTool.copy(isEnabled = false)
        container.get<eu.torvian.chatbot.server.service.core.ToolService>().updateTool(disabled)

        // Change the prefix, then reset: rows must be renamed to the new prefix.
        userPreferenceDao.upsertPreference(
            userId = TestDefaults.user1.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX,
            value = "acme-"
        )

        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        tools.forEach { tool ->
            assertEquals("acme-${tool.builtInToolName}", tool.name)
        }
        // The row identity, canonical name, and enabled choice survive the rename.
        val after = tools.first { it.id == seededTool.id }
        assertEquals(seededTool.id, after.id)
        assertEquals(seededTool.builtInToolName, after.builtInToolName)
        assertTrue(!after.isEnabled)
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
    fun `resetToDefaults prunes stale rows whose canonical spec no longer exists`() = runTest {
        seeder.ensureForUser(TestDefaults.user1.id)

        // Simulate a catalog entry removed in a later version: an extra per-user instance whose
        // canonical name is not part of the current catalog. It must be pruned by the reset.
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
            userId = TestDefaults.user1.id,
            builtInToolName = "obsolete_tool"
        )

        val result = seeder.resetToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        // The stale instance is gone; every catalog-backed row survives.
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        assertTrue(tools.none { it.builtInToolName == "obsolete_tool" })
        assertTrue(tools.none { it.name == "obsolete_tool" })
    }
}
