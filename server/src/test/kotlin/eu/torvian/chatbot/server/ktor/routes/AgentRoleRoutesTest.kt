package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleDisabledRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the agent-role routes (`/api/v1/agent-roles`).
 *
 * Covers the per-user disabled-state toggle endpoint (ownership-checked: a `true` body records a
 * `(user, role)` disabled marker and returns the updated DTO, `false` removes it, and a role owned by
 * another user collapses to 404), and the model-preset configuration surface: attaching a preset
 * persists only the reference while the response reports the preset-derived model/settings ids, a
 * preset-less role stays valid, and an unknown preset is rejected without writing.
 */
class AgentRoleRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var agentRoleTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var authToken: String

    // Test data: role 1 owned by the authenticated user, role 2 owned by another user (999). Both are
    // preset-less so the fixtures need no model/settings/model-preset seeding.
    private val ownedRole = TestDefaults.agentRole1.copy(
        id = 1L,
        name = "My Role",
        modelPresetId = null,
        instructionsJson = "[]"
    )
    private val foreignRole = TestDefaults.agentRole2.copy(
        id = 2L,
        name = "Foreign Role",
        modelPresetId = null,
        instructionsJson = "[]"
    )

    /**
     * The LLM chain the preset references, seeded so the route round-trip can assert the derived ids
     * (AC-5/AC-10) end-to-end instead of only the preset reference.
     */
    private val ownedPreset = TestDefaults.modelPreset1.copy(
        id = 1L,
        modelId = TestDefaults.llmModel1.id,
        modelSettingsId = TestDefaults.modelSettings1.id
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
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.MODEL_SETTINGS,
                Table.MODEL_PRESETS,
                Table.MODEL_PRESET_OWNERS,
                Table.USERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                // The role-update path runs the Session Legality sweep, which reads chat_sessions.
                Table.CHAT_SESSIONS
            )
        )
        testDataManager.setup(
            dataSet = TestDataSet(
                llmProviders = listOf(TestDefaults.llmProvider1),
                llmModels = listOf(TestDefaults.llmModel1),
                modelSettings = listOf(TestDefaults.modelSettings1),
                modelPresets = listOf(ownedPreset),
                agentRoles = listOf(ownedRole, foreignRole)
            )
        )
        // The authenticated user must exist before ownership rows reference it. The foreign owner must
        // exist as a row too or the ownership FK is rejected.
        authToken = authHelper.createUserAndGetToken()
        testDataManager.insertUser(TestDefaults.user2)
        testDataManager.insertAgentRoleOwnership(ownedRole.id, authHelper.defaultTestUser.id)
        testDataManager.insertAgentRoleOwnership(foreignRole.id, TestDefaults.user2.id)
        testDataManager.insertModelPresetOwnership(ownedPreset.id, authHelper.defaultTestUser.id)
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

    @Test
    fun `PUT agent role attaches a preset and returns the derived model and settings ids`() =
        agentRoleTestApplication {
            // Arrange: the owned role starts preset-less; the update attaches the seeded preset.
            val request = UpdateAgentRoleRequest(
                name = ownedRole.name,
                description = ownedRole.description,
                modelPresetId = ownedPreset.id
            )

            // Act
            val response = client.put(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
                contentType(ContentType.Application.Json)
                setBody(request)
                authenticate(authToken)
            }

            // Assert: only the preset reference is persisted, while the response reports the preset's
            // model and settings as derived, read-only convenience values.
            assertEquals(HttpStatusCode.OK, response.status)
            val dto = response.body<AgentRoleDto>()
            assertEquals(ownedPreset.id, dto.modelPresetId)
            assertEquals(ownedPreset.modelId, dto.modelId)
            assertEquals(ownedPreset.modelSettingsId, dto.modelSettingsId)

            val persisted = assertNotNull(testDataManager.getAgentRole(ownedRole.id))
            assertEquals(ownedPreset.id, persisted.modelPresetId)

            // The same values come back from the read endpoint (single-role preset resolution).
            val read = client.get(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
                authenticate(authToken)
            }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(ownedPreset.id, read.body<AgentRoleDto>().modelPresetId)
        }

    @Test
    fun `POST agent role without a preset stays valid and preset-less`() = agentRoleTestApplication {
        // A preset-less role is legal (U-30/FR-7): it is created and simply flagged as non-sendable.
        val response = client.post(href(AgentRoleResource())) {
            contentType(ContentType.Application.Json)
            setBody(CreateAgentRoleRequest(name = "Preset-less role"))
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val dto = response.body<AgentRoleDto>()
        assertEquals(null, dto.modelPresetId)
        assertEquals(null, dto.modelId)
        assertEquals(null, dto.modelSettingsId)
    }

    @Test
    fun `PUT agent role with an unknown preset returns 400 and persists nothing`() = agentRoleTestApplication {
        val response = client.put(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
            contentType(ContentType.Application.Json)
            setBody(UpdateAgentRoleRequest(name = ownedRole.name, modelPresetId = 999L))
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        // The reference was rejected before any write, so the role is unchanged (still preset-less).
        assertEquals(null, assertNotNull(testDataManager.getAgentRole(ownedRole.id)).modelPresetId)
    }
}