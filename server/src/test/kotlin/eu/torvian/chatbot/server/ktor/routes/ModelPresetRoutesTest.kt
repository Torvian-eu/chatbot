package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.ModelPresetResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.CustomApplicationTestBuilder
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
 * Integration tests for the model-preset endpoints (`/api/v1/model-presets`).
 *
 * Exercises the full CRUD path against real DAOs on SQLite with foreign keys enforced: creation
 * returns 201 with the server-managed timestamps, updates are full replacements that advance
 * `updated_at`, deletes are non-destructive for agent roles, and the error surface maps inaccessible
 * or mismatched references to 400, duplicate names to 409 (per owner, so another user may reuse a
 * name) and a foreign preset to 404 without leaking ownership.
 */
class ModelPresetRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var modelPresetTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var authToken: String
    private lateinit var otherUserToken: String

    private val model = TestDefaults.llmModel1
    private val settings = TestDefaults.modelSettings1

    /**
     * A second model/settings pair owned by the OTHER user (both owner tables have a resource-keyed
     * primary key, so a model/settings profile has exactly one owner): used to prove per-owner name
     * reuse and that another user's resources are not readable.
     */
    private val otherModel = TestDefaults.llmModel2
    private val otherSettings = TestDefaults.modelSettings2

    /**
     * A settings profile of the SECOND model, owned by the requesting user: lets the mismatch check be
     * asserted without an accessibility failure standing in for it.
     */
    private val mismatchSettings = TestDefaults.modelSettings1.copy(id = 77L, modelId = otherModel.id)

    /**
     * The agent role bound to a preset, used to prove that deleting the preset detaches (rather than
     * deletes) the role.
     */
    private val boundRole = TestDefaults.agentRole1.copy(id = 1L, modelPresetId = null, instructionsJson = "[]")

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        authHelper = TestAuthHelper(container)
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        modelPresetTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureModelPresetRoutes(this)
            }
        )

        testDataManager = container.get()
        testDataManager.setup(
            dataSet = TestDataSet(
                llmProviders = listOf(TestDefaults.llmProvider1, TestDefaults.llmProvider2),
                llmModels = listOf(model, otherModel),
                modelSettings = listOf(settings, otherSettings, mismatchSettings),
                agentRoles = listOf(boundRole)
            )
        )
        testDataManager.createTables(
            setOf(
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.LLM_MODEL_OWNERS,
                // The accessibility reads (used to validate the preset's references) join the access and
                // group-membership tables, so those must exist even though no grant row is seeded.
                Table.LLM_MODEL_ACCESS,
                Table.MODEL_SETTINGS,
                Table.MODEL_SETTINGS_OWNERS,
                Table.MODEL_SETTINGS_ACCESS,
                Table.MODEL_PRESETS,
                Table.MODEL_PRESET_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.USERS,
                Table.USER_SESSIONS,
                Table.USER_GROUPS,
                Table.USER_GROUP_MEMBERSHIPS
            )
        )

        // Two users, both able to READ the model and the settings profile their presets reference (the
        // references must be accessible, not merely existing). The second user is inserted before their
        // session token so the session's user foreign key resolves.
        authToken = authHelper.createUserAndGetToken(TestDefaults.user1, TestDefaults.userSession1)
        testDataManager.insertUser(TestDefaults.user2)
        otherUserToken = authHelper.createSessionAndGetToken(
            userId = TestDefaults.user2.id,
            sessionId = 2L
        )
        testDataManager.insertModelOwnership(model.id, TestDefaults.user1.id)
        testDataManager.insertSettingsOwnership(settings.id, TestDefaults.user1.id)
        testDataManager.insertSettingsOwnership(mismatchSettings.id, TestDefaults.user1.id)
        testDataManager.insertModelOwnership(otherModel.id, TestDefaults.user2.id)
        testDataManager.insertSettingsOwnership(otherSettings.id, TestDefaults.user2.id)
        testDataManager.insertAgentRoleOwnership(boundRole.id, TestDefaults.user1.id)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `GET model presets returns an empty list initially`() = modelPresetTestApplication {
        val response = client.get(href(ModelPresetResource())) { authenticate(authToken) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(emptyList(), response.body<List<ModelPresetDto>>())
    }

    @Test
    fun `POST model preset creates the preset and GET returns it with both timestamps`() =
        modelPresetTestApplication {
            val response = client.post(href(ModelPresetResource())) {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateModelPresetRequest(
                        name = "smart_model",
                        displayName = "Smart model",
                        description = "Bundles the smart model",
                        modelId = model.id,
                        modelSettingsId = settings.id
                    )
                )
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val created = assertNotNull(response.body<ModelPresetDto>())
            assertEquals("smart_model", created.name)
            assertEquals("Smart model", created.displayName)
            assertEquals(model.id, created.modelId)
            assertEquals(settings.id, created.modelSettingsId)
            // created_at is set on create (OQ-2: both timestamps are exposed on the DTO).
            assertEquals(created.createdAt, created.updatedAt)

            val list = client.get(href(ModelPresetResource())) { authenticate(authToken) }
            assertEquals(1, list.body<List<ModelPresetDto>>().size)
            assertEquals(
                created.id,
                client.get(href(ModelPresetResource.ById(presetId = created.id))) {
                    authenticate(authToken)
                }.body<ModelPresetDto>().id
            )
        }

    @Test
    fun `PUT model preset replaces the configuration and advances updated_at`() = modelPresetTestApplication {
        val created = createPreset("smart_model")

        // Re-point the preset's settings reference only; the name is preserved (no rename collision).
        val response = client.put(href(ModelPresetResource.ById(presetId = created.id))) {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateModelPresetRequest(
                    name = "smart_model",
                    displayName = null,
                    description = "Re-pointed",
                    modelId = model.id,
                    modelSettingsId = null
                )
            )
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = assertNotNull(response.body<ModelPresetDto>())
        assertEquals("Re-pointed", updated.description)
        assertEquals(null, updated.displayName)
        assertEquals(null, updated.modelSettingsId, "a null reference clears it")
        assertEquals(created.createdAt, updated.createdAt, "created_at is never rewritten")
        assertTrue(updated.updatedAt >= created.updatedAt, "updated_at must not go backwards")
    }

    @Test
    fun `DELETE model preset detaches the bound role and keeps the role row`() = modelPresetTestApplication {
        val preset = createPreset("smart_model")
        // Bind the role to the preset (the role's configuration is only the reference).
        bindRoleToPreset(boundRole.id, preset.id)

        val response = client.delete(href(ModelPresetResource.ById(presetId = preset.id))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        // The role survives with a nulled reference: it is non-sendable, not deleted or edited.
        val storedRole = assertNotNull(testDataManager.getAgentRole(boundRole.id))
        assertEquals(null, storedRole.modelPresetId)
        assertEquals(boundRole.name, storedRole.name)
    }

    @Test
    fun `POST model preset with an inaccessible model returns 400`() = modelPresetTestApplication {
        // Model 999 does not exist for this user (and never existed at all): the same 400 shape covers
        // missing and unreadable references, so no existence leak occurs.
        val response = client.post(href(ModelPresetResource())) {
            contentType(ContentType.Application.Json)
            setBody(CreateModelPresetRequest(name = "broken", modelId = 999L))
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, listPresets().size, "nothing must be persisted on a rejected write")
    }

    @Test
    fun `POST model preset with a settings profile of another model returns 400`() = modelPresetTestApplication {
        val response = client.post(href(ModelPresetResource())) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateModelPresetRequest(
                    name = "mismatched",
                    modelId = model.id,
                    modelSettingsId = mismatchSettings.id
                )
            )
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, listPresets().size)
    }

    @Test
    fun `POST model preset with a duplicate name returns 409 and another user may reuse the name`() =
        modelPresetTestApplication {
            createPreset("smart_model")

            val duplicate = client.post(href(ModelPresetResource())) {
                contentType(ContentType.Application.Json)
                setBody(CreateModelPresetRequest(name = "smart_model"))
                authenticate(authToken)
            }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)

            // Name uniqueness is per owner, so the other user's identical name is accepted (their
            // preset references their own model/settings pair).
            val otherUser = client.post(href(ModelPresetResource())) {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateModelPresetRequest(
                        name = "smart_model",
                        modelId = otherModel.id,
                        modelSettingsId = otherSettings.id
                    )
                )
                authenticate(otherUserToken)
            }
            assertEquals(HttpStatusCode.Created, otherUser.status)
        }

    @Test
    fun `GET model preset owned by another user returns 404`() = modelPresetTestApplication {
        val preset = createPreset("smart_model")

        val response = client.get(href(ModelPresetResource.ById(presetId = preset.id))) {
            authenticate(otherUserToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `model preset endpoints without auth return 401`() = modelPresetTestApplication {
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get(href(ModelPresetResource())).status
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post(href(ModelPresetResource())) {
                contentType(ContentType.Application.Json)
                setBody(CreateModelPresetRequest(name = "smart_model"))
            }.status
        )
    }

    /**
     * Creates a preset through the REST endpoint and returns its DTO.
     *
     * @param name The preset name to create.
     * @return The created preset as returned by the endpoint.
     */
    private suspend fun CustomApplicationTestBuilder.createPreset(name: String): ModelPresetDto {
        val response = client.post(href(ModelPresetResource())) {
            contentType(ContentType.Application.Json)
            setBody(CreateModelPresetRequest(name = name, modelId = model.id, modelSettingsId = settings.id))
            authenticate(authToken)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return assertNotNull(response.body<ModelPresetDto>())
    }

    /**
     * Lists the authenticated user's presets through the REST endpoint.
     *
     * @return The listed presets.
     */
    private suspend fun CustomApplicationTestBuilder.listPresets(): List<ModelPresetDto> =
        client.get(href(ModelPresetResource())) { authenticate(authToken) }.body()

    /**
     * Points the seeded agent role at the given preset by updating the role row through the real DAO
     * (the role endpoints are not part of this suite).
     *
     * @param roleId The role to bind.
     * @param presetId The preset id to store on the role.
     */
    private suspend fun bindRoleToPreset(roleId: Long, presetId: Long) {
        val roleDao: eu.torvian.chatbot.server.data.dao.AgentRoleDao = container.get()
        val stored = assertNotNull(testDataManager.getAgentRole(roleId))
        assertTrue(roleDao.updateRole(stored.copy(modelPresetId = presetId)).isRight())
    }
}
