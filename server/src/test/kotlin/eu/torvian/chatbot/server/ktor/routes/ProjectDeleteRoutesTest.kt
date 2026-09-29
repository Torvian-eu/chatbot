package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.ProjectResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.project.DeleteProjectResponse
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for the project deletion endpoint (`DELETE /api/v1/projects/{projectId}`).
 *
 * Covers JWT enforcement, the ownership collapse for a foreign project (404, no existence leak), and
 * the destructive success path: `200` with a [DeleteProjectResponse] naming the deleted member roles
 * and the removed/retained instruction rows, while the member roles are actually gone and a
 * surviving role still links the shared instruction.
 */
class ProjectDeleteRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var authToken: String

    /** Id of the instruction linked only by the deleted project's member role. */
    private var soleInstructionId: Long = 0L

    /** Id of the instruction shared by the member role and the surviving unassociated role. */
    private var sharedInstructionId: Long = 0L

    // The authenticated user (id 1) owns project 1 with one member role (10). Project 2 is owned by
    // another user (999) and must collapse to 404 for the requesting user.
    private val ownerUserId = 1L
    private val foreignUserId = 999L

    private val project = TestDefaults.project1
    private val foreignProject = TestDefaults.project2.copy(id = 2L)

    // The project's member role; a preset-less role keeps this suite free of preset seeding.
    private val memberRole = TestDefaults.agentRole1.copy(id = 10L, name = "Architect", projectId = project.id)

    // An unassociated role that shares one instruction row with the member role; it must survive.
    private val unassociatedRole = TestDefaults.agentRole2.copy(id = 11L, name = "Reviewer", projectId = null)

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        authHelper = TestAuthHelper(container)
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        app = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureProjectRoutes(this)
            }
        )

        testDataManager = container.get()
        instructionDao = container.get()
        instructionOwnershipDao = container.get()
        agentRoleInstructionDao = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.CHAT_GROUPS,
                Table.PROJECTS,
                Table.PROJECT_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS,
                Table.CHAT_SESSIONS
            )
        )

        // The authenticated user must exist before ownership rows reference it; the foreign owner must
        // exist as a row too or ownership FKs are rejected.
        authToken = authHelper.createUserAndGetToken()
        testDataManager.insertUser(TestDefaults.user2.copy(id = foreignUserId))

        testDataManager.insertProject(project)
        testDataManager.insertProject(foreignProject)
        testDataManager.insertAgentRole(memberRole)
        testDataManager.insertAgentRole(unassociatedRole)

        testDataManager.insertProjectOwnership(project.id, ownerUserId)
        testDataManager.insertProjectOwnership(foreignProject.id, foreignUserId)
        testDataManager.insertAgentRoleOwnership(memberRole.id, ownerUserId)
        testDataManager.insertAgentRoleOwnership(unassociatedRole.id, ownerUserId)

        // Instruction 1 is linked only by the member role (must be removed); instruction 2 is shared
        // with the unassociated role (must be retained).
        soleInstructionId = instructionDao
            .insertInstruction(type = "custom", name = "Sole", message = "Only the architect uses this.", custom = null)
            .id
        instructionOwnershipDao.setOwner(soleInstructionId, ownerUserId)
        sharedInstructionId = instructionDao
            .insertInstruction(type = "custom", name = "Shared", message = "Both roles use this.", custom = null)
            .id
        instructionOwnershipDao.setOwner(sharedInstructionId, ownerUserId)

        agentRoleInstructionDao.replaceInstructionsForRole(memberRole.id, listOf(soleInstructionId, sharedInstructionId))
        agentRoleInstructionDao.replaceInstructionsForRole(unassociatedRole.id, listOf(sharedInstructionId))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `DELETE without auth returns 401`() = app {
        val response = client.delete(href(ProjectResource.ById(projectId = project.id)))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `DELETE of a project owned by another user returns 404 without leaking ownership`() = app {
        val response = client.delete(href(ProjectResource.ById(projectId = foreignProject.id))) {
            authenticate(authToken)
        }

        // Foreign and nonexistent collapse to the same not-found outcome (no existence leak).
        assertEquals(HttpStatusCode.NotFound, response.status)
        val apiError = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, apiError.code)
    }

    @Test
    fun `DELETE returns 200 with the deletion impact`() = app {
        val response = client.delete(href(ProjectResource.ById(projectId = project.id))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<DeleteProjectResponse>()
        assertEquals(project.id, body.projectId)
        assertEquals(listOf(memberRole.id), body.deletedAgentRoleIds)
        assertEquals(listOf(soleInstructionId), body.deletedInstructionIds)
        assertEquals(listOf(sharedInstructionId), body.retainedInstructionIds)

        // The member role is gone; the unassociated role survived.
        assertNull(testDataManager.getAgentRole(memberRole.id), "the member role must be gone")
        assertNotNull(testDataManager.getAgentRole(unassociatedRole.id), "the unassociated role must survive")
    }
}
