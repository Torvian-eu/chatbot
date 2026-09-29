package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.dao.error.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.AssignInstructionError
import eu.torvian.chatbot.server.service.core.error.agent.UnassignInstructionError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the two instruction-link operations of [AgentRoleServiceImpl]: assigning appends the row
 * last after ownership, duplicate and per-role-rule checks, while unassigning removes only the link and
 * leaves the role's remaining positions contiguous.
 *
 * Both operations answer with the role's state after the write, so every test also fixes the role's
 * links as the echo re-reads them.
 */
class AgentRoleServiceImplInstructionLinkTest : AgentRoleServiceImplTestBase() {

    /** The role every test links to; it belongs to [userId]. */
    private val roleId = 1L

    /**
     * Stubs the role as existing, owned and readable, and models its links as mutable state so the
     * post-write echo reads what the write left behind.
     *
     * @param initialLinks The role's links before the operation under test.
     * @return The mutable link list the write updates.
     */
    private fun stubOwnedRoleWithLinks(initialLinks: List<InstructionRef>): MutableList<InstructionRef> {
        val links = initialLinks.toMutableList()
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(roleId) } returns emptySet()
        coEvery { agentRoleInstructionDao.getLinksForRoles(listOf(roleId)) } answers { mapOf(roleId to links.toList()) }
        // The echo maps the linked rows themselves, so the content read has to answer for any id set.
        coEvery { instructionDao.getInstructionsByIds(any()) } answers {
            firstArg<List<Long>>().map { instructionId ->
                TestDefaults.instruction1.copy(
                    id = instructionId,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Instruction $instructionId",
                    message = "Text"
                )
            }
        }
        coEvery { agentRoleInstructionDao.getLinkedRoleIdsForInstructions(any()) } answers {
            firstArg<List<Long>>().associateWith { setOf(roleId) }
        }
        return links
    }

    /**
     * Stubs one instruction row as owned by the caller, with the given kind.
     *
     * @param instructionId The row's id.
     * @param type The row's kind.
     */
    private fun stubOwnedInstruction(instructionId: Long, type: String = AgentInstructionTypes.CUSTOM) {
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(instructionId)) } returns
            listOf(TestDefaults.instruction1.copy(id = instructionId, type = type, message = "Text"))
    }

    @Test
    fun `assignInstruction appends the row last and echoes the new order`() = runTest {
        val links = stubOwnedRoleWithLinks(listOf(InstructionRef(instructionId = 5L, sequence = 0)))
        stubOwnedInstruction(6L)
        coEvery { agentRoleInstructionDao.appendInstructionForRole(roleId, 6L) } answers {
            // The DAO appends behind the current maximum position; model that so the echo reflects it.
            links += InstructionRef(instructionId = 6L, sequence = (links.maxOfOrNull { it.sequence } ?: -1) + 1)
        }

        val result = service.assignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(6L, links.last().instructionId)
        // The echo reports the stored order, so the caller sees the append without a second read.
        assertEquals(listOf(5L, 6L), result.getOrNull()!!.instructions.map { it.id })
        coVerify(exactly = 1) { agentRoleInstructionDao.appendInstructionForRole(roleId, 6L) }
        // A link write never authors content.
        coVerify(exactly = 0) { instructionDao.insertInstruction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { instructionDao.updateInstruction(any()) }
    }

    @Test
    fun `assignInstruction rejects an already linked row without writing`() = runTest {
        stubOwnedRoleWithLinks(listOf(InstructionRef(instructionId = 6L, sequence = 0)))
        stubOwnedInstruction(6L)

        val result = service.assignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(AssignInstructionError.AlreadyLinked(6L), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.appendInstructionForRole(any(), any()) }
    }

    @Test
    fun `assignInstruction rejects a second singleton kind with the shared rule wording`() = runTest {
        stubOwnedRoleWithLinks(listOf(InstructionRef(instructionId = 5L, sequence = 0)))
        stubOwnedInstruction(6L, type = AgentInstructionTypes.ROLE)
        // The role already holds a 'role' row, so the effective list would break the per-role rule.
        coEvery { instructionDao.getInstructionsByIds(listOf(5L)) } returns listOf(
            TestDefaults.instruction1.copy(id = 5L, type = AgentInstructionTypes.ROLE, message = "Text")
        )

        val result = service.assignInstruction(userId, roleId, instructionId = 6L)

        val error = assertIs<AssignInstructionError.InstructionValidationFailed>(result.leftOrNull())
        assertTrue(error.reason.contains("At most one 'role' instruction"), error.reason)
        coVerify(exactly = 0) { agentRoleInstructionDao.appendInstructionForRole(any(), any()) }
    }

    @Test
    fun `assignInstruction rejects a missing or foreign instruction as not found`() = runTest {
        stubOwnedRoleWithLinks(emptyList())
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(6L)) } returns emptyList()

        val result = service.assignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(AssignInstructionError.InstructionNotFound(6L), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.appendInstructionForRole(any(), any()) }
    }

    @Test
    fun `assignInstruction rejects a role the caller does not own`() = runTest {
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns (userId + 1).right()

        val result = service.assignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(AssignInstructionError.RoleNotFound(roleId), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.appendInstructionForRole(any(), any()) }
    }

    @Test
    fun `assignInstruction rejects a missing role`() = runTest {
        coEvery { agentRoleDao.getRoleById(roleId) } returns AgentRoleError.NotFound(roleId).left()

        val result = service.assignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(AssignInstructionError.RoleNotFound(roleId), result.leftOrNull())
    }

    @Test
    fun `unassignInstruction removes only the link and keeps the remaining positions contiguous`() = runTest {
        val links = stubOwnedRoleWithLinks(
            listOf(
                InstructionRef(instructionId = 5L, sequence = 0),
                InstructionRef(instructionId = 6L, sequence = 1),
                InstructionRef(instructionId = 7L, sequence = 2)
            )
        )
        stubOwnedInstruction(6L)
        coEvery { agentRoleInstructionDao.removeInstructionFromRole(roleId, 6L) } answers {
            links.removeIf { it.instructionId == 6L }
        }
        coEvery { agentRoleInstructionDao.replaceInstructionsForRole(roleId, any()) } answers {
            // The re-normalization rewrites the surviving positions contiguously.
            val orderedIds = secondArg<List<Long>>()
            links.clear()
            links += orderedIds.mapIndexed { index, instructionId -> InstructionRef(instructionId, index) }
        }

        val result = service.unassignInstruction(userId, roleId, instructionId = 6L)

        coVerify(exactly = 1) { agentRoleInstructionDao.removeInstructionFromRole(roleId, 6L) }
        // Removing the middle link leaves a hole, so the survivors are rewritten as 0..n-1 while
        // keeping their relative order.
        coVerify(exactly = 1) { agentRoleInstructionDao.replaceInstructionsForRole(roleId, listOf(5L, 7L)) }
        assertEquals(listOf(0, 1), links.map { it.sequence })
        assertEquals(listOf(5L, 7L), result.getOrNull()!!.instructions.map { it.id })
        // The instruction rows themselves are untouched; only the explicit delete removes one.
        coVerify(exactly = 0) { instructionDao.deleteInstruction(any()) }
    }

    @Test
    fun `unassignInstruction leaves the positions untouched when the last link is removed`() = runTest {
        val links = stubOwnedRoleWithLinks(
            listOf(
                InstructionRef(instructionId = 5L, sequence = 0),
                InstructionRef(instructionId = 6L, sequence = 1)
            )
        )
        stubOwnedInstruction(6L)
        coEvery { agentRoleInstructionDao.removeInstructionFromRole(roleId, 6L) } answers {
            links.removeIf { it.instructionId == 6L }
        }

        val result = service.unassignInstruction(userId, roleId, instructionId = 6L)

        // Dropping the last position cannot create a gap, so no rewrite is needed.
        coVerify(exactly = 1) { agentRoleInstructionDao.removeInstructionFromRole(roleId, 6L) }
        coVerify(exactly = 0) { agentRoleInstructionDao.replaceInstructionsForRole(any(), any()) }
        assertEquals(listOf(5L), result.getOrNull()!!.instructions.map { it.id })
    }

    @Test
    fun `unassignInstruction rejects a pair that is not linked`() = runTest {
        stubOwnedRoleWithLinks(listOf(InstructionRef(instructionId = 5L, sequence = 0)))
        stubOwnedInstruction(6L)

        val result = service.unassignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(UnassignInstructionError.NotLinked(6L), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.removeInstructionFromRole(any(), any()) }
    }

    @Test
    fun `unassignInstruction rejects a missing or foreign instruction as not found`() = runTest {
        stubOwnedRoleWithLinks(listOf(InstructionRef(instructionId = 6L, sequence = 0)))
        coEvery { instructionDao.getInstructionsByIdsForUser(userId, listOf(6L)) } returns emptyList()

        val result = service.unassignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(UnassignInstructionError.InstructionNotFound(6L), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.removeInstructionFromRole(any(), any()) }
    }

    @Test
    fun `unassignInstruction rejects a role the caller does not own`() = runTest {
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(id = roleId).right()
        coEvery { agentRoleOwnershipDao.getOwner(roleId) } returns (userId + 1).right()

        val result = service.unassignInstruction(userId, roleId, instructionId = 6L)

        assertEquals(UnassignInstructionError.RoleNotFound(roleId), result.leftOrNull())
        coVerify(exactly = 0) { agentRoleInstructionDao.removeInstructionFromRole(any(), any()) }
    }
}
