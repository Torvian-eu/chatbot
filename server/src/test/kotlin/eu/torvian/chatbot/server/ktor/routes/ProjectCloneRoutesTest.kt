package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.ProjectResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.AgentRoleDisabledDao
import eu.torvian.chatbot.server.data.dao.AgentRoleSpawnableRoleDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
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
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the project clone endpoint (`POST /api/v1/projects/{projectId}/clone`).
 *
 * Covers JWT enforcement, the ownership collapse for a foreign source (404, no existence leak), and
 * the full success path: 201 with the cloned [ProjectDto], deep-copied role rows (configuration
 * verbatim, tool set as-is, spawn allow-list remapped to the cloned role ids, per-user disabled
 * markers) bound to the clone, while the source project, its roles and their disabled state stay
 * untouched.
 */
class ProjectCloneRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var spawnableRoleDao: AgentRoleSpawnableRoleDao
    private lateinit var disabledDao: AgentRoleDisabledDao
    private lateinit var authToken: String

    // The authenticated user (id 1) owns project 1 with two member roles: role 10 (disabled for the
    // user, carries a tool and a spawn grant to role 11) and role 11 (enabled). Project 2 is owned by
    // another user (999) and must collapse to 404 for the requesting user.
    private val ownerUserId = 1L
    private val foreignUserId = 999L

    private val sourceProject = TestDefaults.project1
    private val foreignProject = TestDefaults.project2.copy(id = 2L)
    private val sourceRoleA = TestDefaults.agentRole1.copy(
        id = 10L,
        name = "Architect",
        displayName = "Senior Architect",
        description = "The architecture role",
        // Model/settings references are null so the fixtures need no llm_models/model_settings seeding
        // (the deep-copy of those ids is covered by the service unit tests).
        modelId = null,
        modelSettingsId = null,
        instructionsJson = """[{"type":"role","name":"Role","message":"You are the architect."}]""",
        projectId = sourceProject.id
    )
    private val sourceRoleB = TestDefaults.agentRole2.copy(
        id = 11L,
        name = "Reviewer",
        // Model/settings references are null so the fixtures need no llm_models/model_settings seeding
        // (see sourceRoleA).
        modelId = null,
        modelSettingsId = null,
        projectId = sourceProject.id
    )

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
        spawnableRoleDao = container.get()
        disabledDao = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.PROJECTS,
                Table.PROJECT_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.AGENT_ROLE_TOOLS,
                Table.AGENT_ROLE_SPAWNABLE_ROLES,
                Table.AGENT_ROLE_DISABLED,
                Table.TOOL_DEFINITIONS
            )
        )

        // The authenticated user must exist before ownership rows reference it; the foreign owner must
        // exist as a row too or ownership FKs are rejected.
        authToken = authHelper.createUserAndGetToken()
        testDataManager.insertUser(TestDefaults.user2.copy(id = foreignUserId))

        testDataManager.insertProject(sourceProject)
        testDataManager.insertProject(foreignProject)
        testDataManager.insertAgentRole(sourceRoleA)
        testDataManager.insertAgentRole(sourceRoleB)

        testDataManager.insertProjectOwnership(sourceProject.id, ownerUserId)
        testDataManager.insertProjectOwnership(foreignProject.id, foreignUserId)
        testDataManager.insertAgentRoleOwnership(sourceRoleA.id, ownerUserId)
        testDataManager.insertAgentRoleOwnership(sourceRoleB.id, ownerUserId)

        // Seed the source role relations the clone must copy: role 10 carries a tool and spawns
        // role 11, and is disabled for the owner.
        val tool = container.get<ToolDefinitionDao>().insertToolDefinition(
            name = "web_search",
            description = "Search the web",
            type = ToolType.BUILTIN_WORKER,
            config = buildJsonObject {},
            inputSchema = buildJsonObject {},
            outputSchema = null,
            isEnabled = true
        )
        testDataManager.insertAgentRoleTool(sourceRoleA.id, tool.id)
        spawnableRoleDao.replaceSpawnableRolesForRole(sourceRoleA.id, setOf(sourceRoleB.id))
        testDataManager.insertAgentRoleDisabled(sourceRoleA.id, ownerUserId)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `POST clone without auth returns 401`() = app {
        val response =
            client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = sourceProject.id)))) {
                contentType(ContentType.Application.Json)
                setBody(CloneProjectRequest(name = "Copy of Acme Web App"))
            }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST clone of a project owned by another user returns 404 without leaking ownership`() = app {
        val response =
            client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = foreignProject.id)))) {
                authenticate(authToken)
                contentType(ContentType.Application.Json)
                setBody(CloneProjectRequest(name = "Copy of Foreign"))
            }

        // Foreign and nonexistent collapse to the same not-found outcome (no existence leak).
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST clone deep-copies the member roles and returns 201 with the cloned project`() = app {
        // Act: clone without a description — the clone must default to the source's description.
        val response =
            client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = sourceProject.id)))) {
                authenticate(authToken)
                contentType(ContentType.Application.Json)
                setBody(CloneProjectRequest(name = "Copy of Acme Web App"))
            }

        // Assert the wire response.
        assertEquals(HttpStatusCode.Created, response.status)
        val cloned = response.body<ProjectDto>()
        assertEquals("Copy of Acme Web App", cloned.name)
        assertEquals(sourceProject.description, cloned.description, "omitted description defaults to the source's")
        assertEquals(2, cloned.agentRoleIds.size, "both member roles are deep-copied")
        assertTrue(
            cloned.agentRoleIds.none { it == sourceRoleA.id || it == sourceRoleB.id },
            "the clone carries NEW role ids"
        )

        // The cloned role rows exist with the source's configuration verbatim, bound to the clone.
        val clonedRoles = cloned.agentRoleIds.map { assertNotNull(testDataManager.getAgentRole(it)) }
        assertEquals(setOf("Architect", "Reviewer"), clonedRoles.map { it.name }.toSet())
        val clonedA = clonedRoles.single { it.name == "Architect" }
        val clonedB = clonedRoles.single { it.name == "Reviewer" }
        assertEquals(sourceRoleA.displayName, clonedA.displayName)
        assertEquals(sourceRoleA.description, clonedA.description)
        assertEquals(sourceRoleA.modelId, clonedA.modelId)
        assertEquals(sourceRoleA.modelSettingsId, clonedA.modelSettingsId)
        assertEquals(sourceRoleA.instructionsJson, clonedA.instructionsJson)
        assertTrue(clonedRoles.all { it.projectId == cloned.id }, "every cloned role is bound to the clone")

        // The spawn allow-list is remapped to the cloned role ids (11 -> clonedB), never to the source.
        assertEquals(setOf(clonedB.id), spawnableRoleDao.getSpawnableRoleIdsForRole(clonedA.id))

        // The per-user disabled marker is copied: disabled role 10 yields a disabled clone, enabled
        // role 11 yields an enabled clone.
        assertTrue(
            disabledDao.isRoleDisabled(ownerUserId, clonedA.id),
            "cloned role of a disabled source role is disabled"
        )
        assertEquals(false, disabledDao.isRoleDisabled(ownerUserId, clonedB.id))

        // Source untouched: project, membership, spawn allow-list and disabled state all intact.
        val sourceAfter = assertNotNull(testDataManager.getProject(sourceProject.id))
        assertEquals(sourceProject.description, sourceAfter.description)
        val sourceARow = assertNotNull(testDataManager.getAgentRole(sourceRoleA.id))
        assertEquals(sourceProject.id, sourceARow.projectId)
        assertEquals(setOf(sourceRoleB.id), spawnableRoleDao.getSpawnableRoleIdsForRole(sourceRoleA.id))
        assertTrue(disabledDao.isRoleDisabled(ownerUserId, sourceRoleA.id))
    }

    @Test
    fun `POST clone applies an explicit description override`() = app {
        val response =
            client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = sourceProject.id)))) {
                authenticate(authToken)
                contentType(ContentType.Application.Json)
                setBody(CloneProjectRequest(name = "Copy with new description", description = "Refreshed copy"))
            }

        assertEquals(HttpStatusCode.Created, response.status)
        val cloned = response.body<ProjectDto>()
        assertEquals("Refreshed copy", cloned.description)
    }

    @Test
    fun `POST clone with a duplicate name returns 409 AlreadyExists`() = app {
        // A second clone under the same name collides with the first clone.
        client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = sourceProject.id)))) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(CloneProjectRequest(name = "Copy of Acme Web App"))
        }

        val response =
            client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = sourceProject.id)))) {
                authenticate(authToken)
                contentType(ContentType.Application.Json)
                setBody(CloneProjectRequest(name = "Copy of Acme Web App"))
            }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `POST clone with a blank name returns 400 InvalidArgument`() = app {
        val response =
            client.post(href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = sourceProject.id)))) {
                authenticate(authToken)
                contentType(ContentType.Application.Json)
                setBody(CloneProjectRequest(name = "   "))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}