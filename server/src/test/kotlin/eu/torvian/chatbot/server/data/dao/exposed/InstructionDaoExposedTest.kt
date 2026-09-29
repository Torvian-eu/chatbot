package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.InstructionError
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [InstructionDaoExposed].
 *
 * Verifies the shareable instruction rows against a real in-memory SQLite database: timestamp
 * management, plain and owner-scoped batch reads (the batch doubles as the ownership check), the
 * deterministic owned listing (including unassigned library rows), full-replacement content updates
 * and the delete rules (ownership goes with the row, and a row a role still links is reported as a
 * storage refusal).
 */
class InstructionDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var testDataManager: TestDataManager

    private val user = TestDefaults.user1
    private val otherUser = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        instructionDao = container.get()
        instructionOwnershipDao = container.get()
        agentRoleInstructionDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(TestDataSet(users = listOf(user, otherUser)))
        testDataManager.createTables(
            setOf(
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_INSTRUCTIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Inserts an owned instruction row for the default user.
     *
     * @param name The instruction label.
     * @param message The stored message (null marks a generated-message kind).
     * @return The inserted row.
     */
    private suspend fun insertOwned(name: String, message: String? = "Text") =
        instructionDao.insertInstruction(type = "custom", name = name, message = message, custom = null)
            .also { check(instructionOwnershipDao.setOwner(it.id, user.id).isRight()) }

    @Test
    fun `insertInstruction stores the row with created_at equal to updated_at`() = runTest {
        val created = instructionDao.insertInstruction(
            type = "custom",
            name = "Tone",
            message = "Be concise",
            custom = """{"k":1}"""
        )

        assertEquals(created.createdAt, created.updatedAt)
        val reloaded = instructionDao.getInstructionById(created.id).getOrNull()!!
        assertEquals("Tone", reloaded.name)
        assertEquals("Be concise", reloaded.message)
        assertEquals("""{"k":1}""", reloaded.custom)
    }

    @Test
    fun `insertInstruction stores a null message for generated-message kinds`() = runTest {
        val created = instructionDao.insertInstruction(
            type = "spawnable_agents",
            name = "Available agents",
            message = null,
            custom = null
        )

        assertNull(instructionDao.getInstructionById(created.id).getOrNull()!!.message)
    }

    @Test
    fun `getInstructionsByIds resolves a plain batch in requested order`() = runTest {
        val first = insertOwned("First")
        val second = insertOwned("Second")

        val resolved = instructionDao.getInstructionsByIds(listOf(second.id, first.id, 999L))

        // SQL does not guarantee IN-list order, and missing ids are skipped.
        assertEquals(listOf(second.id, first.id), resolved.map { it.id })
    }

    @Test
    fun `getInstructionsByIdsForUser omits missing and foreign rows`() = runTest {
        val owned = insertOwned("Owned")
        val foreign = instructionDao.insertInstruction(type = "custom", name = "Foreign", message = "x", custom = null)
        check(instructionOwnershipDao.setOwner(foreign.id, otherUser.id).isRight())

        val resolved = instructionDao.getInstructionsByIdsForUser(user.id, listOf(owned.id, foreign.id, 999L))

        // Missing and foreign collapse to the same omission (no existence leak).
        assertEquals(listOf(owned.id), resolved.map { it.id })
    }

    @Test
    fun `getAllInstructionsForUser lists only the owner's rows by id including unassigned ones`() = runTest {
        val first = insertOwned("First")
        val second = insertOwned("Second")
        val foreign = instructionDao.insertInstruction(type = "custom", name = "Foreign", message = "x", custom = null)
        check(instructionOwnershipDao.setOwner(foreign.id, otherUser.id).isRight())

        val all = instructionDao.getAllInstructionsForUser(user.id)

        // Unassigned rows (no role links at all) stay listed as library entries.
        assertEquals(listOf(first.id, second.id), all.map { it.id })
    }

    @Test
    fun `getInstructionById reports a typed error for a missing row`() = runTest {
        val error = assertIs<InstructionError.NotFound>(instructionDao.getInstructionById(999L).leftOrNull())
        assertEquals(999L, error.id)
    }

    @Test
    fun `updateInstruction replaces content, advances updated_at and preserves created_at`() = runTest {
        val row = insertOwned("Old name", message = "Old message")
        val updated = instructionDao.updateInstruction(
            row.copy(type = "role", name = "New name", message = "New message", custom = """{"modelId":1}""")
        ).getOrNull()!!

        assertEquals("New name", updated.name)
        assertEquals(row.createdAt, updated.createdAt)
        val reloaded = instructionDao.getInstructionById(row.id).getOrNull()!!
        assertEquals("role", reloaded.type)
        assertEquals("New message", reloaded.message)
        assertEquals("""{"modelId":1}""", reloaded.custom)
    }

    @Test
    fun `updateInstruction reports a typed error for a missing row`() = runTest {
        val missing = TestDefaults.instruction1.copy(id = 999L)

        val error = assertIs<InstructionError.NotFound>(
            instructionDao.updateInstruction(missing).leftOrNull()
        )
        assertEquals(999L, error.id)
    }

    @Test
    fun `deleteInstruction removes an unlinked row and its ownership but keeps other rows linked`() = runTest {
        val kept = insertOwned("Kept")
        val deleted = insertOwned("Deleted")
        val role = TestDefaults.agentRole1.copy(id = 1L, modelPresetId = null)
        testDataManager.insertAgentRole(role)
        agentRoleInstructionDao.replaceInstructionsForRole(role.id, listOf(kept.id, deleted.id))
        // Only an unlinked row is deletable: the link is dropped first.
        agentRoleInstructionDao.removeInstructionFromRole(role.id, deleted.id)

        assertTrue(instructionDao.deleteInstruction(deleted.id).isRight())

        assertEquals(1, instructionDao.getAllInstructionsForUser(user.id).size)
        // The surviving link keeps its relative order untouched.
        assertEquals(
            listOf(kept.id),
            agentRoleInstructionDao.getLinksForRoles(listOf(role.id)).getValue(role.id).map { it.instructionId }
        )
        assertNull(instructionOwnershipDao.getOwner(deleted.id).getOrNull())
    }

    @Test
    fun `deleteInstruction reports a storage refusal while a role still links the row`() = runTest {
        val linked = insertOwned("Linked")
        val role = TestDefaults.agentRole1.copy(id = 1L, modelPresetId = null)
        testDataManager.insertAgentRole(role)
        agentRoleInstructionDao.replaceInstructionsForRole(role.id, listOf(linked.id))

        // The link constraint refuses the delete, and the DAO reports it as a typed error rather than
        // letting the raw SQL failure escape.
        val error = assertIs<InstructionError.ForeignKeyViolation>(
            instructionDao.deleteInstruction(linked.id).leftOrNull()
        )
        assertTrue(error.message.isNotBlank())

        // Nothing was removed: the row, its ownership and its link all survive.
        assertNotNull(instructionDao.getInstructionById(linked.id).getOrNull())
        assertNotNull(instructionOwnershipDao.getOwner(linked.id).getOrNull())
        assertEquals(
            listOf(linked.id),
            agentRoleInstructionDao.getLinksForRoles(listOf(role.id)).getValue(role.id).map { it.instructionId }
        )
    }
}
