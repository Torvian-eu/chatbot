package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.InstructionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleDisabledRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
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
import io.ktor.http.content.TextContent
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
 * another user collapses to 404), the DELETE endpoint (204 on success, 404 collapsing foreign and
 * nonexistent roles, with sole-linked instruction rows swept and shared rows kept), and the
 * model-preset configuration surface: attaching a preset
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
        modelPresetId = null
    )
    private val foreignRole = TestDefaults.agentRole2.copy(
        id = 2L,
        name = "Foreign Role",
        modelPresetId = null
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
                // The delete-cleanup end-to-end case reads the library listing.
                apiRoutesKtor.configureInstructionRoutes(this)
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
                // Role reads and writes resolve the role's shareable instructions through these.
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS,
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

    @Test
    fun `POST agent role materializes all three instruction spec variants in request order`() =
        agentRoleTestApplication {
            val instructionDao: InstructionDao = container.get()
            val instructionOwnershipDao: InstructionOwnershipDao = container.get()
            val userId = authHelper.defaultTestUser.id
            val linkedId = instructionDao
                .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Style", message = "Old", custom = null)
                .id
            instructionOwnershipDao.setOwner(linkedId, userId)
            val updatedId = instructionDao
                .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Old", custom = null)
                .id
            instructionOwnershipDao.setOwner(updatedId, userId)
            val request = CreateAgentRoleRequest(
                name = "writer",
                instructionSpecs = listOf(
                    InstructionSlot.Create(
                        CreateInstructionRequest(
                            type = AgentInstructionTypes.ROLE,
                            name = "Role",
                            message = "You are a writer."
                        )
                    ),
                    InstructionSlot.Link(linkedId),
                    InstructionSlot.Update(
                        UpdateInstructionRequest(
                            id = updatedId,
                            type = AgentInstructionTypes.CUSTOM,
                            name = "Tone",
                            message = "New"
                        )
                    )
                )
            )

            val response = client.post(href(AgentRoleResource())) {
                contentType(ContentType.Application.Json)
                setBody(request)
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.Created, response.status)
            val dto = response.body<AgentRoleDto>()
            // The created row, the linked row and the rewritten row appear in request order.
            assertEquals(listOf("Role", "Style", "Tone"), dto.instructions.map { it.name })
            assertEquals(linkedId, dto.instructions[1].id)
            assertEquals(updatedId, dto.instructions[2].id)
            // The update spec replaced its target's content in place.
            assertEquals("New", instructionDao.getInstructionById(updatedId).getOrNull()?.message)
        }

    @Test
    fun `PUT agent role with a link spec leaves the linked row's content untouched`() = agentRoleTestApplication {
        val instructionDao: InstructionDao = container.get()
        val instructionOwnershipDao: InstructionOwnershipDao = container.get()
        val userId = authHelper.defaultTestUser.id
        val linkedId = instructionDao
            .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Style", message = "Untouched", custom = null)
            .id
        instructionOwnershipDao.setOwner(linkedId, userId)
        val request = UpdateAgentRoleRequest(
            name = ownedRole.name,
            instructionSpecs = listOf(
                InstructionSlot.Create(
                    CreateInstructionRequest(
                        type = AgentInstructionTypes.ROLE,
                        name = "Role",
                        message = "You are a writer."
                    )
                ),
                InstructionSlot.Link(linkedId)
            )
        )

        val response = client.put(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
            contentType(ContentType.Application.Json)
            setBody(request)
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // Exactly one row was created and the referenced row kept its content.
        assertEquals(2, instructionDao.getAllInstructionsForUser(userId).size)
        val linked = instructionDao.getInstructionById(linkedId).getOrNull()
        assertEquals("Untouched", linked?.message)
        assertEquals("Style", linked?.name)
    }

    @Test
    fun `PUT agent role with an empty spec list clears the role's links`() = agentRoleTestApplication {
        val instructionDao: InstructionDao = container.get()
        val instructionOwnershipDao: InstructionOwnershipDao = container.get()
        val agentRoleInstructionDao: AgentRoleInstructionDao = container.get()
        val userId = authHelper.defaultTestUser.id
        val linkedId = instructionDao
            .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Style", message = "x", custom = null)
            .id
        instructionOwnershipDao.setOwner(linkedId, userId)
        agentRoleInstructionDao.replaceInstructionsForRole(ownedRole.id, listOf(linkedId))

        val response = client.put(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
            contentType(ContentType.Application.Json)
            // Absent instructionSpecs means "no links": a full replacement clears the previous set.
            setBody(UpdateAgentRoleRequest(name = ownedRole.name))
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))[ownedRole.id].isNullOrEmpty())
    }

    @Test
    fun `POST agent role carrying the removed instructionIds key is rejected and nothing is written`() =
        agentRoleTestApplication {
            val agentRoleDao: AgentRoleDao = container.get()

            val response = client.post(href(AgentRoleResource())) {
                contentType(ContentType.Application.Json)
                setBody(TextContent("""{"name":"Dup","instructionIds":[]}""", ContentType.Application.Json))
                authenticate(authToken)
            }

            // Strict decoding rejects the stale key regardless of its value.
            assertTrue(response.status.value in 400..499, "expected 4xx but got ${response.status}")
            assertTrue(agentRoleDao.getAllRolesForUser(authHelper.defaultTestUser.id).none { it.name == "Dup" })
        }

    @Test
    fun `POST agent role carrying the legacy instructions key is rejected and nothing is written`() =
        agentRoleTestApplication {
            val agentRoleDao: AgentRoleDao = container.get()
            val agentRoleInstructionDao: AgentRoleInstructionDao = container.get()

            val response = client.post(href(AgentRoleResource())) {
                contentType(ContentType.Application.Json)
                setBody(TextContent("""{"name":"Dup","instructions":[]}""", ContentType.Application.Json))
                authenticate(authToken)
            }

            assertTrue(response.status.value in 400..499, "expected 4xx but got ${response.status}")
            assertTrue(agentRoleDao.getAllRolesForUser(authHelper.defaultTestUser.id).none { it.name == "Dup" })
            assertTrue(agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))[ownedRole.id].isNullOrEmpty())
        }

    @Test
    fun `PUT agent role carrying the legacy instructions key is rejected and keeps the existing links`() =
        agentRoleTestApplication {
            val instructionDao: InstructionDao = container.get()
            val instructionOwnershipDao: InstructionOwnershipDao = container.get()
            val agentRoleInstructionDao: AgentRoleInstructionDao = container.get()
            val userId = authHelper.defaultTestUser.id
            val linkedId = instructionDao
                .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Style", message = "x", custom = null)
                .id
            instructionOwnershipDao.setOwner(linkedId, userId)
            agentRoleInstructionDao.replaceInstructionsForRole(ownedRole.id, listOf(linkedId))

            val response = client.put(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
                contentType(ContentType.Application.Json)
                setBody(TextContent("""{"name":"My Role","instructions":[]}""", ContentType.Application.Json))
                authenticate(authToken)
            }

            // Strict decoding rejects the stale key before the write runs, so the update never reaches
            // the link replacement and the role keeps its previous link set.
            assertTrue(response.status.value in 400..499, "expected 4xx but got ${response.status}")
            assertEquals(
                listOf(linkedId),
                agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))[ownedRole.id]?.map { it.instructionId }
            )
        }

    /** Verifies the wire contract of a successful delete: 204 with the role row gone. */
    @Test
    fun `DELETE agent role returns 204 and removes the role`() = agentRoleTestApplication {
        val response = client.delete(href(AgentRoleResource.ById(roleId = ownedRole.id))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(null, testDataManager.getAgentRole(ownedRole.id))
    }

    /** Verifies foreign and nonexistent roles both answer 404 with nothing deleted. */
    @Test
    fun `DELETE unknown or foreign agent role returns 404 and removes nothing`() = agentRoleTestApplication {
        val foreignResponse = client.delete(href(AgentRoleResource.ById(roleId = foreignRole.id))) {
            authenticate(authToken)
        }
        val unknownResponse = client.delete(href(AgentRoleResource.ById(roleId = 999L))) {
            authenticate(authToken)
        }

        // Foreign and nonexistent roles collapse to the same answer, and neither is deleted.
        assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
        assertEquals(HttpStatusCode.NotFound, unknownResponse.status)
        assertNotNull(testDataManager.getAgentRole(foreignRole.id))
    }

    /**
     * Verifies the sweep end-to-end through the wire: the sole-linked row vanishes from the library
     * listing while the shared row survives with its updated usage.
     */
    @Test
    fun `DELETE agent role removes now-unreferenced rows from the library listing`() = agentRoleTestApplication {
        val instructionDao: InstructionDao = container.get()
        val instructionOwnershipDao: InstructionOwnershipDao = container.get()
        val agentRoleInstructionDao: AgentRoleInstructionDao = container.get()
        val userId = authHelper.defaultTestUser.id
        // The shared row survives the deletion because the owned role keeps linking it.
        val sharedId = instructionDao
            .insertInstruction(type = AgentInstructionTypes.CUSTOM, name = "Shared", message = "x", custom = null)
            .id
        instructionOwnershipDao.setOwner(sharedId, userId)
        agentRoleInstructionDao.replaceInstructionsForRole(ownedRole.id, listOf(sharedId))
        val created = client.post(href(AgentRoleResource())) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateAgentRoleRequest(
                    name = "writer",
                    instructionSpecs = listOf(
                        InstructionSlot.Create(
                            CreateInstructionRequest(
                                type = AgentInstructionTypes.ROLE,
                                name = "Role",
                                message = "You are a writer."
                            )
                        ),
                        InstructionSlot.Link(sharedId)
                    )
                )
            )
            authenticate(authToken)
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val doomedRoleId = created.body<AgentRoleDto>().id

        val response = client.delete(href(AgentRoleResource.ById(roleId = doomedRoleId))) {
            authenticate(authToken)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val listing = client.get(href(InstructionResource())) {
            authenticate(authToken)
        }
        assertEquals(HttpStatusCode.OK, listing.status)
        val rows = listing.body<List<AgentInstructionDto>>()
        // The sole-linked row is gone from the library; the shared row stays with its updated usage.
        assertEquals(listOf(sharedId), rows.map { it.id })
        assertEquals(setOf(ownedRole.id), rows.single().linkedRoleIds)
    }
}