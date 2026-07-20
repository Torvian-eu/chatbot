package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolType
import kotlinx.serialization.json.buildJsonObject
import eu.torvian.chatbot.server.data.dao.BuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [BuiltInToolDefinitionSeeder].
 *
 * Verifies that the default built-in tools are seeded for a worker, that seeding is
 * idempotent, that the worker prefix is reflected in public names, and that prefix updates rename
 * only the public name while preserving the unprefixed `builtInToolName`.
 */
class BuiltInToolDefinitionSeederTest {

    private lateinit var container: DIContainer
    private lateinit var seeder: BuiltInToolDefinitionSeeder
    private lateinit var builtInToolDefinitionDao: BuiltInToolDefinitionDao
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var workerDao: WorkerDao
    private lateinit var testDataManager: TestDataManager

    private val testUser1 = TestDefaults.user1

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        seeder = container.get()
        builtInToolDefinitionDao = container.get()
        toolDefinitionDao = container.get()
        workerDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.WORKERS,
                Table.TOOL_DEFINITIONS,
                Table.BUILT_IN_TOOL_DEFINITIONS
            )
        )

        testDataManager.setup(
            eu.torvian.chatbot.server.testutils.data.TestDataSet(
                users = listOf(testUser1, TestDefaults.user2)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    private suspend fun createWorker(workerUid: String): Long {
        return workerDao.createWorker(
            ownerUserId = testUser1.id,
            workerUid = workerUid,
            displayName = workerUid,
            certificatePem = "pem-$workerUid",
            certificateFingerprint = "fp-$workerUid",
            allowedScopes = emptyList()
        ).getOrNull()!!.id
    }

    @Test
    fun `seedDefaultToolsForWorker creates the default built-in tools`() = runTest {
        val workerId = createWorker("worker-seed")

        val result = seeder.seedDefaultToolsForWorker(workerId, null)

        assertTrue(result.isRight(), "seeding failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(BuiltInToolCatalog.size, tools.size)
        assertEquals(ToolType.BUILTIN_WORKER, tools.first().type)
        // All tools are enabled by default for newly registered workers.
        assertTrue(tools.all { it.isEnabled })
        // Public name equals the unprefixed built-in name when no prefix is provided.
        assertTrue(tools.all { it.name == it.builtInToolName })
        assertEquals(
            BuiltInToolCatalog.allTools.map { it.builtInToolName }.toSet(),
            tools.map { it.builtInToolName }.toSet()
        )
    }

    @Test
    fun `seedDefaultToolsForWorker is idempotent`() = runTest {
        val workerId = createWorker("worker-seed-idempotent")

        val first = seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!
        val second = seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!

        assertEquals(BuiltInToolCatalog.size, first.size)
        assertEquals(BuiltInToolCatalog.size, second.size)
        // Re-running must not create duplicate definitions.
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
    }

    @Test
    fun `seedDefaultToolsForWorker reflects prefix in public names`() = runTest {
        val workerId = createWorker("worker-seed-prefix")

        val tools = seeder.seedDefaultToolsForWorker(workerId, "project1_").getOrNull()!!

        assertEquals(BuiltInToolCatalog.size, tools.size)
        assertTrue(tools.all { it.name == "project1_${it.builtInToolName}" })
        // The unprefixed internal name matches the catalog's canonical name.
        val catalogNames = BuiltInToolCatalog.allTools.map { it.builtInToolName }.toSet()
        assertTrue(tools.all { it.builtInToolName in catalogNames })
        assertEquals("project1_read_text_file", tools.first { it.builtInToolName == "read_text_file" }.name)
    }

    @Test
    fun `renamePublicNamesForPrefix renames only public names, preserving builtInToolName`() = runTest {
        val workerId = createWorker("worker-rename")

        seeder.seedDefaultToolsForWorker(workerId, "project1_").getOrNull()!!
        val before = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        val beforeNames = before.associate { it.builtInToolName to it.name }

        // Change the prefix to project2_.
        val renameResult = seeder.renamePublicNamesForPrefix(workerId, "project2_")
        assertTrue(renameResult.isRight(), "rename failed: ${renameResult.leftOrNull()}")

        val after = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        assertEquals(BuiltInToolCatalog.size, after.size)
        for (tool in after) {
            // builtInToolName must be unchanged.
            assertEquals(beforeNames[tool.builtInToolName], beforeNames[tool.builtInToolName])
            // Public name reflects the new prefix.
            assertEquals("project2_${tool.builtInToolName}", tool.name)
        }
    }

    @Test
    fun `renamePublicNamesForPrefix with null prefix reverts to unprefixed names`() = runTest {
        val workerId = createWorker("worker-rename-null")

        seeder.seedDefaultToolsForWorker(workerId, "project1_").getOrNull()!!
        seeder.renamePublicNamesForPrefix(workerId, null).getOrNull()!!

        val after = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        assertTrue(after.all { it.name == it.builtInToolName })
    }

    @Test
    fun `resetToDefaults adds missing catalog tools to an old worker`() = runTest {
        val workerId = createWorker("worker-reset-missing")

        // Seed only a subset of the catalog (simulating an old worker registered before new tools).
        val oldSpecs = BuiltInToolCatalog.allTools.take(2)
        // Manually insert just two tools to mimic an old worker state (disabled).
        for (spec in oldSpecs) {
            val created = toolDefinitionDao.insertToolDefinition(
                name = spec.builtInToolName,
                description = spec.description,
                type = ToolType.BUILTIN_WORKER,
                config = buildJsonObject { },
                inputSchema = spec.inputSchema,
                outputSchema = null,
                isEnabled = false
            )
            builtInToolDefinitionDao.insertTool(created.id, workerId, spec.builtInToolName)
        }

        val result = seeder.resetToDefaults(workerId, null)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        // All catalog tools are now present.
        assertEquals(BuiltInToolCatalog.size, tools.size)
        // The two pre-existing tools keep their disabled state (preserved by reset).
        val existing = tools.filter { it.builtInToolName in oldSpecs.map { s -> s.builtInToolName } }
        assertEquals(2, existing.size)
        assertTrue(existing.all { !it.isEnabled })
        // The newly added tools are enabled (reset creates missing tools as enabled).
        val added = tools.filter { it.builtInToolName !in oldSpecs.map { s -> s.builtInToolName } }
        assertEquals(BuiltInToolCatalog.size - 2, added.size)
        assertTrue(added.all { it.isEnabled })
    }

    @Test
    fun `resetToDefaults repairs drifted metadata but preserves enabled state`() = runTest {
        val workerId = createWorker("worker-reset-repair")

        seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!
        val before = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        val target = before.first()

        // Drift the description and disable the tool.
        toolDefinitionDao.updateToolDefinition(
            target.copy(description = "DRIFTED", isEnabled = false)
        )

        val result = seeder.resetToDefaults(workerId, null)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val after = result.getOrNull()!!.associateBy { it.builtInToolName }
        // Description is repaired from the catalog.
        assertEquals(
            BuiltInToolCatalog.specFor(target.builtInToolName)!!.description,
            after[target.builtInToolName]!!.description
        )
        // Enabled state is preserved (still disabled).
        assertEquals(false, after[target.builtInToolName]!!.isEnabled)
        // Count is unchanged.
        assertEquals(BuiltInToolCatalog.size, after.size)
    }

    @Test
    fun `resetToDefaults is idempotent and does not change in-sync tools`() = runTest {
        val workerId = createWorker("worker-reset-idempotent")

        seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!
        val first = seeder.resetToDefaults(workerId, null).getOrNull()!!
        val second = seeder.resetToDefaults(workerId, null).getOrNull()!!

        assertEquals(BuiltInToolCatalog.size, first.size)
        assertEquals(BuiltInToolCatalog.size, second.size)
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
    }

    @Test
    fun `resetToDefaults re-applies prefix to public names`() = runTest {
        val workerId = createWorker("worker-reset-prefix")

        // Seed without prefix first.
        seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!

        // Reset with a prefix; public names should reflect it.
        val result = seeder.resetToDefaults(workerId, "project1_")

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(BuiltInToolCatalog.size, tools.size)
        assertTrue(tools.all { it.name == "project1_${it.builtInToolName}" })
    }
}
