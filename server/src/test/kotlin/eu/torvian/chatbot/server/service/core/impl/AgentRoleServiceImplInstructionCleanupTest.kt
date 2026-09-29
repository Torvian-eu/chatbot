package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the reference-counted instruction cleanup of [AgentRoleServiceImpl.deleteRole].
 *
 * The sweep deletes exactly the rows whose last role link died with the deleted role: candidates
 * come only from the role's own link set, rows with a surviving link and never-linked rows keep
 * their entries, and a cleanup miss never fails the role deletion.
 */
class AgentRoleServiceImplInstructionCleanupTest : AgentRoleServiceImplTestBase() {

    /** The role every test deletes; it belongs to [userId]. */
    private val roleId = 1L

    /** Stubs the ownership check and the role-row delete that precede the sweep. */
    private fun stubOwnedRole() {
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleDao.deleteRole(roleId) } returns Unit.right()
    }

    /**
     * Stubs the role's pre-delete links, which are the sweep's candidate set.
     *
     * @param linkedInstructionIds Ids the role links at deletion time; empty means no candidates.
     */
    private fun stubRoleLinks(vararg linkedInstructionIds: Long) {
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(roleId)) } returns mapOf(
            roleId to linkedInstructionIds.mapIndexed { index, id -> InstructionRef(id, index) }
        )
    }

    /**
     * Verifies the core selection rule: candidates with no remaining link are deleted exactly once,
     * whether the remaining-link map is empty or partial.
     */
    @Test
    fun `deleteRole deletes candidates whose remaining link set is empty`() = runTest {
        stubOwnedRole()
        stubRoleLinks(10L, 11L)
        // Row 11 kept a link set that is explicitly empty; row 10 is simply absent (unlinked).
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) } returns
            mapOf(11L to emptySet())
        coEvery { instructionDao.deleteInstruction(any()) } returns Unit.right()

        val result = service.deleteRole(userId, roleId)

        assertTrue(result.isRight())
        assertEquals(listOf(10L, 11L), result.getOrNull()!!.deletedInstructionIds)
        assertTrue(result.getOrNull()!!.retainedInstructionIds.isEmpty())
        coVerify(exactly = 1) { instructionDao.deleteInstruction(10L) }
        coVerify(exactly = 1) { instructionDao.deleteInstruction(11L) }
    }

    /** Verifies a candidate with a link to a surviving role is not deleted, while a sole-linked one is. */
    @Test
    fun `deleteRole keeps rows still linked by another role`() = runTest {
        stubOwnedRole()
        stubRoleLinks(10L, 11L)
        // Row 10 keeps a link to surviving role 2 after this deletion; row 11 has none left.
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(10L, 11L)) } returns
            mapOf(10L to setOf(2L))
        coEvery { instructionDao.deleteInstruction(any()) } returns Unit.right()

        val result = service.deleteRole(userId, roleId)

        assertEquals(listOf(11L), result.getOrNull()!!.deletedInstructionIds)
        assertEquals(listOf(10L), result.getOrNull()!!.retainedInstructionIds)
        coVerify(exactly = 1) { instructionDao.deleteInstruction(11L) }
        coVerify(exactly = 0) { instructionDao.deleteInstruction(10L) }
    }

    /** Verifies the candidate set bounds the sweep: a never-linked library row can never be deleted. */
    @Test
    fun `deleteRole never touches unlinked library rows`() = runTest {
        stubOwnedRole()
        stubRoleLinks(10L)
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) } returns emptyMap()
        coEvery { instructionDao.deleteInstruction(any()) } returns Unit.right()

        val result = service.deleteRole(userId, roleId)

        assertEquals(listOf(10L), result.getOrNull()!!.deletedInstructionIds)
        // Row 99 exists in the library but the role never linked it, so it never becomes a candidate.
        coVerify(exactly = 0) { instructionDao.deleteInstruction(99L) }
        coVerify(exactly = 1) { instructionDao.deleteInstruction(10L) }
    }

    /** Verifies a role without instruction links short-circuits: no sweep read and no row delete. */
    @Test
    fun `deleteRole with no instruction links performs no sweep reads`() = runTest {
        stubOwnedRole()
        stubRoleLinks()

        service.deleteRole(userId, roleId)

        coVerify(exactly = 0) { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) }
        coVerify(exactly = 0) { instructionDao.deleteInstruction(any()) }
    }

    /** Verifies a cleanup miss is benign: the desired end state holds, so the deletion still succeeds. */
    @Test
    fun `a cleanup NotFound is swallowed and the delete succeeds`() = runTest {
        stubOwnedRole()
        stubRoleLinks(10L)
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) } returns emptyMap()
        coEvery { instructionDao.deleteInstruction(10L) } returns InstructionError.NotFound(10L).left()

        val result = service.deleteRole(userId, roleId)

        assertTrue(result.isRight())
        // A row this call did not delete is not reported as deleted.
        assertTrue(result.getOrNull()!!.deletedInstructionIds.isEmpty())
        assertTrue(result.getOrNull()!!.retainedInstructionIds.isEmpty())
    }

    /**
     * Pins the sweep's ordering: the candidate snapshot precedes the role delete (its cascade erases
     * the links) and the remaining-link count follows it (it must answer who still links a row now).
     */
    @Test
    fun `candidate ids are read before the role row is deleted`() = runTest {
        stubOwnedRole()
        stubRoleLinks(10L)
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) } returns emptyMap()
        coEvery { instructionDao.deleteInstruction(any()) } returns Unit.right()

        service.deleteRole(userId, roleId)

        coVerifyOrder {
            agentRoleInstructionDao.getLinksForRoles(listOf(roleId))
            agentRoleDao.deleteRole(roleId)
            agentRoleInstructionDao.getLinkedRoleIdsForInstructions(listOf(10L))
            instructionDao.deleteInstruction(10L)
        }
    }
}
