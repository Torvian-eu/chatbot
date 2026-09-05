package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleDisabledRequest
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
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
import kotlin.test.assertTrue

/**
 * Integration tests for the agent-role disabled-state toggle endpoint
 * (`PUT /api/v1/agent-roles/{roleId}/disabled`).
 *
 * The endpoint is ownership-checked and per-user: a `true` body records a `(user, role)` disabled
 * marker (returning the updated DTO), `false` removes it, and a role owned by another user collapses
 * to 404 like every other role operation.
 */
class AgentRoleRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var agentRoleTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var authToken: String

    // Test data: role 1 owned by the authenticated user, role 2 owned by another user (999). Model
    // and settings references are null so the fixtures need no llm_models/model_settings seeding.
    private val ownedRole = TestDefaults.agentRole1.copy(
        id = 1L,
        name = "My Role",
        modelId = null,
        modelSettingsId = null,
        instructionsJson = "[]"
    )
    private val foreignRole = TestDefaults.agentRole2.copy(
        id = 2L,
        name = "Foreign Role",
        modelId = null,
        modelSettingsId = null,
        instructionsJson = "[]"
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        authHelper = TestAuthHelper(container)
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        agentRoleTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureAgentRoleRoutes(this)
            }
        )

        testDataManager = container.get()
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS
            )
        )
        testDataManager.setup(
            dataSet = TestDataSet(
                agentRoles = listOf(ownedRole, foreignRole)
            )
        )
        // The authenticated user must exist before ownership rows reference it. The foreign owner must
        // exist as a row too or the ownership FK is rejected.
        authToken = authHelper.createUserAndGetToken()
        testDataManager.insertUser(TestDefaults.user2)
        testDataManager.insertAgentRoleOwnership(ownedRole.id, authHelper.defaultTestUser.id)
        testDataManager.insertAgentRoleOwnership(foreignRole.id, TestDefaults.user2.id)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `PUT agent role disabled should disable a role for the requesting user`() = agentRoleTestApplication {
        // Act
        val response = client.put(
            href(AgentRoleResource.ById.Disabled(parent = AgentRoleResource.ById(roleId = ownedRole.id)))
        ) {
            contentType(ContentType.Application.Json)
            setBody(UpdateAgentRoleDisabledRequest(disabled = true))
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val dto = response.body<AgentRoleDto>()
        assertEquals(ownedRole.id, dto.id)
        assertTrue(dto.disabled)

        // The persisted state is visible through the read endpoint (per-user DTO carries the flag).
        val readResponse = client.get(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
            authenticate(authToken)
        }
        assertEquals(HttpStatusCode.OK, readResponse.status)
        assertTrue(readResponse.body<AgentRoleDto>().disabled)
    }

    @Test
    fun `PUT agent role disabled should re-enable a role for the requesting user`() = agentRoleTestApplication {
        // Arrange: disable first, then re-enable via the same endpoint.
        client.put(href(AgentRoleResource.ById.Disabled(parent = AgentRoleResource.ById(roleId = ownedRole.id)))) {
            contentType(ContentType.Application.Json)
            setBody(UpdateAgentRoleDisabledRequest(disabled = true))
            authenticate(authToken)
        }

        // Act
        val response = client.put(
            href(AgentRoleResource.ById.Disabled(parent = AgentRoleResource.ById(roleId = ownedRole.id)))
        ) {
            contentType(ContentType.Application.Json)
            setBody(UpdateAgentRoleDisabledRequest(disabled = false))
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val dto = response.body<AgentRoleDto>()
        assertEquals(false, dto.disabled)
    }

    @Test
    fun `PUT agent role disabled should return 404 for a role owned by another user`() = agentRoleTestApplication {
        // Act: the authenticated user does not own role 2; no existence leak, no write.
        val response = client.put(
            href(AgentRoleResource.ById.Disabled(parent = AgentRoleResource.ById(roleId = foreignRole.id)))
        ) {
            contentType(ContentType.Application.Json)
            setBody(UpdateAgentRoleDisabledRequest(disabled = true))
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        // The foreign role stayed enabled for the requesting user (no side-table row was written).
        val readResponse = client.get(href(AgentRoleResource.ById(roleId = foreignRole.id))) {
            authenticate(authToken)
        }
        // GET by id is ownership-checked too, so the foreign role is invisible to this user: 404
        // confirms the toggle did not leak the role's existence or mutate its disabled state.
        assertEquals(HttpStatusCode.NotFound, readResponse.status)
    }

    @Test
    fun `PUT agent role disabled without auth returns 401`() = agentRoleTestApplication {
        val response = client.put(
            href(AgentRoleResource.ById.Disabled(parent = AgentRoleResource.ById(roleId = ownedRole.id)))
        ) {
            contentType(ContentType.Application.Json)
            setBody(UpdateAgentRoleDisabledRequest(disabled = true))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}