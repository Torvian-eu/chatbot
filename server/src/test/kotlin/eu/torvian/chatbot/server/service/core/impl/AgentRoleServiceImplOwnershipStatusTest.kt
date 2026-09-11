package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests for [AgentRoleServiceImpl] ownership collapse and the per-user disabled state. */
class AgentRoleServiceImplOwnershipStatusTest : AgentRoleServiceImplTestBase() {

    @Test
    fun `createRole should persist the role and set ownership on success`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        // The returned DTO loads the role's tools from the join table.
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals("Senior Architect", dto.name)
        // instructions are resolved to DTOs on return
        assertEquals(1, dto.instructions.size)
        assertEquals(AgentInstructionTypes.ROLE, dto.instructions[0].type)
        coVerify(exactly = 1) { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) }
        // The tool set is persisted into the join table (full replacement of the new role's empty set).
        coVerify(exactly = 1) { agentRoleToolDao.replaceToolsForRole(TestDefaults.agentRole1.id, emptySet()) }
    }
    @Test
    fun `getRoleById should return NotFound for a role owned by another user`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.getRoleById(userId, 1L)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
    }
    @Test
    fun `getAgentRoleById should return NotFound when the ownership row is missing`() = runTest {
        // A role row without an ownership row is a database inconsistency; the unscoped domain load
        // must surface it as not-found instead of degrading the owner id to 0 (which would silently
        // produce empty spawn allow-list prompts).
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.getAgentRoleById(userId, 1L)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleToolDao.getToolsForRole(any()) }
    }
    @Test
    fun `deleteRole should delete an owned role`() = runTest {
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleDao.deleteRole(1L) } returns Unit.right()

        val result = service.deleteRole(userId, 1L)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { agentRoleDao.deleteRole(1L) }
    }
    @Test
    fun `deleteRole should reject a role owned by another user`() = runTest {
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns 99L.right()

        val result = service.deleteRole(userId, 1L)

        // A foreign role collapses into NotFound (no existence leak) and is never deleted.
        val error = assertIs<DeleteAgentRoleError.NotFound>(result.leftOrNull())
        assertEquals(1L, error.id)
        coVerify(exactly = 0) { agentRoleDao.deleteRole(any()) }
    }
    @Test
    fun `deleteRole should reject a nonexistent role`() = runTest {
        coEvery {
            agentRoleOwnershipDao.getOwner(1L)
        } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.deleteRole(userId, 1L)

        val error = assertIs<DeleteAgentRoleError.NotFound>(result.leftOrNull())
        assertEquals(1L, error.id)
        coVerify(exactly = 0) { agentRoleDao.deleteRole(any()) }
    }
    @Test
    fun `getAllRolesForUser maps the per-user disabled flag and resolves presets in one batch read`() = runTest {
        val roleWithPreset = TestDefaults.agentRole1.copy(modelPresetId = validPreset.id)
        val roleWithoutPreset = TestDefaults.agentRole2.copy(modelPresetId = null)
        coEvery { agentRoleDao.getAllRolesForUser(userId) } returns listOf(roleWithPreset, roleWithoutPreset)
        coEvery { agentRoleToolDao.getToolsForRoles(listOf(1L, 2L)) } returns mapOf(1L to emptySet(), 2L to emptySet())
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(listOf(1L, 2L)) } returns emptyMap()
        // Role 1 is disabled for the user; role 2 is not. The service must resolve both flags from the
        // single batch DAO read (no N+1 per role).
        coEvery { agentRoleDisabledDao.getDisabledRoleIds(userId, listOf(1L, 2L)) } returns setOf(1L)
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(validPreset.id)) } returns listOf(validPreset)

        val roles = service.getAllRolesForUser(userId)

        assertEquals(2, roles.size)
        assertTrue(roles[0].disabled)
        assertFalse(roles[1].disabled)
        // The preset is resolved for the whole list in ONE batch read (NFR-5 / no N+1), and the
        // preset-bound role reports the preset's ids as derived values while the preset-less role
        // reports none.
        coVerify(exactly = 1) { modelPresetDao.getPresetsByIdsForUser(userId, listOf(validPreset.id)) }
        assertEquals(validPreset.id, roles[0].modelPresetId)
        assertEquals(validPreset.modelId, roles[0].modelId)
        assertEquals(validPreset.modelSettingsId, roles[0].modelSettingsId)
        assertEquals(null, roles[1].modelPresetId)
        assertEquals(null, roles[1].modelId)
        assertEquals(null, roles[1].modelSettingsId)
        coVerify(exactly = 1) { agentRoleDisabledDao.getDisabledRoleIds(userId, listOf(1L, 2L)) }
    }
    @Test
    fun `getRoleById reports disabled for the requesting user when a side-table row exists`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(userId, 1L) } returns true

        val result = service.getRoleById(userId, 1L)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.disabled)
    }
    @Test
    fun `disabled state is isolated per user for the same role`() = runTest {
        // userA disabled role 1; the service must keep userB's reads enabled for the same role. The
        // domain load (`getAgentRoleById`) is the requester-scoped path: the role row is resolved by
        // id, ownership stays with userA, and only the disabled flag varies with the requesting user
        // (exactly what future shared roles need).
        val userA = 7L
        val userB = 8L
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userA.right()
        coEvery { agentRoleDao.getRoleByNameForUser(userA, "Senior Architect", null) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(userA, 1L) } returns true
        coEvery { agentRoleDisabledDao.isRoleDisabled(userB, 1L) } returns false

        val dtoForA = service.getRoleById(userA, 1L).getOrNull()!!
        val byNameForA = service.getRoleByName(userA, "Senior Architect").getOrNull()!!
        val domainForA = service.getAgentRoleById(userA, 1L).getOrNull()!!
        val domainForB = service.getAgentRoleById(userB, 1L).getOrNull()!!

        assertTrue(dtoForA.disabled)
        assertTrue(byNameForA.disabled)
        assertTrue(domainForA.disabled)
        assertFalse(domainForB.disabled)
    }
    @Test
    fun `getAgentRoleById resolves the per-user disabled flag on the domain role`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()
        coEvery { agentRoleDisabledDao.isRoleDisabled(userId, 1L) } returns true

        val result = service.getAgentRoleById(userId, 1L)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.disabled)
        coVerify(exactly = 1) { agentRoleDisabledDao.isRoleDisabled(userId, 1L) }
    }
    @Test
    fun `createRole returns a role enabled for the new owner`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
        assertFalse(result.getOrNull()!!.disabled)
        // No disabled marker is consulted or written for a fresh role.
        coVerify(exactly = 0) { agentRoleDisabledDao.getDisabledRoleIds(any(), any()) }
        coVerify(exactly = 0) { agentRoleDisabledDao.isRoleDisabled(any(), any()) }
        coVerify(exactly = 0) { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) }
    }
    @Test
    fun `setRoleDisabled disables an owned role and returns the updated DTO`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.setRoleDisabled(userId, 1L, disabled = true)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.disabled)
        coVerify(exactly = 1) { agentRoleDisabledDao.setRoleDisabled(userId, 1L, true) }
    }
    @Test
    fun `setRoleDisabled re-enables idempotently and returns an enabled DTO`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { agentRoleToolDao.getToolsForRole(1L) } returns emptySet()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(1L) } returns emptySet()

        val result = service.setRoleDisabled(userId, 1L, disabled = false)

        assertTrue(result.isRight())
        assertFalse(result.getOrNull()!!.disabled)
        // Same idempotent write pattern regardless of the current side-table state.
        coVerify(exactly = 1) { agentRoleDisabledDao.setRoleDisabled(userId, 1L, false) }
    }
    @Test
    fun `setRoleDisabled rejects a foreign role without writing`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns GetOwnerError.ResourceNotFound("1").left()

        val result = service.setRoleDisabled(userId, 1L, disabled = true)

        assertTrue(result.isLeft())
        assertIs<eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) }
    }
}
