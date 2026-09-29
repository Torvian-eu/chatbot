package eu.torvian.chatbot.server.service.core.impl

import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
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
 * Atomicity tests for agent-role saves with inline instruction specs and for the instruction cleanup
 * that rides a role delete, against a real in-memory SQLite database and the real [AgentRoleService]
 * wiring.
 *
 * The service-level scenario tests pin the save guarantees end-to-end (a failing multi-spec save
 * persists no instruction rows, owners, links or role; a corrected retry produces exactly one save's
 * rows) and the role-delete sweep (sole-linked rows die with the role, shared and never-linked rows
 * survive, and a failing outer block unwinds the delete and the sweep together). The rollback probe
 * pins the transaction boundary the materializer relies on: rows already written inside a transaction
 * block disappear when the block reports a failure.
 */
class AgentRoleServiceInstructionAtomicityTest {

    private lateinit var container: DIContainer
    private lateinit var agentRoleService: AgentRoleService
    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var agentRoleDao: AgentRoleDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var testDataManager: TestDataManager

    /** Requesting user id; the seeded test user. */
    private val userId = TestDefaults.user1.id

    /** A well-formed inline content spec, accepted by the instruction endpoints' rules. */
    private fun validContent(name: String) = CreateInstructionRequest(
        type = AgentInstructionTypes.ROLE,
        name = name,
        message = "You are a writer."
    )

    /** An inline content spec rejected by the shared content rules (blank name). */
    private fun invalidContent() = CreateInstructionRequest(
        type = AgentInstructionTypes.CUSTOM,
        name = "  ",
        message = "x"
    )

    /**
     * Boots the real service stack and creates only the tables a preset-less role save touches.
     */
    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        agentRoleService = container.get()
        instructionDao = container.get()
        instructionOwnershipDao = container.get()
        agentRoleInstructionDao = container.get()
        agentRoleDao = container.get()
        transactionScope = container.get()
        testDataManager = container.get()

        testDataManager.setup(TestDataSet(users = listOf(TestDefaults.user1)))
        testDataManager.createTables(
            setOf(
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.AGENT_ROLE_TOOLS,
                Table.AGENT_ROLE_SPAWNABLE_ROLES,
                Table.AGENT_ROLE_DISABLED,
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS,
                Table.CHAT_SESSIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Asserts that the instruction library holds no row at all — including ownerless leftovers a
     * joined read would hide.
     */
    private suspend fun assertLibraryEmpty() {
        assertTrue(instructionDao.getAllInstructionsForUser(userId).isEmpty(), "library must be empty")
        // Probe the id space directly so an ownerless row cannot hide from the owner-scoped listing.
        (1L..4L).forEach { id ->
            assertTrue(instructionDao.getInstructionById(id).isLeft(), "row $id must not exist")
            assertTrue(instructionOwnershipDao.getOwner(id).isLeft(), "row $id must have no owner")
        }
    }

    /**
     * Verifies that a save whose later slot is invalid persists nothing: no rows, owners, links or
     * role — the guarantee an abandoned or failed save leaves the library untouched.
     */
    @Test
    fun `a save failing after the first slot is valid writes nothing`() = runTest {
        val request = CreateAgentRoleRequest(
            name = "writer",
            instructionSpecs = listOf(
                InstructionSlot.Create(validContent("Role")),
                InstructionSlot.Create(invalidContent())
            )
        )

        val result = agentRoleService.createRole(userId, request)

        assertIs<CreateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        assertLibraryEmpty()
        assertTrue(agentRoleDao.getAllRolesForUser(userId).isEmpty(), "no role row may survive")
    }

    /**
     * Verifies that retrying the failed save verbatim creates nothing extra and that one corrected
     * save then produces exactly one save's rows — there are no partial ids to de-duplicate.
     */
    @Test
    fun `a verbatim retry of the failed save creates nothing extra and a corrected save succeeds cleanly`() = runTest {
        val failing = CreateAgentRoleRequest(
            name = "writer",
            instructionSpecs = listOf(
                InstructionSlot.Create(validContent("Role")),
                InstructionSlot.Create(invalidContent())
            )
        )
        agentRoleService.createRole(userId, failing)

        // Verbatim retry: the earlier attempt wrote nothing, so the retry cannot duplicate anything.
        assertTrue(agentRoleService.createRole(userId, failing).isLeft())
        assertLibraryEmpty()

        val corrected = failing.copy(
            instructionSpecs = listOf(
                InstructionSlot.Create(validContent("Role")),
                InstructionSlot.Create(validContent("Tone").copy(type = AgentInstructionTypes.CUSTOM))
            )
        )
        val saved = agentRoleService.createRole(userId, corrected)

        val role = saved.getOrNull() ?: error("corrected save must succeed: ${saved.leftOrNull()}")
        // Exactly one save's rows: two instructions with their owners and two ordered links.
        assertEquals(2, instructionDao.getAllInstructionsForUser(userId).size)
        assertEquals(listOf("Role", "Tone"), role.instructions.map { it.name })
        val links = agentRoleInstructionDao.getLinksForRoles(listOf(role.id)).getValue(role.id)
        assertEquals(role.instructions.map { it.id }, links.map { it.instructionId })
    }

    /**
     * Verifies that a failing update save leaves the existing library and the role's links untouched.
     */
    @Test
    fun `a failing update save leaves the existing library and links untouched`() = runTest {
        val seededRowId = instructionDao
            .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Style", message = "Be concise", custom = null)
            .id
        instructionOwnershipDao.setOwner(seededRowId, userId)
        val seededRole = TestDefaults.agentRole1.copy(id = 1L, name = "writer", modelPresetId = null)
        testDataManager.insertAgentRole(seededRole)
        testDataManager.insertAgentRoleOwnership(seededRole.id, userId)
        agentRoleInstructionDao.replaceInstructionsForRole(seededRole.id, listOf(seededRowId))

        val request = UpdateAgentRoleRequest(
            name = "writer",
            instructionSpecs = listOf(
                InstructionSlot.Link(seededRowId),
                InstructionSlot.Create(invalidContent())
            )
        )
        val result = agentRoleService.updateRole(userId, seededRole.id, request)

        assertIs<UpdateAgentRoleError.InstructionValidationFailed>(result.leftOrNull())
        // The seeded row is the only one and its link is unchanged.
        assertEquals(listOf("Style"), instructionDao.getAllInstructionsForUser(userId).map { it.name })
        assertEquals(
            listOf(seededRowId),
            agentRoleInstructionDao.getLinksForRoles(listOf(seededRole.id))
                .getValue(seededRole.id)
                .map { it.instructionId }
        )
    }

    /**
     * Pins the transaction boundary (boundary probe): rows and owners already written inside a
     * transaction block are unwound when the block reports a failure, which is what makes a role save
     * with inline specs all-or-nothing.
     */
    @Test
    fun `a failing transaction block rolls back rows already inserted inside it`() = runTest {
        val outcome = transactionScope.transaction {
            either<String, Long> {
                val created = instructionDao.insertInstruction(
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Ghost",
                    message = "x",
                    custom = null
                )
                instructionOwnershipDao.setOwner(created.id, userId)
                // Failing after the writes is the shape a late materialization failure produces.
                ensure(false) { "late failure" }
                created.id
            }
        }

        assertTrue(outcome.isLeft())
        assertLibraryEmpty()
    }

    /**
     * Seeds an owned preset-less role row.
     *
     * @param roleId The id the role row gets.
     * @param name The role's unique name.
     */
    private suspend fun seedRole(roleId: Long, name: String) {
        testDataManager.insertAgentRole(
            TestDefaults.agentRole1.copy(id = roleId, name = name, modelPresetId = null)
        )
        testDataManager.insertAgentRoleOwnership(roleId, userId)
    }

    /**
     * Seeds an owned instruction row.
     *
     * @param name The row's display label.
     * @return The new row's id.
     */
    private suspend fun seedInstruction(name: String): Long {
        val id = instructionDao
            .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = name, message = "Text", custom = null)
            .id
        instructionOwnershipDao.setOwner(id, userId)
        return id
    }

    /**
     * Asserts a row and its ownership link are both gone.
     *
     * @param instructionId The row id to check.
     */
    private suspend fun assertRowAndOwnerGone(instructionId: Long) {
        assertTrue(instructionDao.getInstructionById(instructionId).isLeft(), "row $instructionId must be gone")
        assertTrue(instructionOwnershipDao.getOwner(instructionId).isLeft(), "owner of $instructionId must be gone")
    }

    /**
     * Asserts a row and its ownership link both survive.
     *
     * @param instructionId The row id to check.
     */
    private suspend fun assertRowAndOwnerKept(instructionId: Long) {
        assertTrue(instructionDao.getInstructionById(instructionId).isRight(), "row $instructionId must survive")
        assertTrue(instructionOwnershipDao.getOwner(instructionId).isRight(), "owner of $instructionId must survive")
    }

    /**
     * Verifies the role-delete sweep removes the sole-linked row (with its owner) while the shared
     * and the never-linked rows — and the surviving role's stored link order — stay untouched.
     */
    @Test
    fun `role delete removes the sole-linked row and keeps the shared and the never-linked rows`() = runTest {
        val sharedId = seedInstruction("Shared")
        val soleId = seedInstruction("Sole")
        val unlinkedId = seedInstruction("Unlinked")
        seedRole(1L, "writer")
        seedRole(2L, "reviewer")
        agentRoleInstructionDao.replaceInstructionsForRole(1L, listOf(sharedId, soleId))
        agentRoleInstructionDao.replaceInstructionsForRole(2L, listOf(sharedId))
        val survivorLinksBefore = agentRoleInstructionDao.getLinksForRoles(listOf(2L)).getValue(2L)

        val result = agentRoleService.deleteRole(userId, 1L)

        assertTrue(result.isRight())
        // The result distinguishes the swept row from the row kept on the surviving role.
        assertEquals(listOf(soleId), result.getOrNull()!!.deletedInstructionIds)
        assertEquals(listOf(sharedId), result.getOrNull()!!.retainedInstructionIds)
        assertRowAndOwnerGone(soleId)
        assertRowAndOwnerKept(sharedId)
        assertRowAndOwnerKept(unlinkedId)
        // The surviving role's links keep their exact stored state (no rewrites, no new sequence gaps).
        assertEquals(survivorLinksBefore, agentRoleInstructionDao.getLinksForRoles(listOf(2L)).getValue(2L))
    }

    /**
     * Verifies a shared row dies only with the deletion that removes its actual last link.
     */
    @Test
    fun `an instruction that loses its last link to a second role deletion is removed then`() = runTest {
        val sharedId = seedInstruction("Shared")
        seedRole(1L, "writer")
        seedRole(2L, "reviewer")
        agentRoleInstructionDao.replaceInstructionsForRole(1L, listOf(sharedId))
        agentRoleInstructionDao.replaceInstructionsForRole(2L, listOf(sharedId))

        val afterFirst = agentRoleService.deleteRole(userId, 1L)
        assertTrue(afterFirst.isRight())
        assertEquals(emptyList<Long>(), afterFirst.getOrNull()!!.deletedInstructionIds)
        assertEquals(listOf(sharedId), afterFirst.getOrNull()!!.retainedInstructionIds)
        assertRowAndOwnerKept(sharedId)

        val afterSecond = agentRoleService.deleteRole(userId, 2L)
        assertTrue(afterSecond.isRight())
        assertEquals(listOf(sharedId), afterSecond.getOrNull()!!.deletedInstructionIds)
        assertEquals(emptyList<Long>(), afterSecond.getOrNull()!!.retainedInstructionIds)
        assertRowAndOwnerGone(sharedId)
    }

    /**
     * Verifies atomicity with the role delete: a failure raised after the sweep inside the outermost
     * transaction block persists neither the role deletion nor any instruction deletion.
     */
    @Test
    fun `a failure after the role delete persists nothing`() = runTest {
        val sharedId = seedInstruction("Shared")
        val soleId = seedInstruction("Sole")
        seedRole(1L, "writer")
        seedRole(2L, "reviewer")
        agentRoleInstructionDao.replaceInstructionsForRole(1L, listOf(sharedId, soleId))
        agentRoleInstructionDao.replaceInstructionsForRole(2L, listOf(sharedId))
        val roleLinksBefore = agentRoleInstructionDao.getLinksForRoles(listOf(1L)).getValue(1L)

        // The nested deleteRole call joins this outermost transaction block; failing the block after
        // the delete must unwind the role deletion and the instruction sweep in one rollback.
        val outcome = transactionScope.transaction {
            either<String, Unit> {
                agentRoleService.deleteRole(userId, 1L)
                    .mapLeft { error -> "delete must succeed before the late failure: $error" }
                    .bind()
                ensure(false) { "late failure" }
            }
        }

        assertTrue(outcome.isLeft())
        assertTrue(agentRoleDao.getRoleById(1L).isRight(), "the role must survive the rollback")
        assertEquals(roleLinksBefore, agentRoleInstructionDao.getLinksForRoles(listOf(1L)).getValue(1L))
        assertRowAndOwnerKept(sharedId)
        assertRowAndOwnerKept(soleId)
    }

    /**
     * Verifies explicit unassign keeps the row even when it removes the last link: only a role
     * deletion sweeps zero-link rows.
     */
    @Test
    fun `explicit unassign still keeps the row with zero links`() = runTest {
        val soleId = seedInstruction("Sole")
        seedRole(1L, "writer")
        agentRoleInstructionDao.replaceInstructionsForRole(1L, listOf(soleId))

        val result = agentRoleService.unassignInstruction(userId, 1L, soleId)

        assertTrue(result.isRight())
        assertTrue(agentRoleInstructionDao.getLinksForRoles(listOf(1L))[1L].isNullOrEmpty())
        assertRowAndOwnerKept(soleId)
    }
}
