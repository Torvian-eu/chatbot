package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
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
import kotlin.test.assertTrue

/**
 * Integration tests for the role↔instruction link endpoints
 * (`/api/v1/agent-roles/{roleId}/instructions/{instructionId}`).
 *
 * Exercises the two link mutations against real DAOs on SQLite with foreign keys enforced: assigning
 * appends the row last and answers with the role's state after the write, a duplicate link, a
 * per-role rule violation and a foreign or unknown id are rejected without writing, and unassigning
 * removes only the link — the instruction row survives, the role's remaining positions stay
 * contiguous and the echo reports them.
 */
class AgentRoleInstructionRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var linkTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var instructionDao: InstructionDao
    private lateinit var instructionOwnershipDao: InstructionOwnershipDao
    private lateinit var agentRoleInstructionDao: AgentRoleInstructionDao
    private lateinit var authToken: String

    /** The authenticated user, owner of the role and of every instruction the suite links. */
    private val user = TestDefaults.user1

    /** Owner of the role and the rows that must stay invisible to the authenticated user. */
    private val otherUser = TestDefaults.user2

    /** The role owned by the authenticated user. */
    private val ownedRole = TestDefaults.agentRole1.copy(id = 1L, name = "My Role", modelPresetId = null)

    /** The role owned by another user, used for the ownership checks. */
    private val foreignRole = TestDefaults.agentRole2.copy(id = 2L, name = "Foreign Role", modelPresetId = null)

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        authHelper = TestAuthHelper(container)
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        linkTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureAgentRoleRoutes(this)
            }
        )

        testDataManager = container.get()
        instructionDao = container.get()
        instructionOwnershipDao = container.get()
        agentRoleInstructionDao = container.get()
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.AGENT_ROLE_TOOLS,
                Table.AGENT_ROLE_SPAWNABLE_ROLES,
                Table.AGENT_ROLE_DISABLED,
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS
            )
        )
        // The authenticated user is the suite's `user`; the other user only owns a foreign role and a
        // foreign instruction row, so it needs no session of its own.
        authToken = authHelper.createUserAndGetToken(user)
        testDataManager.insertUser(otherUser)
        testDataManager.insertAgentRole(ownedRole)
        testDataManager.insertAgentRole(foreignRole)
        testDataManager.insertAgentRoleOwnership(ownedRole.id, user.id)
        testDataManager.insertAgentRoleOwnership(foreignRole.id, otherUser.id)
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
     * @param type The instruction kind key.
     * @return The id of the inserted row.
     */
    private suspend fun insertOwned(
        ownerId: Long,
        name: String,
        type: String = AgentInstructionTypes.CUSTOM
    ): Long {
        val row = instructionDao.insertInstruction(type = type, name = name, message = "Text", custom = null)
        check(instructionOwnershipDao.setOwner(row.id, ownerId).isRight()) {
            "Failed to seed ownership for instruction ${row.id}"
        }
        return row.id
    }

    /**
     * The URL of one role↔instruction link.
     *
     * @param roleId The role the link belongs to.
     * @param instructionId The linked instruction.
     * @return The nested link resource.
     */
    private fun linkUrl(roleId: Long, instructionId: Long) =
        AgentRoleResource.ById.Instructions.ByInstructionId(
            parent = AgentRoleResource.ById.Instructions(parent = AgentRoleResource.ById(roleId = roleId)),
            instructionId = instructionId
        )

    @Test
    fun `POST link appends the instruction last and answers with the role echo`() = linkTestApplication {
        val first = insertOwned(user.id, "Role definition", AgentInstructionTypes.ROLE)
        val second = insertOwned(user.id, "Tone")

        val firstResponse = client.post(href(linkUrl(ownedRole.id, first))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, firstResponse.status)
        val afterFirst = assertNotNull(firstResponse.body<AgentRoleDto>())
        assertEquals(listOf(first), afterFirst.instructions.map { it.id })
        assertEquals(setOf(ownedRole.id), afterFirst.instructions.single().linkedRoleIds)

        val secondResponse = client.post(href(linkUrl(ownedRole.id, second))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, secondResponse.status)
        val afterSecond = assertNotNull(secondResponse.body<AgentRoleDto>())
        // Append-last: the new row goes behind the role's current maximum position.
        assertEquals(listOf(first, second), afterSecond.instructions.map { it.id })
    }

    @Test
    fun `POST link of an already linked row returns 409 and writes nothing`() = linkTestApplication {
        val instructionId = insertOwned(user.id, "Tone")
        agentRoleInstructionDao.appendInstructionForRole(ownedRole.id, instructionId)

        val response = client.post(href(linkUrl(ownedRole.id, instructionId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, response.body<ApiError>().code)
        assertEquals(
            listOf(instructionId),
            agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))
                .getValue(ownedRole.id)
                .map { it.instructionId }
        )
    }

    @Test
    fun `POST link that would break a per-role rule returns 400 and writes nothing`() = linkTestApplication {
        val existingRoleRow = insertOwned(user.id, "Role definition", AgentInstructionTypes.ROLE)
        val secondRoleRow = insertOwned(user.id, "Another role definition", AgentInstructionTypes.ROLE)
        agentRoleInstructionDao.appendInstructionForRole(ownedRole.id, existingRoleRow)

        val response = client.post(href(linkUrl(ownedRole.id, secondRoleRow))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, response.body<ApiError>().code)
        // The rules judge the resulting list, so the rejected append leaves the role untouched.
        assertEquals(
            listOf(existingRoleRow),
            agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))
                .getValue(ownedRole.id)
                .map { it.instructionId }
        )
    }

    @Test
    fun `POST link rejects a foreign or unknown instruction without leaking its existence`() =
        linkTestApplication {
            val foreignInstruction = insertOwned(otherUser.id, "Foreign tone")

            val foreignResponse = client.post(href(linkUrl(ownedRole.id, foreignInstruction))) {
                authenticate(authToken)
            }
            val unknownResponse = client.post(href(linkUrl(ownedRole.id, 999L))) {
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
            assertEquals(HttpStatusCode.NotFound, unknownResponse.status)
            assertEquals(CommonApiErrorCodes.NOT_FOUND.code, unknownResponse.body<ApiError>().code)
            assertTrue(agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id)).isEmpty())
        }

    @Test
    fun `POST link into a role owned by another user returns 404`() = linkTestApplication {
        val instructionId = insertOwned(user.id, "Tone")

        val response = client.post(href(linkUrl(foreignRole.id, instructionId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, response.body<ApiError>().code)
        // The foreign role's list stays untouched.
        assertTrue(agentRoleInstructionDao.getLinksForRoles(listOf(foreignRole.id)).isEmpty())
    }

    @Test
    fun `POST link without auth returns 401 and writes nothing`() = linkTestApplication {
        val instructionId = insertOwned(user.id, "Tone")

        val response = client.post(href(linkUrl(ownedRole.id, instructionId)))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id)).isEmpty())
    }

    @Test
    fun `DELETE link removes only the link and keeps the surviving positions contiguous`() =
        linkTestApplication {
            val first = insertOwned(user.id, "First")
            val middle = insertOwned(user.id, "Middle")
            val last = insertOwned(user.id, "Last")
            agentRoleInstructionDao.replaceInstructionsForRole(ownedRole.id, listOf(first, middle, last))

            val response = client.delete(href(linkUrl(ownedRole.id, middle))) {
                authenticate(authToken)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val echoed = assertNotNull(response.body<AgentRoleDto>())
            // The surviving entries keep their relative order...
            assertEquals(listOf(first, last), echoed.instructions.map { it.id })
            val links = agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id)).getValue(ownedRole.id)
            // ...and their stored positions stay contiguous zero-based.
            assertEquals(listOf(0, 1), links.map { it.sequence })
            // Only the link was removed: the instruction row survives as a library entry.
            assertNotNull(instructionDao.getInstructionById(middle).getOrNull())
            assertEquals(listOf(first, middle, last), instructionDao.getAllInstructionsForUser(user.id).map { it.id })
        }

    @Test
    fun `DELETE link of a pair that is not linked returns 404 and keeps the list`() = linkTestApplication {
        val linked = insertOwned(user.id, "Tone")
        val unlinked = insertOwned(user.id, "Style")
        agentRoleInstructionDao.appendInstructionForRole(ownedRole.id, linked)

        val response = client.delete(href(linkUrl(ownedRole.id, unlinked))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, response.body<ApiError>().code)
        assertEquals(
            listOf(linked),
            agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))
                .getValue(ownedRole.id)
                .map { it.instructionId }
        )
    }

    @Test
    fun `DELETE link rejects a foreign role and an unknown instruction`() = linkTestApplication {
        val instructionId = insertOwned(user.id, "Tone")
        agentRoleInstructionDao.appendInstructionForRole(ownedRole.id, instructionId)

        val foreignRoleResponse = client.delete(href(linkUrl(foreignRole.id, instructionId))) {
            authenticate(authToken)
        }
        val unknownInstructionResponse = client.delete(href(linkUrl(ownedRole.id, 999L))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, foreignRoleResponse.status)
        assertEquals(HttpStatusCode.NotFound, unknownInstructionResponse.status)
        // Neither attempt removed the owned role's link.
        assertEquals(
            listOf(instructionId),
            agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))
                .getValue(ownedRole.id)
                .map { it.instructionId }
        )
    }

    @Test
    fun `DELETE link without auth returns 401 and keeps the link`() = linkTestApplication {
        val instructionId = insertOwned(user.id, "Tone")
        agentRoleInstructionDao.appendInstructionForRole(ownedRole.id, instructionId)

        val response = client.delete(href(linkUrl(ownedRole.id, instructionId)))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            listOf(instructionId),
            agentRoleInstructionDao.getLinksForRoles(listOf(ownedRole.id))
                .getValue(ownedRole.id)
                .map { it.instructionId }
        )
    }

    @Test
    fun `assigning one row to two roles reports both roles in the echoed usage`() = linkTestApplication {
        val secondRole = TestDefaults.agentRole1.copy(id = 3L, name = "Second Role", modelPresetId = null)
        testDataManager.insertAgentRole(secondRole)
        testDataManager.insertAgentRoleOwnership(secondRole.id, user.id)
        val shared = insertOwned(user.id, "Tone")

        client.post(href(linkUrl(ownedRole.id, shared))) {
            authenticate(authToken)
        }
        val secondResponse = client.post(href(linkUrl(secondRole.id, shared))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, secondResponse.status)
        // One shared row, two links: the second role's echo reports both roles, which is what makes
        // the row shared content for every consumer.
        val echoed = assertNotNull(secondResponse.body<AgentRoleDto>())
        val entry = echoed.instructions.single()
        assertEquals(shared, entry.id)
        assertEquals(setOf(ownedRole.id, secondRole.id), entry.linkedRoleIds)
    }
}
