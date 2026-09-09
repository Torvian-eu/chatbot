package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleDisabledDao
import eu.torvian.chatbot.server.data.dao.AgentRoleOwnershipDao
import eu.torvian.chatbot.server.data.dao.AgentRoleSpawnableRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleToolDao
import eu.torvian.chatbot.server.data.dao.ProjectAgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.ProjectOwnershipDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SessionProjectPair
import eu.torvian.chatbot.server.data.dao.SessionRolePair
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.project.ProjectError as ProjectDaoError
import eu.torvian.chatbot.server.service.core.error.project.CloneProjectError
import eu.torvian.chatbot.server.service.core.error.project.CreateProjectError
import eu.torvian.chatbot.server.service.core.error.project.DeleteProjectError
import eu.torvian.chatbot.server.service.core.error.project.ProjectError
import eu.torvian.chatbot.server.service.core.error.project.UpdateProjectError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProjectServiceImpl].
 *
 * This suite verifies the owner-scoped CRUD behavior, the per-owner name-uniqueness rule
 * (including self-exclusion on rename), the batch-loaded member role ids on DTOs, and the uniform
 * Legality-restoring project deletion: affected sessions are captured before the delete and their
 * roles are cleared after it, in the same transaction, without touching the roles themselves.
 */
class ProjectServiceImplTest {

    private lateinit var projectDao: ProjectDao
    private lateinit var projectOwnershipDao: ProjectOwnershipDao
    private lateinit var projectAgentRoleDao: ProjectAgentRoleDao
    private lateinit var agentRoleDao: AgentRoleDao
    private lateinit var agentRoleToolDao: AgentRoleToolDao
    private lateinit var agentRoleSpawnableRoleDao: AgentRoleSpawnableRoleDao
    private lateinit var agentRoleOwnershipDao: AgentRoleOwnershipDao
    private lateinit var agentRoleDisabledDao: AgentRoleDisabledDao
    private lateinit var sessionDao: SessionDao
    private lateinit var transactionScope: TransactionScope

    private lateinit var service: ProjectServiceImpl

    private val userId = 7L
    private val otherUserId = 8L

    @BeforeEach
    fun setUp() {
        projectDao = mockk()
        projectOwnershipDao = mockk()
        projectAgentRoleDao = mockk()
        agentRoleDao = mockk()
        agentRoleToolDao = mockk()
        agentRoleSpawnableRoleDao = mockk()
        agentRoleOwnershipDao = mockk()
        agentRoleDisabledDao = mockk()
        sessionDao = mockk()
        transactionScope = mockk()

        service = ProjectServiceImpl(
            projectDao = projectDao,
            projectOwnershipDao = projectOwnershipDao,
            projectAgentRoleDao = projectAgentRoleDao,
            agentRoleDao = agentRoleDao,
            agentRoleToolDao = agentRoleToolDao,
            agentRoleSpawnableRoleDao = agentRoleSpawnableRoleDao,
            agentRoleOwnershipDao = agentRoleOwnershipDao,
            agentRoleDisabledDao = agentRoleDisabledDao,
            sessionDao = sessionDao,
            transactionScope = transactionScope
        )

        // Default the legality-restoring reads/writes so focused tests control their own stubbing: no
        // session selects any project / role, and clearing is a no-op. Role ownership also defaults
        // to "nothing owned", so only tests that opt into memberships stub it (empty member sets are
        // skipped by the service without a DAO call).
        coEvery { sessionDao.getSessionIdsByProject(any()) } returns emptyList()
        coEvery { sessionDao.getSessionRolePairsForProject(any()) } returns emptyList()
        // The attach-direction sweep reads every session using any newly attached role; default it to
        // "no session uses any role" so focused tests only stub it when they verify the sweep.
        coEvery { sessionDao.getSessionProjectPairsForRoles(any()) } returns emptyList()
        coEvery { sessionDao.clearAgentRoleForSessions(any()) } returns Unit
        // The membership replacement is invoked unconditionally by create/update (a full replacement
        // of the member set); default it so focused tests only override when they verify arguments.
        coEvery { projectAgentRoleDao.replaceRolesForProject(any(), any()) } returns Unit
        coEvery { agentRoleDao.getRolesByIdsForUser(any(), any()) } returns emptyList()
        // Clone-path defaults: an empty member set and per-role relations, so focused clone tests
        // only stub the batch reads/writes they verify.
        coEvery { projectAgentRoleDao.getRoleIdsForProject(any()) } returns emptySet()
        coEvery { agentRoleToolDao.getToolsForRoles(any()) } returns emptyMap()
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(any()) } returns emptyMap()
        coEvery { agentRoleDisabledDao.getDisabledRoleIds(any(), any()) } returns emptySet()
        coEvery { agentRoleToolDao.replaceToolsForRole(any(), any()) } returns Unit
        coEvery { agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(any(), any()) } returns Unit
        coEvery { agentRoleOwnershipDao.setOwner(any(), any()) } returns Unit.right()
        coEvery { agentRoleDisabledDao.setRoleDisabled(any(), any(), any()) } returns Unit

        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            projectDao,
            projectOwnershipDao,
            projectAgentRoleDao,
            agentRoleDao,
            agentRoleToolDao,
            agentRoleSpawnableRoleDao,
            agentRoleOwnershipDao,
            agentRoleDisabledDao,
            sessionDao,
            transactionScope
        )
    }

    // --- getAllProjectsForUser ---

    @Test
    fun `getAllProjectsForUser batch-loads the member role ids and returns DTOs`() = runTest {
        val project1 = TestDefaults.project1
        val project2 = TestDefaults.project2
        coEvery { projectDao.getAllProjectsForUser(userId) } returns listOf(project1, project2)
        coEvery { projectAgentRoleDao.getRoleIdsForProjects(listOf(project1.id, project2.id)) } returns
            mapOf(project1.id to setOf(10L, 11L), project2.id to setOf(12L))

        val result = service.getAllProjectsForUser(userId)

        assertEquals(2, result.size)
        assertEquals(setOf(10L, 11L), result[0].agentRoleIds)
        assertEquals(setOf(12L), result[1].agentRoleIds)
        // A single batch call backs the whole list (no N+1 per project).
        coVerify(exactly = 1) { projectAgentRoleDao.getRoleIdsForProjects(listOf(project1.id, project2.id)) }
    }

    // --- getProjectById ---

    @Test
    fun `getProjectById returns the owned project with its member role ids`() = runTest {
        val project = TestDefaults.project1
        coEvery { projectDao.getProjectById(project.id) } returns project.right()
        coEvery { projectOwnershipDao.getOwner(project.id) } returns userId.right()
        coEvery { projectAgentRoleDao.getRoleIdsForProject(project.id) } returns setOf(5L, 6L)

        val result = service.getProjectById(userId, project.id)

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals(project.name, dto.name)
        assertEquals(setOf(5L, 6L), dto.agentRoleIds)
    }

    @Test
    fun `getProjectById collapses a foreign project to NotFound`() = runTest {
        val project = TestDefaults.project1
        coEvery { projectDao.getProjectById(project.id) } returns project.right()
        // User 8 owns it, not the requesting user 7.
        coEvery { projectOwnershipDao.getOwner(project.id) } returns otherUserId.right()

        val result = service.getProjectById(userId, project.id)

        val error = assertIs<ProjectError.NotFound>(result.leftOrNull())
        assertEquals(project.id, error.id)
    }

    @Test
    fun `getProjectById returns NotFound when the project does not exist`() = runTest {
        coEvery { projectDao.getProjectById(99L) } returns ProjectDaoError.NotFound(99L).left()

        val result = service.getProjectById(userId, 99L)

        assertIs<ProjectError.NotFound>(result.leftOrNull())
    }

    // --- createProject ---

    @Test
    fun `createProject validates, inserts and assigns ownership atomically`() = runTest {
        val request = CreateProjectRequest(name = "Acme Web App", description = "Flagship app")
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns Unit.right()

        val result = service.createProject(userId, request)

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals(request.name, dto.name)
        assertTrue(dto.agentRoleIds.isEmpty(), "a request without memberships yields an empty set")
        // The default empty membership is still persisted as a full replacement, atomically with the
        // row and ownership.
        coVerify(exactly = 1) {
            projectAgentRoleDao.replaceRolesForProject(TestDefaults.project1.id, emptySet())
        }
        coVerify(exactly = 1) { projectDao.projectNameExistsForUser(userId, request.name) }
        coVerify(exactly = 1) { projectDao.insertProject(request.name, request.description) }
        coVerify(exactly = 1) { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) }
    }

    @Test
    fun `createProject persists the requested role memberships and returns them`() = runTest {
        val request = CreateProjectRequest(
            name = "Acme Web App",
            description = "Flagship app",
            agentRoleIds = setOf(10L, 11L)
        )
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        // Both requested roles are owned by the requesting user.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L), TestDefaults.agentRole2.copy(id = 11L))
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns Unit.right()

        val result = service.createProject(userId, request)

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals(setOf(10L, 11L), dto.agentRoleIds, "the DTO echoes the persisted membership")
        coVerify(exactly = 1) { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) }
        coVerify(exactly = 1) {
            projectAgentRoleDao.replaceRolesForProject(TestDefaults.project1.id, setOf(10L, 11L))
        }
    }

    @Test
    fun `createProject rejects a missing or foreign role id`() = runTest {
        val request = CreateProjectRequest(
            name = "Acme Web App",
            agentRoleIds = setOf(10L, 11L)
        )
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        // Only role 11 is owned: role 10 is missing or belongs to another user — the same error shape.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns
            listOf(TestDefaults.agentRole2.copy(id = 11L))

        val result = service.createProject(userId, request)

        val error = assertIs<CreateProjectError.RoleNotFound>(result.leftOrNull())
        assertEquals(10L, error.roleId)
        // No write may happen when a member role is not owned.
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
        coVerify(exactly = 0) { projectOwnershipDao.setOwner(any(), any()) }
        coVerify(exactly = 0) { projectAgentRoleDao.replaceRolesForProject(any(), any()) }
    }

    @Test
    fun `createProject rejects a role bound to another project`() = runTest {
        val request = CreateProjectRequest(
            name = "Acme Web App",
            agentRoleIds = setOf(10L)
        )
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        // Role 10 is owned but already belongs to project 5: attaching it here would silently move it.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L, projectId = 5L))

        val result = service.createProject(userId, request)

        val error = assertIs<CreateProjectError.RoleInAnotherProject>(result.leftOrNull())
        assertEquals(10L, error.roleId)
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
        coVerify(exactly = 0) { projectAgentRoleDao.replaceRolesForProject(any(), any()) }
    }

    @Test
    fun `createProject allows unassociated roles`() = runTest {
        // An unassociated role (projectId null) is attachable to a brand-new project.
        val request = CreateProjectRequest(name = "Acme Web App", agentRoleIds = setOf(10L))
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L))
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns Unit.right()

        val result = service.createProject(userId, request)

        assertTrue(result.isRight())
    }

    @Test
    fun `createProject rejects a duplicate name for the same owner`() = runTest {
        val request = CreateProjectRequest(name = "Acme Web App")
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns true

        val result = service.createProject(userId, request)

        val error = assertIs<CreateProjectError.NameAlreadyExists>(result.leftOrNull())
        assertEquals(request.name, error.name)
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
        coVerify(exactly = 0) { projectOwnershipDao.setOwner(any(), any()) }
    }

    @Test
    fun `createProject rejects a blank name`() = runTest {
        val result = service.createProject(userId, CreateProjectRequest(name = "   "))

        assertIs<CreateProjectError.InvalidName>(result.leftOrNull())
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
    }

    @Test
    fun `createProject maps an ownership insertion failure to OwnerInsertFailed`() = runTest {
        val request = CreateProjectRequest(name = "Acme Web App")
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns
            SetOwnerError.ForeignKeyViolation(
                TestDefaults.project1.id.toString(),
                userId
            ).left()

        val result = service.createProject(userId, request)

        assertIs<CreateProjectError.OwnerInsertFailed>(result.leftOrNull())
    }

    // --- updateProject ---

    @Test
    fun `updateProject renames an owned project and replaces its role memberships`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = "Renamed", description = "Updated", agentRoleIds = setOf(7L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        // The requested name differs; no other owned project uses it.
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { projectDao.updateProject(any()) } returns Unit.right()
        // The requested member role is owned by the requesting user.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(7L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 7L))

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals(request.name, dto.name)
        assertEquals(setOf(7L), dto.agentRoleIds, "the DTO echoes the replacement set")
        coVerify(exactly = 1) { projectDao.projectNameExistsForUser(userId, request.name) }
        coVerify(exactly = 1) {
            projectAgentRoleDao.replaceRolesForProject(existing.id, setOf(7L))
        }
    }

    @Test
    fun `updateProject sweeps sessions whose role left the membership`() = runTest {
        val existing = TestDefaults.project1
        // Role 2 is detached; role 1 stays; session 103 has no role at all.
        val request = UpdateProjectRequest(name = existing.name, description = "Updated", agentRoleIds = setOf(1L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        coEvery { projectDao.updateProject(any()) } returns Unit.right()
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1)
        coEvery { sessionDao.getSessionRolePairsForProject(existing.id) } returns listOf(
            SessionRolePair(sessionId = 101L, agentRoleId = 1L),  // stays: role still a member
            SessionRolePair(sessionId = 102L, agentRoleId = 2L),  // leaves: (project, role) now illegal
            SessionRolePair(sessionId = 103L, agentRoleId = null) // stays: no role to clear
        )

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.getSessionRolePairsForProject(existing.id) }
        // Only the session whose role left the membership is cleared, in the same transaction.
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(listOf(102L)) }
    }

    @Test
    fun `createProject clears an attached role from project-less and other-project sessions`() = runTest {
        // legality regression (project-side attach direction): attaching unassociated roles that sessions
        // already use must clear those sessions' roles in the same transaction as the membership
        // write — "role became associated while the session has no project" (or a different one).
        val request = CreateProjectRequest(name = "Acme Web App", agentRoleIds = setOf(10L, 11L))
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns
            listOf(
                TestDefaults.agentRole1.copy(id = 10L),
                TestDefaults.agentRole2.copy(id = 11L)
            )
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns Unit.right()
        // Session 101 uses role 10 with no project; session 102 uses role 11 under project 5: both
        // pairs become illegal once the roles are attached to the new project (id 1).
        coEvery { sessionDao.getSessionProjectPairsForRoles(listOf(10L, 11L)) } returns listOf(
            SessionProjectPair(sessionId = 101L, projectId = null),
            SessionProjectPair(sessionId = 102L, projectId = 5L)
        )

        val result = service.createProject(userId, request)

        assertTrue(result.isRight())
        // The affected sessions are cleared after the membership write, in the same transaction.
        coVerifyOrder {
            projectAgentRoleDao.replaceRolesForProject(TestDefaults.project1.id, setOf(10L, 11L))
            sessionDao.getSessionProjectPairsForRoles(listOf(10L, 11L))
            sessionDao.clearAgentRoleForSessions(listOf(101L, 102L))
        }
    }

    @Test
    fun `createProject leaves no illegal sessions when no session uses an attached role`() = runTest {
        val request = CreateProjectRequest(name = "Acme Web App", agentRoleIds = setOf(10L))
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L))
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns Unit.right()

        val result = service.createProject(userId, request)

        assertTrue(result.isRight())
        // No session uses the attached role, so the sweep clears nothing (DAO clear is a no-op).
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(emptyList()) }
    }

    @Test
    fun `createProject does not sweep when a role is rejected before the write`() = runTest {
        val request = CreateProjectRequest(name = "Acme Web App", agentRoleIds = setOf(10L))
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        // Role 10 is owned but already belongs to project 5: rejected before any membership write.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L, projectId = 5L))

        val result = service.createProject(userId, request)

        assertIs<CreateProjectError.RoleInAnotherProject>(result.leftOrNull())
        // The attach sweep must not run when the membership write was rejected.
        coVerify(exactly = 0) { sessionDao.getSessionProjectPairsForRoles(any()) }
        coVerify(exactly = 0) { sessionDao.clearAgentRoleForSessions(any()) }
    }

    // --- createProject (legality attach sweep) ---

    @Test
    fun `createProject keeps sessions already selecting the new project`() = runTest {
        val request = CreateProjectRequest(name = "Acme Web App", agentRoleIds = setOf(10L))
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns false
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L))
        coEvery { projectDao.insertProject(request.name, request.description) } returns TestDefaults.project1
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project1.id, userId) } returns Unit.right()
        // A session already selecting the new project (id 1) with the attached role stays untouched.
        coEvery { sessionDao.getSessionProjectPairsForRoles(listOf(10L)) } returns
            listOf(SessionProjectPair(sessionId = 101L, projectId = TestDefaults.project1.id))

        val result = service.createProject(userId, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(emptyList()) }
    }

    // --- updateProject (legality attach sweep) ---

    @Test
    fun `updateProject clears an attached role from a project-less session`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, agentRoleIds = setOf(1L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        coEvery { projectDao.updateProject(any()) } returns Unit.right()
        // The attached role is currently unassociated; no session of this project loses a role.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1)
        coEvery { sessionDao.getSessionRolePairsForProject(existing.id) } returns emptyList()
        // Session 101 uses role 1 with no project: after the attach the pair is illegal and the role
        // must be cleared in the same transaction.
        coEvery { sessionDao.getSessionProjectPairsForRoles(listOf(1L)) } returns
            listOf(SessionProjectPair(sessionId = 101L, projectId = null))

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) {
            sessionDao.getSessionProjectPairsForRoles(listOf(1L))
            sessionDao.clearAgentRoleForSessions(listOf(101L))
        }
        coVerifyOrder {
            projectAgentRoleDao.replaceRolesForProject(existing.id, setOf(1L))
            sessionDao.getSessionProjectPairsForRoles(listOf(1L))
            sessionDao.clearAgentRoleForSessions(listOf(101L))
        }
    }

    @Test
    fun `updateProject keeps kept-role sessions that already share the project`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, agentRoleIds = setOf(1L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        coEvery { projectDao.updateProject(any()) } returns Unit.right()
        // Role 1 is ALREADY a member of THIS project (keep case, same-scope attach allowed).
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1.copy(projectId = existing.id))
        // Keep/clear matrix: a session selecting this project with the kept role stays (projectId
        // matches), while a session selecting another project with the kept role is drift-cleared.
        coEvery { sessionDao.getSessionRolePairsForProject(existing.id) } returns listOf(
            SessionRolePair(sessionId = 101L, agentRoleId = 1L)
        )
        coEvery { sessionDao.getSessionProjectPairsForRoles(listOf(1L)) } returns listOf(
            SessionProjectPair(sessionId = 101L, projectId = existing.id),
            SessionProjectPair(sessionId = 102L, projectId = 5L)
        )

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        // Only the session outside the project is cleared; the in-project session keeps its role.
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(listOf(102L)) }
    }

    @Test
    fun `updateProject does not sweep when a role is rejected before the write`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, agentRoleIds = setOf(10L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        // Role 10 is owned but belongs to project 5: rejected before any membership write.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L, projectId = 5L))

        val result = service.updateProject(userId, existing.id, request)

        assertIs<UpdateProjectError.RoleInAnotherProject>(result.leftOrNull())
        // Neither sweep may run when the membership write was rejected.
        coVerify(exactly = 0) { sessionDao.getSessionProjectPairsForRoles(any()) }
        coVerify(exactly = 0) { sessionDao.clearAgentRoleForSessions(any()) }
    }

    @Test
    fun `updateProject clears nothing when the membership is unchanged`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, description = "Updated", agentRoleIds = setOf(1L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        coEvery { projectDao.updateProject(any()) } returns Unit.right()
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1)
        // Both sessions keep a role that stays in the membership: the sweep finds no illegal pair.
        coEvery { sessionDao.getSessionRolePairsForProject(existing.id) } returns listOf(
            SessionRolePair(sessionId = 101L, agentRoleId = 1L),
            SessionRolePair(sessionId = 102L, agentRoleId = 1L)
        )

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(emptyList()) }
    }

    @Test
    fun `updateProject rejects a missing or foreign role id`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, agentRoleIds = setOf(10L, 11L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        // Only role 11 is owned: role 10 is missing or belongs to another user — the same error shape.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns
            listOf(TestDefaults.agentRole2.copy(id = 11L))

        val result = service.updateProject(userId, existing.id, request)

        val error = assertIs<UpdateProjectError.RoleNotFound>(result.leftOrNull())
        assertEquals(10L, error.roleId)
        coVerify(exactly = 0) { projectDao.updateProject(any()) }
        coVerify(exactly = 0) { projectAgentRoleDao.replaceRolesForProject(any(), any()) }
    }

    @Test
    fun `updateProject rejects a role bound to another project`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, agentRoleIds = setOf(10L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        // Role 10 is owned but belongs to project 5, not this one: adding it here would steal it.
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns
            listOf(TestDefaults.agentRole1.copy(id = 10L, projectId = 5L))

        val result = service.updateProject(userId, existing.id, request)

        val error = assertIs<UpdateProjectError.RoleInAnotherProject>(result.leftOrNull())
        assertEquals(10L, error.roleId)
        coVerify(exactly = 0) { projectDao.updateProject(any()) }
        coVerify(exactly = 0) { projectAgentRoleDao.replaceRolesForProject(any(), any()) }
    }

    @Test
    fun `updateProject keeps roles already in the project`() = runTest {
        // A role already bound to THIS project stays attachable (the guard only rejects roles bound
        // to a DIFFERENT project).
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, agentRoleIds = setOf(1L))
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        coEvery { projectDao.updateProject(any()) } returns Unit.right()
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(1L)) } returns
            listOf(TestDefaults.agentRole1.copy(projectId = existing.id))

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        coVerify(exactly = 1) {
            projectAgentRoleDao.replaceRolesForProject(existing.id, setOf(1L))
        }
    }

    @Test
    fun `updateProject allows keeping the current name (self-excluded uniqueness)`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = existing.name, description = "Updated")
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        // The uniqueness check must be skipped for the unchanged name (the row still holds old name).
        coEvery { projectDao.updateProject(any()) } returns Unit.right()

        val result = service.updateProject(userId, existing.id, request)

        assertTrue(result.isRight())
        // No duplicate-name check runs when the name is unchanged.
        coVerify(exactly = 0) { projectDao.projectNameExistsForUser(any(), any()) }
    }

    @Test
    fun `updateProject rejects renaming to another owned project's name`() = runTest {
        val existing = TestDefaults.project1
        val request = UpdateProjectRequest(name = "Taken Name")
        coEvery { projectDao.getProjectById(existing.id) } returns existing.right()
        coEvery { projectOwnershipDao.getOwner(existing.id) } returns userId.right()
        // The requesting user already owns a DIFFERENT project with that name.
        coEvery { projectDao.projectNameExistsForUser(userId, request.name) } returns true

        val result = service.updateProject(userId, existing.id, request)

        val error = assertIs<UpdateProjectError.NameAlreadyExists>(result.leftOrNull())
        assertEquals(request.name, error.name)
        coVerify(exactly = 0) { projectDao.updateProject(any()) }
    }

    @Test
    fun `updateProject returns NotFound for a foreign or missing project`() = runTest {
        coEvery { projectDao.getProjectById(5L) } returns ProjectDaoError.NotFound(5L).left()

        val result = service.updateProject(userId, 5L, UpdateProjectRequest(name = "X"))

        assertIs<UpdateProjectError.NotFound>(result.leftOrNull())
    }

    // --- cloneProject ---

    @Test
    fun `cloneProject returns NotFound for a nonexistent source`() = runTest {
        coEvery { projectDao.getProjectById(5L) } returns ProjectDaoError.NotFound(5L).left()

        val result = service.cloneProject(userId, 5L, CloneProjectRequest(name = "Copy"))

        assertIs<CloneProjectError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
    }

    @Test
    fun `cloneProject collapses a foreign source to NotFound without leaking ownership`() = runTest {
        val source = TestDefaults.project1
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        // User 8 owns the source, not the requesting user 7: same not-found shape as a missing project.
        coEvery { projectOwnershipDao.getOwner(source.id) } returns otherUserId.right()

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy"))

        val error = assertIs<CloneProjectError.NotFound>(result.leftOrNull())
        assertEquals(source.id, error.id)
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
    }

    @Test
    fun `cloneProject rejects a duplicate name for the same owner`() = runTest {
        val source = TestDefaults.project1
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        // The requesting user already owns a DIFFERENT project with that name.
        coEvery { projectDao.projectNameExistsForUser(userId, "Taken") } returns true

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Taken"))

        val error = assertIs<CloneProjectError.NameAlreadyExists>(result.leftOrNull())
        assertEquals("Taken", error.name)
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
    }

    @Test
    fun `cloneProject rejects a blank name`() = runTest {
        val source = TestDefaults.project1
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "   "))

        assertIs<CloneProjectError.InvalidName>(result.leftOrNull())
        coVerify(exactly = 0) { projectDao.insertProject(any(), any()) }
    }

    @Test
    fun `cloneProject copies the source description when none is provided`() = runTest {
        val source = TestDefaults.project1
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy of Acme Web App") } returns false
        // The omitted description defaults to the source's description (Q4-A).
        coEvery { projectDao.insertProject("Copy of Acme Web App", source.description) } returns
            TestDefaults.project2.copy(id = 20L, name = "Copy of Acme Web App")
        coEvery { projectOwnershipDao.setOwner(20L, userId) } returns Unit.right()

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy of Acme Web App"))

        assertTrue(result.isRight())
        coVerify(exactly = 1) { projectDao.insertProject("Copy of Acme Web App", source.description) }
        coVerify(exactly = 1) { projectOwnershipDao.setOwner(20L, userId) }
    }

    @Test
    fun `cloneProject applies an explicit description override`() = runTest {
        val source = TestDefaults.project1
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy of Acme Web App") } returns false
        coEvery { projectDao.insertProject("Copy of Acme Web App", "Fresh description") } returns
            TestDefaults.project2.copy(id = 20L, name = "Copy of Acme Web App")
        coEvery { projectOwnershipDao.setOwner(20L, userId) } returns Unit.right()

        val result = service.cloneProject(
            userId,
            source.id,
            CloneProjectRequest(name = "Copy of Acme Web App", description = "Fresh description")
        )

        assertTrue(result.isRight())
        coVerify(exactly = 1) { projectDao.insertProject("Copy of Acme Web App", "Fresh description") }
        coVerify(exactly = 1) { projectOwnershipDao.setOwner(20L, userId) }
    }

    @Test
    fun `cloneProject maps a project-ownership insertion failure to OwnerInsertFailed`() = runTest {
        val source = TestDefaults.project1
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy") } returns false
        coEvery { projectDao.insertProject("Copy", source.description) } returns TestDefaults.project2.copy(name = "Copy")
        coEvery { projectOwnershipDao.setOwner(TestDefaults.project2.id, userId) } returns
            SetOwnerError.ForeignKeyViolation(TestDefaults.project2.id.toString(), userId).left()

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy"))

        assertIs<CloneProjectError.OwnerInsertFailed>(result.leftOrNull())
    }

    @Test
    fun `cloneProject deep-copies member roles with configuration, tools and remapped spawn ids`() = runTest {
        val source = TestDefaults.project1
        val sourceRoleA = TestDefaults.agentRole1.copy(
            id = 10L,
            name = "Architect",
            displayName = "Senior Architect",
            description = "role description",
            modelId = 1L,
            modelSettingsId = 2L,
            projectId = source.id
        )
        val sourceRoleB = TestDefaults.agentRole2.copy(id = 11L, name = "Reviewer", projectId = source.id)
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy of Acme Web App") } returns false
        coEvery { projectAgentRoleDao.getRoleIdsForProject(source.id) } returns setOf(10L, 11L)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns listOf(sourceRoleA, sourceRoleB)
        // Role 10 carries a tool set and a spawn grant to role 11, plus a stale target 99 that is NOT
        // part of the cloned set — it must be dropped, not copied verbatim.
        coEvery { agentRoleToolDao.getToolsForRoles(listOf(10L, 11L)) } returns mapOf(10L to setOf(100L, 101L))
        coEvery { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(listOf(10L, 11L)) } returns
            mapOf(10L to setOf(11L, 99L))
        coEvery { projectDao.insertProject("Copy of Acme Web App", source.description) } returns
            TestDefaults.project2.copy(id = 20L, name = "Copy of Acme Web App")
        coEvery { projectOwnershipDao.setOwner(20L, userId) } returns Unit.right()
        // New ids 30/31 are assigned in source iteration order (insertRole call order).
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            TestDefaults.agentRole1.copy(id = 30L),
            TestDefaults.agentRole2.copy(id = 31L)
        )

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy of Acme Web App"))

        assertTrue(result.isRight())
        val dto = result.getOrNull()
        assertNotNull(dto)
        assertEquals(20L, dto.id)
        assertEquals(setOf(30L, 31L), dto.agentRoleIds, "the DTO carries the NEW role ids, not the source's")
        // Per-role configuration is copied field-for-field, with the membership pointing at the clone.
        coVerify(exactly = 1) {
            agentRoleDao.insertRole(
                name = "Architect",
                displayName = "Senior Architect",
                description = "role description",
                modelId = 1L,
                modelSettingsId = 2L,
                instructionsJson = sourceRoleA.instructionsJson,
                projectId = 20L
            )
        }
        coVerify(exactly = 1) { agentRoleDao.insertRole("Reviewer", "Code Reviewer", sourceRoleB.description, 2L, 2L, sourceRoleB.instructionsJson, 20L) }
        // The tool set is copied as-is.
        coVerify(exactly = 1) { agentRoleToolDao.replaceToolsForRole(30L, setOf(100L, 101L)) }
        // The spawn allow-list is remapped old-id -> new-id (11 -> 31), and the stale target 99 is
        // dropped rather than copied verbatim (it would be an illegal cross-project grant).
        coVerify(exactly = 1) { agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(30L, setOf(31L)) }
        // Every cloned role gets its own ownership row.
        coVerify(exactly = 1) { agentRoleOwnershipDao.setOwner(30L, userId) }
        coVerify(exactly = 1) { agentRoleOwnershipDao.setOwner(31L, userId) }
        // The relations are loaded batch-wise (one query per relation, no N+1).
        coVerify(exactly = 1) { agentRoleToolDao.getToolsForRoles(listOf(10L, 11L)) }
        coVerify(exactly = 1) { agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(listOf(10L, 11L)) }
        // Source untouched: no project or role row of the source is mutated by the clone.
        coVerify(exactly = 0) { projectDao.updateProject(any()) }
        coVerify(exactly = 0) { projectDao.deleteProject(any()) }
        coVerify(exactly = 0) { agentRoleDao.updateRole(any()) }
        coVerify(exactly = 0) { agentRoleDao.deleteRole(any()) }
    }

    @Test
    fun `cloneProject deep-copies the per-user disabled markers of the source roles`() = runTest {
        val source = TestDefaults.project1
        val sourceRoleA = TestDefaults.agentRole1.copy(id = 10L, projectId = source.id)
        val sourceRoleB = TestDefaults.agentRole2.copy(id = 11L, projectId = source.id)
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy") } returns false
        coEvery { projectAgentRoleDao.getRoleIdsForProject(source.id) } returns setOf(10L, 11L)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns listOf(sourceRoleA, sourceRoleB)
        coEvery { projectDao.insertProject("Copy", source.description) } returns TestDefaults.project2.copy(id = 20L, name = "Copy")
        coEvery { projectOwnershipDao.setOwner(20L, userId) } returns Unit.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            TestDefaults.agentRole1.copy(id = 30L),
            TestDefaults.agentRole2.copy(id = 31L)
        )
        // Role 10 carries a disabled marker for the user; role 11 is enabled.
        coEvery { agentRoleDisabledDao.getDisabledRoleIds(userId, listOf(10L, 11L)) } returns setOf(10L)

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy"))

        assertTrue(result.isRight())
        // Deliberate deviation from create-role semantics (which never inserts disabled rows): only
        // the clone of the disabled source role receives a disabled marker; the enabled source role
        // yields an enabled clone (no row written).
        coVerify(exactly = 1) { agentRoleDisabledDao.setRoleDisabled(userId, 30L, true) }
        coVerify(exactly = 0) { agentRoleDisabledDao.setRoleDisabled(userId, 31L, true) }
    }

    @Test
    fun `cloneProject maps a role-ownership failure to OwnerInsertFailed and returns no partial clone`() = runTest {
        val source = TestDefaults.project1
        val sourceRoleA = TestDefaults.agentRole1.copy(id = 10L, projectId = source.id)
        val sourceRoleB = TestDefaults.agentRole2.copy(id = 11L, projectId = source.id)
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy") } returns false
        coEvery { projectAgentRoleDao.getRoleIdsForProject(source.id) } returns setOf(10L, 11L)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L, 11L)) } returns listOf(sourceRoleA, sourceRoleB)
        coEvery { projectDao.insertProject("Copy", source.description) } returns TestDefaults.project2.copy(id = 20L, name = "Copy")
        coEvery { projectOwnershipDao.setOwner(20L, userId) } returns Unit.right()
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            TestDefaults.agentRole1.copy(id = 30L),
            TestDefaults.agentRole2.copy(id = 31L)
        )
        // The ownership write of the LAST cloned role fails after the project row, both role rows and
        // the first role's relations were already written. The typed error is returned (the real
        // TransactionScope rolls the whole clone back, leaving no project and no orphaned roles).
        coEvery { agentRoleOwnershipDao.setOwner(30L, userId) } returns Unit.right()
        coEvery { agentRoleOwnershipDao.setOwner(31L, userId) } returns
            SetOwnerError.ForeignKeyViolation("31", userId).left()

        val result = service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy"))

        assertIs<CloneProjectError.OwnerInsertFailed>(result.leftOrNull())
    }

    @Test
    fun `cloneProject propagates an unhandled failure during role insertion`() = runTest {
        val source = TestDefaults.project1
        val sourceRole = TestDefaults.agentRole1.copy(id = 10L, projectId = source.id)
        coEvery { projectDao.getProjectById(source.id) } returns source.right()
        coEvery { projectOwnershipDao.getOwner(source.id) } returns userId.right()
        coEvery { projectDao.projectNameExistsForUser(userId, "Copy") } returns false
        coEvery { projectAgentRoleDao.getRoleIdsForProject(source.id) } returns setOf(10L)
        coEvery { agentRoleDao.getRolesByIdsForUser(userId, listOf(10L)) } returns listOf(sourceRole)
        coEvery { projectDao.insertProject("Copy", source.description) } returns TestDefaults.project2.copy(id = 20L, name = "Copy")
        coEvery { projectOwnershipDao.setOwner(20L, userId) } returns Unit.right()
        // A forced failure while copying a role, after the new project row was inserted: the exception
        // escapes the service so the real TransactionScope can roll the whole clone back (no new
        // project, no orphaned role rows).
        coEvery { agentRoleDao.insertRole(any(), any(), any(), any(), any(), any(), any()) } throws
            RuntimeException("injected role insertion failure")

        assertFailsWith<RuntimeException> {
            service.cloneProject(userId, source.id, CloneProjectRequest(name = "Copy"))
        }
    }

    // --- deleteProject (legality cleanup) ---

    @Test
    fun `deleteProject captures affected sessions, deletes and clears their roles uniformly`() = runTest {
        val project = TestDefaults.project1
        coEvery { projectDao.getProjectById(project.id) } returns project.right()
        coEvery { projectOwnershipDao.getOwner(project.id) } returns userId.right()
        // Two sessions currently select the project; both must have their roles cleared.
        coEvery { sessionDao.getSessionIdsByProject(project.id) } returns listOf(101L, 102L)
        coEvery { projectDao.deleteProject(project.id) } returns Unit.right()

        val result = service.deleteProject(userId, project.id)

        assertTrue(result.isRight())
        // Order matters: the affected set is captured before the delete (after it, the sessions would
        // no longer be addressable by project), then the roles are cleared uniformly after the delete
        // inside the same transaction.
        coVerifyOrder {
            sessionDao.getSessionIdsByProject(project.id)
            projectDao.deleteProject(project.id)
            sessionDao.clearAgentRoleForSessions(listOf(101L, 102L))
        }
    }

    @Test
    fun `deleteProject clears roles even when the affected set is empty`() = runTest {
        val project = TestDefaults.project1
        coEvery { projectDao.getProjectById(project.id) } returns project.right()
        coEvery { projectOwnershipDao.getOwner(project.id) } returns userId.right()
        coEvery { sessionDao.getSessionIdsByProject(project.id) } returns emptyList()
        // The clear is a no-op on an empty list by DAO contract; the service still invokes it so the
        // uniform-clear semantics stay deterministic regardless of the affected set size.
        coEvery { projectDao.deleteProject(project.id) } returns Unit.right()

        val result = service.deleteProject(userId, project.id)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.clearAgentRoleForSessions(emptyList()) }
    }

    @Test
    fun `deleteProject never deletes roles`() = runTest {
        val project = TestDefaults.project1
        coEvery { projectDao.getProjectById(project.id) } returns project.right()
        coEvery { projectOwnershipDao.getOwner(project.id) } returns userId.right()
        coEvery { projectDao.deleteProject(project.id) } returns Unit.right()

        val result = service.deleteProject(userId, project.id)

        assertTrue(result.isRight())
        // The service surface has no role-deletion capability; only the project row and the session
        // role clears may be invoked (role rows survive via cascade semantics in the DB).
        coVerify(exactly = 0) { projectAgentRoleDao.replaceRolesForProject(any(), any()) }
    }

    @Test
    fun `deleteProject returns NotFound for a foreign or missing project`() = runTest {
        coEvery { projectDao.getProjectById(9L) } returns ProjectDaoError.NotFound(9L).left()

        val result = service.deleteProject(userId, 9L)

        assertIs<DeleteProjectError.NotFound>(result.leftOrNull())
        coVerify(exactly = 0) { sessionDao.clearAgentRoleForSessions(any()) }
    }
}