package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.AgentRoleApi
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [KtorAgentRoleApiClient] covering the five agent-role endpoints and error mapping.
 */
class KtorAgentRoleApiClientTest {

    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): AgentRoleApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorAgentRoleApiClient(httpClient)
    }

    private fun mockRole(
        id: Long,
        name: String,
        modelId: Long = 1L,
        settingsId: Long = 1L
    ) = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = modelId,
        modelSettingsId = settingsId,
        tools = setOf(1L, 2L),
        instructions = emptyList()
    )

    // --- getAllRoles ---

    @Test
    fun `getAllRoles - success`() = runTest {
        val roles = listOf(mockRole(1, "writer"), mockRole(2, "coder"))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(AgentRoleResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(roles),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllRoles()) {
            is Either.Right -> {
                assertEquals(2, result.value.size)
                assertEquals("writer", result.value[0].name)
                assertEquals(setOf(1L, 2L), result.value[0].tools)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllRoles - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllRoles()) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
            }
        }
    }

    // --- getRoleById ---

    @Test
    fun `getRoleById - success`() = runTest {
        val role = mockRole(7, "reviewer")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(AgentRoleResource.ById(roleId = 7L)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(role),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getRoleById(7L)) {
            is Either.Right -> assertEquals("reviewer", result.value.name)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getRoleById - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getRoleById(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- createRole ---

    @Test
    fun `createRole - success`() = runTest {
        val request = CreateAgentRoleRequest(
            name = "translator",
            modelId = 1L,
            modelSettingsId = 2L,
            toolIds = setOf(5L)
        )
        val created = mockRole(10, "translator")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(AgentRoleResource()), request.url.fullPath)
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("translator"), "Request body should contain the role name")
            assertTrue(body.contains("toolIds"), "Request body should contain toolIds")
            respond(
                content = json.encodeToString(created),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createRole(request)) {
            is Either.Right -> assertEquals("translator", result.value.name)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createRole - failure - 400 Bad Request`() = runTest {
        val request = CreateAgentRoleRequest(name = "", modelId = 1L, modelSettingsId = 2L)
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Role name cannot be blank.")
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createRole(request)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
            }
        }
    }

    // --- updateRole ---

    @Test
    fun `updateRole - success`() = runTest {
        val request = UpdateAgentRoleRequest(
            name = "editor",
            modelId = 1L,
            modelSettingsId = 2L,
            toolIds = emptySet()
        )
        val updated = mockRole(10, "editor")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(href(AgentRoleResource.ById(roleId = 10L)), request.url.fullPath)
            respond(
                content = json.encodeToString(updated),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateRole(10L, request)) {
            is Either.Right -> assertEquals("editor", result.value.name)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    // --- setRoleDisabled ---

    @Test
    fun `setRoleDisabled - success`() = runTest {
        val updated = mockRole(10, "translator").copy(disabled = true)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(AgentRoleResource.ById.Disabled(parent = AgentRoleResource.ById(roleId = 10L))),
                request.url.fullPath
            )
            val body = request.body.toByteArray().decodeToString()
            // The codec is pretty-printing, so tolerate the whitespace around the separator.
            assertTrue(
                Regex("\"disabled\"\\s*:\\s*true").containsMatchIn(body),
                "Request body should carry the requested disabled state, got: $body"
            )
            respond(
                content = json.encodeToString(updated),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.setRoleDisabled(10L, disabled = true)) {
            is Either.Right -> assertTrue(result.value.disabled)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `setRoleDisabled - failure - 409 Conflict`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(
                    apiError(CommonApiErrorCodes.CONFLICT, "Agent role is disabled")
                ),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.setRoleDisabled(10L, disabled = false)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.CONFLICT.code, error.apiError.code)
            }
        }
    }

    @Test
    fun `updateRole - failure - 404 Not Found`() = runTest {
        val request = UpdateAgentRoleRequest(name = "editor", modelId = 1L, modelSettingsId = 2L)
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateRole(999L, request)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
            }
        }
    }

    // --- deleteRole ---

    @Test
    fun `deleteRole - success`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(href(AgentRoleResource.ById(roleId = 10L)), request.url.fullPath)
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteRole(10L)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteRole - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteRole(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
            }
        }
    }
}
