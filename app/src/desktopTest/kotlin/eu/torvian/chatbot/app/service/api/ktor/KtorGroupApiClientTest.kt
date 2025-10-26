package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.GroupApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.GroupResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.api.core.CreateGroupRequest
import eu.torvian.chatbot.common.models.api.core.RenameGroupRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorGroupApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): GroupApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorGroupApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private val now = Clock.System.now()
    private fun mockGroup(id: Long, name: String) = ChatGroup(id, name, now)

    // --- Tests for getAllGroups ---
    @Test
    fun `getAllGroups - success`() = runTest {
        val mockGroups = listOf(
            mockGroup(1, "Work"),
            mockGroup(2, "Personal")
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(GroupResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(mockGroups),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllGroups()
        when (result) {
            is Either.Right -> {
                val groups = result.value
                assertEquals(2, groups.size)
                assertEquals("Work", groups[0].name)
                assertEquals("Personal", groups[1].name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllGroups - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(GroupResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllGroups()
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Database error", error.apiError.message)
            }
        }
    }

    @Test
    fun `getAllGroups - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(GroupResource()), request.url.fullPath)
            respond(
                content = """{"groups": "not a list"}""", // Bad JSON
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllGroups()
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for createGroup ---
    @Test
    fun `createGroup - success`() = runTest {
        val mockRequest = CreateGroupRequest(name = "New Group")
        val mockGroup = mockGroup(1, "New Group")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(GroupResource()), request.url.fullPath)
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = json.encodeToString(mockGroup),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createGroup(mockRequest)
        when (result) {
            is Either.Right -> {
                val group = result.value
                assertEquals(1, group.id)
                assertEquals("New Group", group.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createGroup - failure - 400 Bad Request`() = runTest {
        val mockRequest = CreateGroupRequest(name = "") // Invalid name
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(GroupResource()), request.url.fullPath)
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createGroup(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Name cannot be empty", error.apiError.message)
            }
        }
    }

    @Test
    fun `createGroup - failure - SerializationException`() = runTest {
        val mockRequest = CreateGroupRequest(name = "New Group")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(GroupResource()), request.url.fullPath)
            respond(
                content = """{"group": "not a group"}""", // Bad JSON
                status = HttpStatusCode.Created, // Still a 201, but body is wrong
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createGroup(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for renameGroup ---
    @Test
    fun `renameGroup - success`() = runTest {
        val groupId = 123L
        val mockRequest = RenameGroupRequest(name = "Renamed Group")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(GroupResource.ById(groupId = groupId)),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "", // Typically 200 OK or 204 No Content for Unit response
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.renameGroup(groupId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `renameGroup - failure - 400 Bad Request`() = runTest {
        val groupId = 123L
        val mockRequest = RenameGroupRequest(name = "") // Invalid name
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(GroupResource.ById(groupId = groupId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.renameGroup(groupId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Name cannot be empty", error.apiError.message)
            }
        }
    }

    @Test
    fun `renameGroup - failure - 404 Not Found`() = runTest {
        val groupId = 999L
        val mockRequest = RenameGroupRequest(name = "Renamed Group")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(GroupResource.ById(groupId = groupId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.renameGroup(groupId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Group not found", error.apiError.message)
            }
        }
    }

    // --- Tests for deleteGroup ---
    @Test
    fun `deleteGroup - success`() = runTest {
        val groupId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(GroupResource.ById(groupId = groupId)),
                request.url.fullPath
            )
            respond(
                content = "", // 204 No Content response has no body
                status = HttpStatusCode.NoContent
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteGroup(groupId)
        when (result) {
            is Either.Right -> {
                assertEquals(Unit, result.value) // Expect Unit on success
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteGroup - failure - 404 Not Found`() = runTest {
        val groupId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(GroupResource.ById(groupId = groupId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteGroup(groupId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Group not found", error.apiError.message)
            }
        }
    }
}