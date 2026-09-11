package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao.AgentRoleNameScope
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests for [AgentRoleServiceImpl] project-scope rules: name uniqueness per scope and the same-project spawn allow-list. */
class AgentRoleServiceImplScopeRulesTest : AgentRoleServiceImplTestBase() {

    @Test
    fun `createRole should reject a duplicate name for the same user`() = runTest {
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user already owns a same-named role in the unassociated scope (empty project set), which
        // overlaps the candidate's unassociated scope under the scope-intersection rule.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 9L, projectId = null)
        )

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isLeft())
        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { agentRoleToolDao.replaceToolsForRole(any(), any()) }
    }
    @Test
    fun `createRole should allow reusing a name owned by another user`() = runTest {
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // Another user owns "Senior Architect"; the requesting user does not, so the name is free.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns emptyList()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
    }
    @Test
    fun `createRole rejects a same-name unassociated role (scope-intersection rule)`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = null)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()

        val result = service.createRole(userId, validRequest())

        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }
    @Test
    fun `createRole rejects a same-name role sharing a project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 1L)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)

        val result = service.createRole(userId, validRequest().copy(projectId = 1L))

        assertIs<CreateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
    }
    @Test
    fun `createRole allows a same-name role in a disjoint project`() = runTest {
        // The existing "Senior Architect" lives in project 1 only; the candidate joins project 2 only
        // — non-overlapping scopes, same user, same name: allowed.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 1L)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(projectId = 2L))

        assertTrue(result.isRight())
        // The single membership column is written together with the row.
        coVerify(exactly = 1) {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), eq(2L))
        }
    }
    @Test
    fun `createRole allows an unassociated role next to a same-name in-project role`() = runTest {
        // The existing "Senior Architect" belongs to project 1; the candidate is unassociated (empty
        // set) — unassociated scope vs project scope never conflict.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 1L)
        )
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest())

        assertTrue(result.isRight())
    }
    @Test
    fun `updateRole re-checks the name when only the project changed (self excluded)`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The role currently belongs to project 1; the update moves it to project 2.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        // A DIFFERENT role (5L) already occupies project 2 with the same name -> scope overlap, reject.
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Renamed") } returns listOf(
            AgentRoleNameScope(roleId = 5L, projectId = 2L)
        )
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            ),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertIs<UpdateAgentRoleError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }
    @Test
    fun `updateRole re-checking the scope excludes the role being updated`() = runTest {
        // The role currently lives in project 1; the update drops it to the unassociated scope, which
        // still changes the scope, so the recheck runs; the same-name row returned by the scope query
        // IS the role itself, which must not conflict with itself.
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { agentRoleDao.getRoleNameScopesForUser(userId, "Senior Architect") } returns listOf(
            AgentRoleNameScope(roleId = 1L, projectId = 1L)
        )
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Senior Architect",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect.")
            )
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // The row update ran even though the scope query returned the role itself.
        coVerify(exactly = 1) { agentRoleDao.updateRole(any()) }
    }
    @Test
    fun `createRole rejects a spawnable target in a different project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The source role joins project 1; the spawn target lives in project 2.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 5L, projectId = 2L))

        val result = service.createRole(userId, validRequest().copy(projectId = 1L, spawnableAgentRoleIds = setOf(5L)))

        val error = assertIs<CreateAgentRoleError.SpawnableRoleNotInProject>(result.leftOrNull())
        assertEquals(5L, error.roleId)
        assertEquals(1L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }
    @Test
    fun `createRole rejects a spawnable target when the source is unassociated and the target is in a project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The source role stays unassociated (projectId null); the target belongs to project 1.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 5L, projectId = 1L))

        val result = service.createRole(userId, validRequest().copy(spawnableAgentRoleIds = setOf(5L)))

        val error = assertIs<CreateAgentRoleError.SpawnableRoleNotInProject>(result.leftOrNull())
        assertEquals(5L, error.roleId)
        assertEquals(null, error.projectId)
    }
    @Test
    fun `createRole accepts spawn targets sharing the role's project`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)
        // Both targets are unassociated like the source role -> legal.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L, 6L)) } returns listOf(
            TestDefaults.agentRole1.copy(id = 5L),
            TestDefaults.agentRole2.copy(id = 6L)
        )
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(spawnableAgentRoleIds = setOf(5L, 6L)))

        assertTrue(result.isRight())
    }
    @Test
    fun `updateRole exempts self-spawn when the role moves projects`() = runTest {
        // The role currently lives in project 1 and moves to project 2 while keeping itself in its
        // spawn allow-list: the persisted (stale) membership of the target IS the role being written,
        // so the same-project check must exempt it (its row is updated with projectId 2 in the same
        // transaction).
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1.copy(projectId = 1L))
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            spawnableAgentRoleIds = setOf(1L),
            instructions = emptyList(),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { agentRoleDao.updateRole(match { it.projectId == 2L }) }
    }
    @Test
    fun `updateRole rejects a spawnable target outside the role's new project`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        // The target role stays in project 1 while the source moves to project 2 -> illegal.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(5L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 5L, projectId = 1L))
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            spawnableAgentRoleIds = setOf(5L),
            instructions = emptyList(),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        val error = assertIs<UpdateAgentRoleError.SpawnableRoleNotInProject>(result.leftOrNull())
        assertEquals(5L, error.roleId)
        assertEquals(2L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }
}
