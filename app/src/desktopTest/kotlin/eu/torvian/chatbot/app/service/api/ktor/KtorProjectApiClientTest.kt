package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ProjectApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ProjectResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Tests for [KtorProjectApiClient] covering the project CRUD endpoints and error mapping.
 */
class KtorProjectApiClientTest {

    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): ProjectApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorProjectApiClient(httpClient)
    }

    private fun mockProject(id: Long, name: String, agentRoleIds: Set<Long> = emptySet()) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = agentRoleIds
    )

    // --- getAllProjects ---

    @Test
    fun `getAllProjects - success`() = runTest {
        val projects = listOf(mockProject(1, "Research"), mockProject(2, "Writing"))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ProjectResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(projects),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllProjects()) {
            is Either.Right -> {
                assertEquals(2, result.value.size)
                assertEquals("Research", result.value[0].name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllProjects - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllProjects()) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
            }
        }
    }

    // --- getProjectById ---

    @Test
    fun `getProjectById - success`() = runTest {
        val project = mockProject(7, "Research", agentRoleIds = setOf(5L, 6L))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProjectResource.ById(projectId = 7L)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(project),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getProjectById(7L)) {
            is Either.Right -> {
                assertEquals("Research", result.value.name)
                assertEquals(setOf(5L, 6L), result.value.agentRoleIds)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getProjectById - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getProjectById(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- createProject ---

    @Test
    fun `createProject - success`() = runTest {
        val request = CreateProjectRequest(
            name = "Research",
            description = "Group of writing roles",
            agentRoleIds = setOf(5L, 6L)
        )
        val created = mockProject(10, "Research", agentRoleIds = setOf(5L, 6L))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ProjectResource()), request.url.fullPath)
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("Research"), "Request body should contain the project name")
            assertTrue(body.contains("agentRoleIds"), "Request body should contain agentRoleIds")
            respond(
                content = json.encodeToString(created),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createProject(request)) {
            is Either.Right -> {
                assertEquals("Research", result.value.name)
                assertEquals(setOf(5L, 6L), result.value.agentRoleIds)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createProject - failure - 400 Bad Request`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Project name cannot be blank.")
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createProject(CreateProjectRequest(name = ""))) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
            }
        }
    }

    // --- updateProject ---

    @Test
    fun `updateProject - success`() = runTest {
        val request = UpdateProjectRequest(name = "Research v2", agentRoleIds = setOf(5L))
        val updated = mockProject(10, "Research v2", agentRoleIds = setOf(5L))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(href(ProjectResource.ById(projectId = 10L)), request.url.fullPath)
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("agentRoleIds"), "Request body should contain agentRoleIds")
            respond(
                content = json.encodeToString(updated),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProject(10L, request)) {
            is Either.Right -> assertEquals("Research v2", result.value.name)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateProject - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProject(999L, UpdateProjectRequest(name = "X"))) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
            }
        }
    }

    // --- cloneProject ---

    @Test
    fun `cloneProject - success`() = runTest {
        val request = CloneProjectRequest(name = "Copy of Research", description = "Deep copy")
        val cloned = mockProject(11, "Copy of Research", agentRoleIds = setOf(7L, 8L))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(ProjectResource.ById.Clone(parent = ProjectResource.ById(projectId = 7L))),
                request.url.fullPath
            )
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("Copy of Research"), "Request body should contain the project name")
            respond(
                content = json.encodeToString(cloned),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.cloneProject(7L, request)) {
            is Either.Right -> {
                assertEquals("Copy of Research", result.value.name)
                assertEquals(setOf(7L, 8L), result.value.agentRoleIds)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `cloneProject - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.cloneProject(999L, CloneProjectRequest(name = "X"))) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- deleteProject ---

    @Test
    fun `deleteProject - success`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(href(ProjectResource.ById(projectId = 10L)), request.url.fullPath)
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteProject(10L)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteProject - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Project not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteProject(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
            }
        }
    }
}