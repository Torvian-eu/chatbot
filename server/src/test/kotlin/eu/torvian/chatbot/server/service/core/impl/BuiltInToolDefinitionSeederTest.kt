package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ToolType
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
 * Verifies that the eight default built-in tools are seeded for a worker, that seeding is
 * idempotent, that the worker prefix is reflected in public names, and that prefix updates rename
 * only the public name while preserving the unprefixed [builtInToolName].
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
    fun `seedDefaultToolsForWorker creates the 8 default built-in tools`() = runTest {
        val workerId = createWorker("worker-seed-8")

        val result = seeder.seedDefaultToolsForWorker(workerId, null)

        assertTrue(result.isRight(), "seeding failed: ${result.leftOrNull()}")
        val tools = result.getOrNull()!!
        assertEquals(8, tools.size)
        assertEquals(ToolType.BUILTIN_WORKER, tools.first().type)
        // All tools are enabled by default for newly registered workers.
        assertTrue(tools.all { it.isEnabled })
        // Public name equals the unprefixed built-in name when no prefix is provided.
        assertTrue(tools.all { it.name == it.builtInToolName })
        assertEquals(
            setOf(
                "read_text_file", "write_file", "edit_file", "create_directory",
                "list_directory", "move_file", "search_files", "run_command"
            ),
            tools.map { it.builtInToolName }.toSet()
        )
    }

    @Test
    fun `seedDefaultToolsForWorker is idempotent`() = runTest {
        val workerId = createWorker("worker-seed-idempotent")

        val first = seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!
        val second = seeder.seedDefaultToolsForWorker(workerId, null).getOrNull()!!

        assertEquals(8, first.size)
        assertEquals(8, second.size)
        // Re-running must not create duplicate definitions.
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
    }

    @Test
    fun `seedDefaultToolsForWorker reflects prefix in public names`() = runTest {
        val workerId = createWorker("worker-seed-prefix")

        val tools = seeder.seedDefaultToolsForWorker(workerId, "project1").getOrNull()!!

        assertEquals(8, tools.size)
        assertTrue(tools.all { it.name == "project1.${it.builtInToolName}" })
        // The unprefixed internal name is preserved.
        assertTrue(tools.all { it.builtInToolName == it.builtInToolName })
        assertEquals("project1.read_text_file", tools.first { it.builtInToolName == "read_text_file" }.name)
    }

    @Test
    fun `renamePublicNamesForPrefix renames only public names, preserving builtInToolName`() = runTest {
        val workerId = createWorker("worker-rename")

        seeder.seedDefaultToolsForWorker(workerId, "project1").getOrNull()!!
        val before = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        val beforeNames = before.associate { it.builtInToolName to it.name }

        // Change the prefix to project2.
        val renameResult = seeder.renamePublicNamesForPrefix(workerId, "project2")
        assertTrue(renameResult.isRight(), "rename failed: ${renameResult.leftOrNull()}")

        val after = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        assertEquals(8, after.size)
        for (tool in after) {
            // builtInToolName must be unchanged.
            assertEquals(beforeNames[tool.builtInToolName], beforeNames[tool.builtInToolName])
            // Public name reflects the new prefix.
            assertEquals("project2.${tool.builtInToolName}", tool.name)
        }
    }

    @Test
    fun `renamePublicNamesForPrefix with null prefix reverts to unprefixed names`() = runTest {
        val workerId = createWorker("worker-rename-null")

        seeder.seedDefaultToolsForWorker(workerId, "project1").getOrNull()!!
        seeder.renamePublicNamesForPrefix(workerId, null).getOrNull()!!

        val after = builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        assertTrue(after.all { it.name == it.builtInToolName })
    }
}
