package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorSessionApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): SessionApi {
        val httpClient = createHttpClient("http://localhost", json, mockEngine)
        return KtorSessionApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private val now = Clock.System.now()
    private fun mockSessionSummary(
        id: Long,
        name: String,
        groupId: Long? = null,
        groupName: String? = null
    ) = ChatSessionSummary(id, name, now, now, groupId, groupName)

    private fun mockSession(
        id: Long,
        name: String,
        groupId: Long? = null,
        currentModelId: Long? = null,
        currentSettingsId: Long? = null,
        currentLeafMessageId: Long? = null,
        messages: List<ChatMessage> = emptyList()
    ) = ChatSession(
        id,
        name,
        now,
        now,
        groupId,
        currentModelId,
        currentSettingsId,
        currentLeafMessageId,
        messages
    )

    private fun mockUserMessage(id: Long, sessionId: Long, content: String) =
        ChatMessage.UserMessage(id, sessionId, content, now, now, null)

    private fun mockAssistantMessage(
        id: Long,
        sessionId: Long,
        content: String,
        parentId: Long,
        modelId: Long = 1,
        settingsId: Long = 1
    ) = ChatMessage.AssistantMessage(
        id,
        sessionId,
        content,
        now,
        now,
        parentId,
        emptyList(),
        modelId,
        settingsId
    )

    // --- Tests for getAllSessions ---
    @Test
    fun `getAllSessions - success`() = runTest {
        val mockSessions = listOf(
            mockSessionSummary(1, "Session 1"),
            mockSessionSummary(2, "Session 2", groupId = 10, groupName = "Group A")
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(SessionResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(mockSessions),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllSessions()
        when (result) {
            is Either.Right -> {
                val sessions = result.value
                assertEquals(2, sessions.size)
                assertEquals("Session 1", sessions[0].name)
                assertEquals("Session 2", sessions[1].name)
                assertEquals(10, sessions[1].groupId)
                assertEquals("Group A", sessions[1].groupName)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllSessions - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllSessions()
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
    fun `getAllSessions - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = """{"sessions": "not a list"}""", // Bad JSON
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllSessions()
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for createSession ---
    @Test
    fun `createSession - success`() = runTest {
        val mockRequest = CreateSessionRequest(name = "New Session")
        val mockSession = mockSession(1, "New Session")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(SessionResource()), request.url.fullPath)
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = json.encodeToString(mockSession),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createSession(mockRequest)
        when (result) {
            is Either.Right -> {
                val session = result.value
                assertEquals(1, session.id)
                assertEquals("New Session", session.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createSession - success with no name`() = runTest {
        val mockRequest = CreateSessionRequest(name = null)
        val mockSession = mockSession(1, "Untitled Session") // Assuming backend generates name
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(SessionResource()), request.url.fullPath)
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = json.encodeToString(mockSession),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createSession(mockRequest)
        when (result) {
            is Either.Right -> {
                val session = result.value
                assertEquals(1, session.id)
                assertEquals("Untitled Session", session.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createSession - failure - 400 Bad Request`() = runTest {
        val mockRequest = CreateSessionRequest(name = "") // Invalid name
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createSession(mockRequest)
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
    fun `createSession - failure - SerializationException`() = runTest {
        val mockRequest = CreateSessionRequest(name = "New Session")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"session": "not a session"}""", // Bad JSON
                status = HttpStatusCode.Created, // Still a 201, but body is wrong
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.createSession(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for getSessionDetails ---
    @Test
    fun `getSessionDetails - success`() = runTest {
        val sessionId = 456L
        val mockSessionWithMessages = mockSession(
            id = sessionId,
            name = "Detailed Session",
            messages = listOf(
                mockUserMessage(1, sessionId, "Hi"),
                mockAssistantMessage(2, sessionId, "Hello!", 1)
            )
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(SessionResource.ById(sessionId = sessionId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockSessionWithMessages),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSessionDetails(sessionId)
        when (result) {
            is Either.Right -> {
                val session = result.value
                assertEquals(sessionId, session.id)
                assertEquals("Detailed Session", session.name)
                assertEquals(2, session.messages.size)
                assertEquals("Hi", session.messages[0].content)
                assertEquals("Hello!", session.messages[1].content)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getSessionDetails - failure - 404 Not Found`() = runTest {
        val sessionId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(SessionResource.ById(sessionId = sessionId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSessionDetails(sessionId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `getSessionDetails - failure - SerializationException`() = runTest {
        val sessionId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = """{"id": 123, "name": "Bad Session", "messages": "not a list"}""", // Bad JSON
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSessionDetails(sessionId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for deleteSession ---
    @Test
    fun `deleteSession - success`() = runTest {
        val sessionId = 789L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(SessionResource.ById(sessionId = sessionId)),
                request.url.fullPath
            )
            respond(
                content = "", // 204 No Content response has no body
                status = HttpStatusCode.NoContent
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteSession(sessionId)
        when (result) {
            is Either.Right -> {
                assertEquals(Unit, result.value) // Expect Unit on success
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteSession - failure - 404 Not Found`() = runTest {
        val sessionId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(SessionResource.ById(sessionId = sessionId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteSession(sessionId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }

    // --- Tests for updateSessionName ---
    @Test
    fun `updateSessionName - success`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionNameRequest(name = "Updated Name")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Name(SessionResource.ById(sessionId = sessionId))),
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
        val result = apiClient.updateSessionName(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionName - failure - 400 Bad Request`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionNameRequest(name = "") // Invalid name
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionName(sessionId, mockRequest)
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
    fun `updateSessionName - failure - 404 Not Found`() = runTest {
        val sessionId = 999L
        val mockRequest = UpdateSessionNameRequest(name = "Updated Name")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionName(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }

    // --- Tests for updateSessionModel ---
    @Test
    fun `updateSessionModel - success`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionModelRequest(modelId = 10L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Model(SessionResource.ById(sessionId = sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionModel(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionModel - success - unset model`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionModelRequest(modelId = null)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Model(SessionResource.ById(sessionId = sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionModel(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionModel - failure - 400 Bad Request (Invalid ModelId)`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionModelRequest(modelId = 999L) // Non-existent model
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Model ID not found",
                        "modelId" to mockRequest.modelId.toString()
                    )
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionModel(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Model ID not found", error.apiError.message)
                assertEquals("999", error.apiError.details?.get("modelId"))
            }
        }
    }

    @Test
    fun `updateSessionModel - failure - 404 Not Found (Session)`() = runTest {
        val sessionId = 999L
        val mockRequest = UpdateSessionModelRequest(modelId = 10L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionModel(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }

    // --- Tests for updateSessionSettings ---
    @Test
    fun `updateSessionSettings - success`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionSettingsRequest(settingsId = 20L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Settings(SessionResource.ById(sessionId = sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionSettings(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionSettings - success - unset settings`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionSettingsRequest(settingsId = null)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Settings(SessionResource.ById(sessionId = sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionSettings(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionSettings - failure - 400 Bad Request (Invalid SettingsId)`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionSettingsRequest(settingsId = 999L) // Non-existent settings
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Settings ID not found",
                        "settingsId" to mockRequest.settingsId.toString()
                    )
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionSettings(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Settings ID not found", error.apiError.message)
                assertEquals("999", error.apiError.details?.get("settingsId"))
            }
        }
    }

    @Test
    fun `updateSessionSettings - failure - 404 Not Found (Session)`() = runTest {
        val sessionId = 999L
        val mockRequest = UpdateSessionSettingsRequest(settingsId = 20L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionSettings(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }

    // --- Tests for updateSessionLeafMessage ---
    @Test
    fun `updateSessionLeafMessage - success`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionLeafMessageRequest(leafMessageId = 30L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.LeafMessage(SessionResource.ById(SessionResource(), sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionLeafMessage(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionLeafMessage - success - unset leaf message`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionLeafMessageRequest(leafMessageId = null)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.LeafMessage(SessionResource.ById(SessionResource(), sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionLeafMessage(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionLeafMessage - failure - 400 Bad Request (Invalid LeafMessageId)`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionLeafMessageRequest(leafMessageId = 999L) // Non-existent message
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Leaf message ID not found or not in session",
                        "leafMessageId" to mockRequest.leafMessageId.toString()
                    )
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionLeafMessage(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Leaf message ID not found or not in session", error.apiError.message)
                assertEquals("999", error.apiError.details?.get("leafMessageId"))
            }
        }
    }

    @Test
    fun `updateSessionLeafMessage - failure - 404 Not Found (Session)`() = runTest {
        val sessionId = 999L
        val mockRequest = UpdateSessionLeafMessageRequest(leafMessageId = 30L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionLeafMessage(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }

    // --- Tests for updateSessionGroup ---
    @Test
    fun `updateSessionGroup - success`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionGroupRequest(groupId = 40L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Group(SessionResource.ById(SessionResource(), sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionGroup(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionGroup - success - ungroup session`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionGroupRequest(groupId = null)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SessionResource.ById.Group(SessionResource.ById(SessionResource(), sessionId))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = "",
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionGroup(sessionId, mockRequest)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSessionGroup - failure - 400 Bad Request (Invalid GroupId)`() = runTest {
        val sessionId = 123L
        val mockRequest = UpdateSessionGroupRequest(groupId = 999L) // Non-existent group
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Group ID not found",
                        "groupId" to mockRequest.groupId.toString()
                    )
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionGroup(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Group ID not found", error.apiError.message)
                assertEquals("999", error.apiError.details?.get("groupId"))
            }
        }
    }

    @Test
    fun `updateSessionGroup - failure - 404 Not Found (Session)`() = runTest {
        val sessionId = 999L
        val mockRequest = UpdateSessionGroupRequest(groupId = 40L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSessionGroup(sessionId, mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Session not found", error.apiError.message)
            }
        }
    }
}
