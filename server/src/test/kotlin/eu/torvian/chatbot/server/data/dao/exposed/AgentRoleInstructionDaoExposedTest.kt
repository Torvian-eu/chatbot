package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [AgentRoleInstructionDaoExposed].
 *
 * Verifies the ordered role↔instruction links against a real in-memory SQLite database: sequence
 * ordering on batch reads, contiguous zero-based rewrites, append-last positioning, link removal
 * semantics and the composite-PK backstop against duplicate links.
 */
class AgentRoleInstructionDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var instructionDao: InstructionDao
    private lateinit var testDataManager: TestDataManager

    private val role1 = TestDefaults.agentRole1.copy(id = 1L, modelPresetId = null)
    private val role2 = TestDefaults.agentRole2.copy(id = 2L, modelPresetId = null)

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        agentRoleInstructionDao = container.get()
        instructionDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(TestDataSet(users = listOf(TestDefaults.user1)))
        testDataManager.createTables(
            setOf(
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_INSTRUCTIONS
            )
        )
        testDataManager.insertAgentRole(role1)
        testDataManager.insertAgentRole(role2)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Inserts an instruction row with the given label.
     *
     * @param name The instruction label (used to identify rows in assertions).
     * @return The inserted row id.
     */
    private suspend fun insertInstruction(name: String): Long =
        instructionDao.insertInstruction(type = "custom", name = name, message = "Text", custom = null).id

    @Test
    fun `getLinksForRoles returns refs ordered by sequence per role`() = runTest {
        val first = insertInstruction("First")
        val second = insertInstruction("Second")
        val third = insertInstruction("Third")
        // Inserted out of order on purpose: the read must return the stored sequence order.
        agentRoleInstructionDao.replaceInstructionsForRole(role1.id, listOf(second, first, third))

        val links = agentRoleInstructionDao.getLinksForRoles(listOf(role1.id, role2.id))

        assertEquals(
            listOf(second, first, third),
            links.getValue(role1.id).map { it.instructionId }
        )
        assertEquals(listOf(0, 1, 2), links.getValue(role1.id).map { it.sequence })
        assertTrue(role2.id !in links, "a role without links is absent from the map")
    }

    @Test
    fun `replaceInstructionsForRole rewrites links contiguously and clears dropped ids`() = runTest {
        val first = insertInstruction("First")
        val second = insertInstruction("Second")
        val third = insertInstruction("Third")
        agentRoleInstructionDao.replaceInstructionsForRole(role1.id, listOf(first, second, third))

        agentRoleInstructionDao.replaceInstructionsForRole(role1.id, listOf(third, first))

        // Dropped ids lose their link; the rewrite re-normalizes the positions to 0..n-1.
        assertEquals(
            listOf(InstructionRef(third, 0), InstructionRef(first, 1)),
            agentRoleInstructionDao.getLinksForRoles(listOf(role1.id)).getValue(role1.id)
        )
    }

    @Test
    fun `appendInstructionForRole links at the end of the role's list`() = runTest {
        val first = insertInstruction("First")
        val second = insertInstruction("Second")
        agentRoleInstructionDao.replaceInstructionsForRole(role1.id, listOf(first))

        agentRoleInstructionDao.appendInstructionForRole(role1.id, second)

        assertEquals(
            listOf(InstructionRef(first, 0), InstructionRef(second, 1)),
            agentRoleInstructionDao.getLinksForRoles(listOf(role1.id)).getValue(role1.id)
        )
    }

    @Test
    fun `appendInstructionForRole starts an empty role at sequence zero`() = runTest {
        val first = insertInstruction("First")

        agentRoleInstructionDao.appendInstructionForRole(role1.id, first)

        assertEquals(
            listOf(InstructionRef(first, 0)),
            agentRoleInstructionDao.getLinksForRoles(listOf(role1.id)).getValue(role1.id)
        )
    }

    @Test
    fun `removeInstructionFromRole unlinks only the pair and keeps the row`() = runTest {
        val first = insertInstruction("First")
        val second = insertInstruction("Second")
        agentRoleInstructionDao.replaceInstructionsForRole(role1.id, listOf(first, second))
        agentRoleInstructionDao.replaceInstructionsForRole(role2.id, listOf(second))

        agentRoleInstructionDao.removeInstructionFromRole(role1.id, second)

        assertEquals(
            listOf(InstructionRef(first, 0)),
            agentRoleInstructionDao.getLinksForRoles(listOf(role1.id)).getValue(role1.id)
        )
        // The row survives (library orphan policy) and keeps its other role's link untouched.
        assertTrue(instructionDao.getInstructionById(second).isRight())
        assertEquals(
            listOf(second),
            agentRoleInstructionDao.getLinksForRoles(listOf(role2.id)).getValue(role2.id).map { it.instructionId }
        )
    }

    @Test
    fun `getLinkedRoleIdsForInstructions resolves role ids ascending`() = runTest {
        val shared = insertInstruction("Shared")
        val unlinked = insertInstruction("Unlinked")
        agentRoleInstructionDao.replaceInstructionsForRole(role2.id, listOf(shared))
        agentRoleInstructionDao.appendInstructionForRole(role1.id, shared)

        val links = agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(shared, unlinked))

        assertEquals(listOf(role1.id, role2.id), links.getValue(shared).toList(), "role ids come back ascending")
        assertTrue(unlinked !in links, "an instruction without links is absent from the map")
    }

    @Test
    fun `duplicate links are rejected at the storage level`() = runTest {
        val first = insertInstruction("First")
        agentRoleInstructionDao.replaceInstructionsForRole(role1.id, listOf(first))

        // The composite PK (agent_role_id, instruction_id) is the backstop behind the typed checks.
        assertFailsWith<ExposedSQLException> {
            agentRoleInstructionDao.appendInstructionForRole(role1.id, first)
        }
    }
}
