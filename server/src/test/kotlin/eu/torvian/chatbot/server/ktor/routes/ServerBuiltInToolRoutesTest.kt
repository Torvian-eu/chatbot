package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.ServerBuiltInToolResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.service.core.impl.ServerBuiltInToolDefinitionSeeder
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
import kotlin.test.assertTrue

/**
 * Integration tests for the server built-in tool management routes
 * (/api/v1/server-built-in-tools).
 *
 * Covers JWT enforcement, per-user listing, the path/body id mismatch guard, ownership denial
 * (including that the 403 body does not reveal the owner), successful updates, and the reset
 * endpoint's catalog reconciliation.
 */
class ServerBuiltInToolRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var seeder: ServerBuiltInToolDefinitionSeeder

    private val user1 = TestDefaults.user1
    private val user2 = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        app = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureServerBuiltInToolRoutes(this)
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        seeder = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.TOOL_DEFINITIONS,
                Table.SERVER_BUILTIN_TOOL_DEFINITIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `unauthenticated requests are rejected with 401`() = app {
        val getResponse = client.get(href(ServerBuiltInToolResource()))
        assertEquals(HttpStatusCode.Unauthorized, getResponse.status)

        val putResponse = client.put(href(ServerBuiltInToolResource.ById(toolId = 1L)))
        assertEquals(HttpStatusCode.Unauthorized, putResponse.status)
    }

    @Test
    fun `GET returns only the calling user's server built-in tools`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val user2Token = authHelper.createUserAndGetToken(
            user = user2,
            session = authHelper.createTestSession(id = 999L, userId = user2.id)
        )
        seeder.ensureForUser(user1.id)
        seeder.ensureForUser(user2.id)

        val response = client.get(href(ServerBuiltInToolResource())) {
            authenticate(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val tools = response.body<List<ServerBuiltInToolDefinition>>()
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        assertTrue(tools.all { it.userId == user1.id })

        // The second user sees only their own rows, not user1's.
        val user2Tools = client.get(href(ServerBuiltInToolResource())) {
            authenticate(user2Token)
        }.body<List<ServerBuiltInToolDefinition>>()
        assertEquals(ServerBuiltInToolCatalog.allTools.size, user2Tools.size)
        assertTrue(user2Tools.all { it.userId == user2.id })
        assertTrue(user2Tools.map { it.id }.none { it in tools.map { tool -> tool.id } })
    }

    @Test
    fun `PUT with mismatched path and body ids returns 400`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val seeded = seeder.ensureForUser(user1.id).getOrNull()!!
        val bodyTool = seeded.first()
        val pathTool = seeded.last()

        val response = client.put(href(ServerBuiltInToolResource.ById(toolId = pathTool.id))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(bodyTool)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
    }

    @Test
    fun `PUT by a non-owner returns 403 without leaking owner info`() = app {
        authHelper.createUserAndGetToken(user1)
        val tool = seeder.ensureForUser(user1.id).getOrNull()!!.first()

        val user2Token = authHelper.createUserAndGetToken(
            user = user2,
            session = authHelper.createTestSession(id = 998L, userId = user2.id)
        )

        val response = client.put(href(ServerBuiltInToolResource.ById(toolId = tool.id))) {
            authenticate(user2Token)
            contentType(ContentType.Application.Json)
            setBody(tool)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
        // A probing user must not be able to confirm the tool exists or learn who owns it: the
        // 403 body carries only the requested tool id — there is no owner-id detail key and no
        // extra field that could identify the owner (note the tool id may numerically equal the
        // owner's user id, so only the key set and message are asserted).
        val details = error.details ?: emptyMap()
        assertEquals(setOf("toolId"), details.keys)
        assertTrue(!details.containsKey("ownerUserId"))
        assertTrue(!error.message.contains(user1.id.toString()))
    }

    @Test
    fun `owner can toggle enabled state and edit the description`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val tool = seeder.ensureForUser(user1.id).getOrNull()!!.first()

        val response = client.put(href(ServerBuiltInToolResource.ById(toolId = tool.id))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(tool.copy(isEnabled = false, description = "edited description"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<ServerBuiltInToolDefinition>()
        assertEquals(false, updated.isEnabled)
        assertEquals("edited description", updated.description)
        // The server returns the authoritative row: same id, catalog name preserved.
        assertEquals(tool.id, updated.id)
        assertEquals(tool.name, updated.name)
        assertEquals(user1.id, updated.userId)
    }

    @Test
    fun `POST reset restores catalog defaults while preserving enabled state`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val tool = seeder.ensureForUser(user1.id).getOrNull()!!.first()

        // Disable one tool and drift its description away from the catalog.
        val editResponse = client.put(href(ServerBuiltInToolResource.ById(toolId = tool.id))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(tool.copy(isEnabled = false, description = "drifted description"))
        }
        assertEquals(HttpStatusCode.OK, editResponse.status)

        val resetResponse = client.post(href(ServerBuiltInToolResource.Reset())) {
            authenticate(token)
        }

        assertEquals(HttpStatusCode.OK, resetResponse.status)
        val tools = resetResponse.body<List<ServerBuiltInToolDefinition>>()
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        val after = tools.first { it.id == tool.id }
        // Catalog-derived fields are repaired...
        assertEquals(ServerBuiltInToolCatalog.specFor(tool.name)!!.description, after.description)
        // ...but the user's enabled/disabled choice survives the reset.
        assertTrue(!after.isEnabled)
    }
}
