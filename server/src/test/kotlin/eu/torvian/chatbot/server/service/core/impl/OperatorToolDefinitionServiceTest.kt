package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.server.service.core.OperatorToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.operator.UpdateOperatorToolError
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
 * Tests for [OperatorToolDefinitionServiceImpl].
 *
 * Verifies user-scoped listing, ownership enforcement on updates, and catalog reconciliation on
 * reset. The service is the HTTP-facing seam behind the Operator Tools settings tab.
 */
class OperatorToolDefinitionServiceTest {

    private lateinit var container: DIContainer
    private lateinit var service: OperatorToolDefinitionService
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        service = container.get()
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
    fun `getOperatorToolsForUser returns only the calling user's tools`() = runTest {
        val seeded = container.get<OperatorToolDefinitionSeeder>()
        seeded.ensureForUser(TestDefaults.user1.id)
        seeded.ensureForUser(TestDefaults.user2.id)

        val user1Tools = service.getOperatorToolsForUser(TestDefaults.user1.id)
        val user2Tools = service.getOperatorToolsForUser(TestDefaults.user2.id)

        assertEquals(1, user1Tools.size)
        assertEquals(1, user2Tools.size)
        assertEquals(TestDefaults.user1.id, user1Tools.single().userId)
        assertEquals(TestDefaults.user2.id, user2Tools.single().userId)
        assertTrue(user1Tools.single().id != user2Tools.single().id)
    }

    @Test
    fun `updateOperatorTool toggles enabled state for the owning user`() = runTest {
        val seeder = container.get<OperatorToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val original = seeded.single()
        assertTrue(original.isEnabled)

        val result = service.updateOperatorTool(
            userId = TestDefaults.user1.id,
            tool = original.copy(isEnabled = false)
        )

        assertTrue(result.isRight(), "update failed: ${result.leftOrNull()}")
        val updated = result.getOrNull()!!
        assertTrue(!updated.isEnabled)
        assertEquals(original.id, updated.id)
        assertEquals(TestDefaults.user1.id, updated.userId)
    }

    @Test
    fun `updateOperatorTool by non-owner returns Forbidden`() = runTest {
        val seeder = container.get<OperatorToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val original = seeded.single()

        val result = service.updateOperatorTool(
            userId = TestDefaults.user2.id,
            tool = original.copy(isEnabled = false)
        )

        assertTrue(result.isLeft())
        assertIs<UpdateOperatorToolError.Forbidden>(result.leftOrNull())
        // The owner's row must not be modified by the foreign update attempt.
        val after = service.getOperatorToolsForUser(TestDefaults.user1.id).single()
        assertTrue(after.isEnabled)
    }

    @Test
    fun `resetOperatorToolsToDefaults reconciles the user's tools`() = runTest {
        val seeder = container.get<OperatorToolDefinitionSeeder>()
        val seeded = seeder.ensureForUser(TestDefaults.user1.id).getOrNull()!!
        val edited = seeded.single().copy(
            description = "drifted description",
            isEnabled = false
        )
        container.get<eu.torvian.chatbot.server.service.core.ToolService>().updateTool(edited)

        val result = service.resetOperatorToolsToDefaults(TestDefaults.user1.id)

        assertTrue(result.isRight(), "reset failed: ${result.leftOrNull()}")
        val after = result.getOrNull()!!.single()
        assertEquals(OperatorToolCatalog.allTools.single().description, after.description)
        // Enabled choice is preserved across a reset.
        assertTrue(!after.isEnabled)
    }
}
