package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.InstructionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the instruction library routes (`/api/v1/instructions`).
 *
 * Exercises the endpoints against real DAOs on SQLite with foreign keys enforced: listing returns the
 * requesting user's library, creation returns 201 with a row owned by the requesting user, illegal
 * content is rejected with 400 before any write, an update is a full content replacement of an owned
 * row, and a row owned by another user collapses to 404 without leaking its existence or rewriting its
 * content. Every reported row names the roles that link it, which is the identity a library consumer
 * needs to tell shared content from role-specific content. A delete is refused with 409 while any role
 * links the row and succeeds once the last link is gone.
 */
class InstructionRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var instructionTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var authToken: String

    /** The authenticated user, owner of every row the suite reads or edits. */
    private val user = TestDefaults.user1

    /** Owner of the row that must stay invisible to the authenticated user. */
    private val otherUser = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        authHelper = TestAuthHelper(container)
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        instructionTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureInstructionRoutes(this)
            }
        )

        testDataManager = container.get()
        instructionDao = container.get()
        instructionOwnershipDao = container.get()
        agentRoleInstructionDao = container.get()
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_INSTRUCTIONS
            )
        )
        // The authenticated user is the suite's `user`; the other user only exists as the owner of a
        // foreign row and needs no session of its own.
        authToken = authHelper.createUserAndGetToken(user)
        testDataManager.insertUser(otherUser)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Inserts an instruction row owned by [ownerId], as an existing library entry would be stored.
     *
     * @param ownerId User who owns the row.
     * @param name Human-readable label of the row.
     * @param message Stored text, or null for a generated-message kind.
     * @param type The instruction kind key.
     * @return The id of the inserted row.
     */
    private suspend fun insertOwned(
        ownerId: Long,
        name: String,
        message: String? = "Text",
        type: String = AgentInstructionTypes.CUSTOM
    ): Long {
        val row = instructionDao.insertInstruction(type = type, name = name, message = message, custom = null)
        check(instructionOwnershipDao.setOwner(row.id, ownerId).isRight()) {
            "Failed to seed ownership for instruction ${row.id}"
        }
        return row.id
    }

    @Test
    fun `GET instructions lists the requesting user's library and hides foreign rows`() =
        instructionTestApplication {
            val ownId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")
            val ownMarkerId = insertOwned(
                ownerId = user.id,
                name = "Available agents",
                message = null,
                type = AgentInstructionTypes.SPAWNABLE_AGENTS
            )
            insertOwned(ownerId = otherUser.id, name = "Foreign tone", message = "Secret")

            val response = client.get(href(InstructionResource())) {
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val library = assertNotNull(response.body<List<AgentInstructionDto>>())
            // Id ascending, and only the caller's rows: a foreign row never leaks into the listing.
            assertEquals(listOf(ownId, ownMarkerId), library.map { it.id })
            assertEquals(listOf("Tone", "Available agents"), library.map { it.name })
            // The generated-message kind stores no text and has no role context here to generate one
            // from, so the listing reports an empty message for it.
            assertEquals("", library[1].message)
        }

    @Test
    fun `GET instructions names the roles that link each row`() = instructionTestApplication {
        val linkedId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")
        val orphanId = insertOwned(ownerId = user.id, name = "Orphan", message = "Unused")
        testDataManager.insertAgentRole(TestDefaults.agentRole1)
        agentRoleInstructionDao.appendInstructionForRole(
            roleId = TestDefaults.agentRole1.id,
            instructionId = linkedId
        )

        val response = client.get(href(InstructionResource())) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val library = assertNotNull(response.body<List<AgentInstructionDto>>())
        val byId = library.associateBy { it.id }
        // The linking roles travel with the rows, so a consumer needs no second request.
        assertEquals(setOf(TestDefaults.agentRole1.id), byId.getValue(linkedId).linkedRoleIds)
        // A row no role links any more stays in the library as an unassigned entry.
        assertEquals(emptySet(), byId.getValue(orphanId).linkedRoleIds)
    }

    @Test
    fun `GET instructions without auth returns 401`() = instructionTestApplication {
        insertOwned(ownerId = user.id, name = "Tone")

        val response = client.get(href(InstructionResource()))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST instruction creates a row owned by the requesting user`() = instructionTestApplication {
        val response = client.post(href(InstructionResource())) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateInstructionRequest(
                    type = AgentInstructionTypes.MODEL_SPECIFIC,
                    name = "Swift mode",
                    message = "Write idiomatic Swift",
                    custom = buildJsonObject { put("modelId", 5L) }
                )
            )
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = assertNotNull(response.body<AgentInstructionDto>())
        assertEquals(AgentInstructionTypes.MODEL_SPECIFIC, created.type)
        assertEquals("Swift mode", created.name)
        assertEquals("Write idiomatic Swift", created.message)
        assertEquals(buildJsonObject { put("modelId", 5L) }, created.custom)
        // A brand-new row is linked to nothing yet.
        assertEquals(emptySet(), created.linkedRoleIds)

        // The new row is owned by the caller, which is what makes it editable later.
        val owned = instructionDao.getInstructionsByIdsForUser(user.id, listOf(created.id))
        assertEquals(listOf(created.id), owned.map { it.id })
        assertEquals(1, instructionDao.getAllInstructionsForUser(user.id).size)
    }

    @Test
    fun `POST instruction stores no message for the generated-message kind`() = instructionTestApplication {
        val response = client.post(href(InstructionResource())) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateInstructionRequest(
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    name = "Available agents"
                )
            )
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = assertNotNull(response.body<AgentInstructionDto>())
        assertEquals("", created.message)
        // The stored row carries no text of its own: the message is generated per linked role.
        assertNull(instructionDao.getInstructionById(created.id).getOrNull()!!.message)
    }

    @Test
    fun `POST instruction with illegal content returns 400 and persists nothing`() = instructionTestApplication {
        val response = client.post(href(InstructionResource())) {
            contentType(ContentType.Application.Json)
            setBody(CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "   ", message = "Text"))
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, response.body<ApiError>().code)
        assertTrue(instructionDao.getAllInstructionsForUser(user.id).isEmpty())
    }

    @Test
    fun `PUT instruction replaces the content of an owned row`() = instructionTestApplication {
        val instructionId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")

        val response = client.put(href(InstructionResource())) {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateInstructionRequest(
                    id = instructionId,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Tone",
                    message = "Be formal"
                )
            )
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = assertNotNull(response.body<AgentInstructionDto>())
        assertEquals(instructionId, updated.id)
        assertEquals("Be formal", updated.message)
        assertEquals("Be formal", instructionDao.getInstructionById(instructionId).getOrNull()!!.message)
    }

    @Test
    fun `PUT instruction returns 404 for a row owned by another user and keeps its content`() =
        instructionTestApplication {
            val foreignId = insertOwned(ownerId = otherUser.id, name = "Foreign tone", message = "Untouched")

            val response = client.put(href(InstructionResource())) {
                contentType(ContentType.Application.Json)
                setBody(
                    UpdateInstructionRequest(
                        id = foreignId,
                        type = AgentInstructionTypes.CUSTOM,
                        name = "Foreign tone",
                        message = "Hijacked"
                    )
                )
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals(CommonApiErrorCodes.NOT_FOUND.code, response.body<ApiError>().code)
            val stored = instructionDao.getInstructionById(foreignId).getOrNull()!!
            assertEquals("Untouched", stored.message)
        }

    @Test
    fun `PUT instruction on an unknown id returns 404`() = instructionTestApplication {
        val response = client.put(href(InstructionResource())) {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateInstructionRequest(
                    id = 999L,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Tone",
                    message = "Be concise"
                )
            )
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST instruction without auth returns 401`() = instructionTestApplication {
        val response = client.post(href(InstructionResource())) {
            contentType(ContentType.Application.Json)
            setBody(CreateInstructionRequest(type = AgentInstructionTypes.CUSTOM, name = "Tone", message = "Text"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(instructionDao.getAllInstructionsForUser(user.id).isEmpty())
    }

    @Test
    fun `GET instruction by id reports the row with the roles that link it`() = instructionTestApplication {
        val linkedId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")
        insertOwned(ownerId = user.id, name = "Orphan", message = "Unused")
        testDataManager.insertAgentRole(TestDefaults.agentRole1)
        agentRoleInstructionDao.appendInstructionForRole(
            roleId = TestDefaults.agentRole1.id,
            instructionId = linkedId
        )

        val response = client.get(href(InstructionResource.ById(instructionId = linkedId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val read = assertNotNull(response.body<AgentInstructionDto>())
        assertEquals(linkedId, read.id)
        assertEquals("Be concise", read.message)
        // The usage projection (which roles link the row) travels with the single row.
        assertEquals(setOf(TestDefaults.agentRole1.id), read.linkedRoleIds)
    }

    @Test
    fun `GET instruction by id returns 404 for a foreign row`() = instructionTestApplication {
        val foreignId = insertOwned(ownerId = otherUser.id, name = "Foreign tone", message = "Secret")

        val response = client.get(href(InstructionResource.ById(instructionId = foreignId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, response.body<ApiError>().code)
    }

    @Test
    fun `GET instruction by id returns 404 for an unknown id and 401 without auth`() =
        instructionTestApplication {
            val unknown = client.get(href(InstructionResource.ById(instructionId = 999L))) {
                authenticate(authToken)
            }
            assertEquals(HttpStatusCode.NotFound, unknown.status)

            val unauthenticated = client.get(href(InstructionResource.ById(instructionId = 999L)))
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
        }

    @Test
    fun `DELETE instruction returns 409 naming the linking roles and keeps the row and its links`() =
        instructionTestApplication {
            val linkedId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")
            testDataManager.insertAgentRole(TestDefaults.agentRole1)
            agentRoleInstructionDao.appendInstructionForRole(
                roleId = TestDefaults.agentRole1.id,
                instructionId = linkedId
            )

            val response = client.delete(href(InstructionResource.ById(instructionId = linkedId))) {
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val error = assertNotNull(response.body<ApiError>())
            assertEquals(CommonApiErrorCodes.RESOURCE_IN_USE.code, error.code)
            // The conflict names what blocks the delete, so the caller can unlink it and retry.
            assertEquals(linkedId.toString(), error.details?.get("instructionId"))
            assertEquals(TestDefaults.agentRole1.id.toString(), error.details?.get("roleIds"))
            // The row, its ownership and its link all survive the refusal.
            assertNotNull(instructionDao.getInstructionById(linkedId).getOrNull())
            assertNotNull(instructionOwnershipDao.getOwner(linkedId).getOrNull())
            assertEquals(
                listOf(linkedId),
                agentRoleInstructionDao.getLinksForRoles(listOf(TestDefaults.agentRole1.id))
                    .getValue(TestDefaults.agentRole1.id)
                    .map { it.instructionId }
            )
        }

    @Test
    fun `DELETE instruction removes the row once its last link is gone`() = instructionTestApplication {
        val unlinkedId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")
        val remainingId = insertOwned(ownerId = user.id, name = "Style", message = "Be formal")
        testDataManager.insertAgentRole(TestDefaults.agentRole1)
        agentRoleInstructionDao.replaceInstructionsForRole(
            TestDefaults.agentRole1.id,
            listOf(unlinkedId, remainingId)
        )
        // Unlinking the row from its last role is what makes it deletable.
        agentRoleInstructionDao.removeInstructionFromRole(TestDefaults.agentRole1.id, unlinkedId)

        val response = client.delete(href(InstructionResource.ById(instructionId = unlinkedId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        // The row and its ownership are gone; the other row keeps its relative place in the role's list.
        assertTrue(instructionDao.getInstructionById(unlinkedId).isLeft())
        assertNull(instructionOwnershipDao.getOwner(unlinkedId).getOrNull())
        assertEquals(listOf(remainingId), instructionDao.getAllInstructionsForUser(user.id).map { it.id })
        val remainingLinks = agentRoleInstructionDao.getLinksForRoles(listOf(TestDefaults.agentRole1.id))
            .getValue(TestDefaults.agentRole1.id)
        assertEquals(listOf(remainingId), remainingLinks.map { it.instructionId })
    }

    @Test
    fun `DELETE instruction returns 404 for a foreign row and keeps it`() = instructionTestApplication {
        val foreignId = insertOwned(ownerId = otherUser.id, name = "Foreign tone", message = "Untouched")

        val response = client.delete(href(InstructionResource.ById(instructionId = foreignId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, response.body<ApiError>().code)
        assertNotNull(instructionDao.getInstructionById(foreignId).getOrNull())
    }

    @Test
    fun `DELETE instruction without auth returns 401 and deletes nothing`() = instructionTestApplication {
        val instructionId = insertOwned(ownerId = user.id, name = "Tone", message = "Be concise")

        val response = client.delete(href(InstructionResource.ById(instructionId = instructionId)))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNotNull(instructionDao.getInstructionById(instructionId).getOrNull())
    }
}
