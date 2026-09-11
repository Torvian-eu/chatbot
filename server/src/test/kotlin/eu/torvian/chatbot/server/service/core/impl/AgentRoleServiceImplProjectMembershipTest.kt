package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.data.dao.*
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

/** Tests for [AgentRoleServiceImpl] project membership persistence and the role-update session legality sweep. */
class AgentRoleServiceImplProjectMembershipTest : AgentRoleServiceImplTestBase() {

    @Test
    fun `createRole persists projectId atomically with the role row and returns it on the DTO`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The requesting user owns the referenced project.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.project1)
        coEvery {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), any())
        } returns TestDefaults.agentRole1
        coEvery { agentRoleOwnershipDao.setOwner(TestDefaults.agentRole1.id, userId) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit

        val result = service.createRole(userId, validRequest().copy(projectId = 1L))

        assertTrue(result.isRight())
        assertEquals(1L, result.getOrNull()!!.projectId)
        // The single membership column is written together with the row, atomically with the tools
        // and ownership writes.
        coVerify(exactly = 1) {
            agentRoleDao.insertRole(any(), any(), any(), any(), any(), eq(1L))
        }
    }
    @Test
    fun `createRole rejects a foreign project id as not found`() = runTest {
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user owns no project at all, so every attached id is missing/foreign.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(99L)) } returns emptyList()

        val result = service.createRole(userId, validRequest().copy(projectId = 99L))

        val error = assertIs<CreateAgentRoleError.ProjectNotFound>(result.leftOrNull())
        assertEquals(99L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any()) }
    }
    @Test
    fun `updateRole persists the new projectId and returns it on the DTO`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The role moves from project 1 to project 2; the membership is rewritten with the row.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
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

        assertTrue(result.isRight())
        assertEquals(2L, result.getOrNull()!!.projectId)
        coVerify(exactly = 1) { agentRoleDao.updateRole(match { it.projectId == 2L }) }
    }
    @Test
    fun `updateRole rejects a foreign project id as not found`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The user owns no project matching id 99.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(99L)) } returns emptyList()

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = emptyList(),
            projectId = 99L
        )
        val result = service.updateRole(userId, 1L, request)

        val error = assertIs<UpdateAgentRoleError.ProjectNotFound>(result.leftOrNull())
        assertEquals(99L, error.projectId)
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
    }
    @Test
    fun `updateRole clears the role on sessions whose project differs from the new projectId`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        // The role currently belongs to project 1; the update moves it to project 2.
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(2L)) } returns listOf(TestDefaults.project2)
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        // Session 10 selected the role's OLD project 1 -> now illegal; session 11 selected the new
        // project 2 -> legal; session 12 has no project but the role is now project-bound -> illegal.
        coEvery { sessionDao.getSessionProjectPairsForRole(1L) } returns listOf(
            SessionProjectPair(sessionId = 10L, projectId = 1L),
            SessionProjectPair(sessionId = 11L, projectId = 2L),
            SessionProjectPair(sessionId = 12L, projectId = null)
        )

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = emptyList(),
            projectId = 2L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // Only the sessions whose project differs from the new single project id are cleared.
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(listOf(10L, 12L)) }
    }
    @Test
    fun `updateRole keeps sessions when the new projectId still covers them`() = runTest {
        coEvery { agentRoleDao.getRoleById(1L) } returns TestDefaults.agentRole1.copy(projectId = 1L).right()
        coEvery { agentRoleOwnershipDao.getOwner(1L) } returns userId.right()
        coEvery { settingsDao.getSettingsById(1L) } returns chatSettings.right()
        coEvery { projectDao.getProjectsByIdsForUser(userId, listOf(1L)) } returns listOf(TestDefaults.project1)
        coEvery { agentRoleDao.getRoleNameScopesForUser(any(), any()) } returns emptyList()
        coEvery { agentRoleDao.updateRole(any()) } returns Unit.right()
        coEvery { agentRoleToolDao.getToolsForRole(TestDefaults.agentRole1.id) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { sessionDao.getSessionProjectPairsForRole(1L) } returns listOf(
            SessionProjectPair(sessionId = 11L, projectId = 1L)
        )

        val request = UpdateAgentRoleRequest(
            name = "Renamed",
            description = "Designs systems",
            modelPresetId = validPreset.id,
            toolIds = emptySet(),
            instructions = emptyList(),
            projectId = 1L
        )
        val result = service.updateRole(userId, 1L, request)

        assertTrue(result.isRight())
        // Session 11 occupies exactly the role's project: no sweep clears anything.
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(emptyList()) }
    }
}
