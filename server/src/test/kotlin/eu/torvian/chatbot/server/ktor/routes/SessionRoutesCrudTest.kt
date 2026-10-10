package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.core.CreateSessionRequest
import eu.torvian.chatbot.common.models.api.core.UpdateSessionNameRequest
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.server.testutils.auth.authenticate
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Session CRUD and ownership integration tests.
 *
 * Covers listing, creating, reading, deleting and renaming sessions, together with the authorization
 * checks for callers that do not own the addressed session.
 */
class SessionRoutesCrudTest : SessionRoutesTestBase() {

    // --- GET /api/v1/sessions Tests ---

    @Test
    fun `GET sessions should return list of sessions successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        testDataManager.insertChatSession(testSession2)
        testDataManager.insertSessionOwnership(testSession2.id, authHelper.defaultTestUser.id)

        // Act
        val response = client.get(href(SessionResource())) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val sessions = response.body<List<ChatSessionSummary>>()
        assertEquals(2, sessions.size)
        assertEquals(testSession.id, sessions[0].id)
        assertEquals(testSession.name, sessions[0].name)
        assertEquals(testSession2.id, sessions[1].id)
        assertEquals(testSession2.name, sessions[1].name)
    }

    @Test
    fun `GET sessions should return empty list when no sessions exist`() = sessionTestApplication {
        // Act
        val response = client.get(href(SessionResource())) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val sessions = response.body<List<ChatSessionSummary>>()
        assertEquals(0, sessions.size)
    }

    // --- POST /api/v1/sessions Tests ---

    @Test
    fun `POST sessions should create a new session successfully with name`() = sessionTestApplication {
        // Arrange
        val sessionName = "New Test Session"
        val createRequest = CreateSessionRequest(name = sessionName)

        // Act
        val response = client.post(href(SessionResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdSession = response.body<ChatSession>()
        assertEquals(sessionName, createdSession.name)

        // Verify the session was actually created in the database
        val retrievedSession = testDataManager.getChatSession(createdSession.id)
        assertNotNull(retrievedSession)
        assertEquals(sessionName, retrievedSession.name)
    }

    @Test
    fun `POST sessions should return 400 for blank name`() = sessionTestApplication {
        // Arrange
        val blankName = "   "
        val createRequest = CreateSessionRequest(name = blankName)

        // Act
        val response = client.post(href(SessionResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals("Invalid session name provided", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Session name cannot be blank.", error.details?.get("reason"))
    }

    // --- GET /api/v1/sessions/{sessionId} Tests ---

    @Test
    fun `GET session by ID should return session details successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)

        // Act
        val response = client.get(href(SessionResource.ById(sessionId = testSession.id))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val session = response.body<ChatSession>()
        assertEquals(testSession.id, session.id)
        assertEquals(testSession.name, session.name)
        assertEquals(testSession.groupId, session.groupId)
        assertEquals(testSession.agentRoleId, session.agentRoleId)
    }

    @Test
    fun `GET session by ID should return 404 for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.get(href(SessionResource.ById(sessionId = nonExistentId))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Resource not found", error.message)
        assert(error.details?.containsKey("id") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("id"))
    }

    // --- DELETE /api/v1/sessions/{sessionId} Tests ---

    @Test
    fun `DELETE session should remove the session successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)

        // Act
        val response = client.delete(href(SessionResource.ById(sessionId = testSession.id))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the session was actually deleted
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNull(retrievedSession)
    }

    @Test
    fun `DELETE session should return 404 for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.delete(href(SessionResource.ById(sessionId = nonExistentId))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Resource not found", error.message)
        assert(error.details?.containsKey("id") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("id"))
    }

    // --- PUT /api/v1/sessions/{sessionId}/name Tests ---

    @Test
    fun `PUT session name should update name successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        val newName = "Updated Session Name"
        val updateRequest = UpdateSessionNameRequest(name = newName)

        // Act
        val response =
            client.put(href(SessionResource.ById.Name(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session name was actually updated
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNotNull(retrievedSession)
        assertEquals(newName, retrievedSession.name)
    }

    @Test
    fun `PUT session name should return 404 for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updateRequest = UpdateSessionNameRequest(name = "New Name")

        // Act
        val response =
            client.put(href(SessionResource.ById.Name(parent = SessionResource.ById(sessionId = nonExistentId)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Resource not found", error.message)
        assert(error.details?.containsKey("id") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("id"))
    }

    @Test
    fun `PUT session name should return 400 for blank name`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        val blankName = "   "
        val updateRequest = UpdateSessionNameRequest(name = blankName)

        // Act
        val response =
            client.put(href(SessionResource.ById.Name(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals("Invalid session name provided", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Session name cannot be blank.", error.details?.get("reason"))
    }

    // --- 403 (Forbidden) tests for non-owner access ---

    @Test
    fun `GET session by ID as non-owner should return 403`() = sessionTestApplication {
        // Arrange: create session owned by someone else
        val otherUser = authHelper.createTestUser(id = 999L, email = "otheruser@example.com", username = "otheruser")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        // Act
        val response = client.get(href(SessionResource.ById(sessionId = testSession.id))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
        assertEquals(403, error.statusCode)
        assertEquals("Access denied", error.message)
    }

    @Test
    fun `DELETE session as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 998L, email = "otheruser2@example.com", username = "otheruser2")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        // Act
        val response = client.delete(href(SessionResource.ById(sessionId = testSession.id))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
        assertEquals(403, error.statusCode)
        assertEquals("Access denied", error.message)
    }

    @Test
    fun `PUT session name as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 997L, email = "otheruser3@example.com", username = "otheruser3")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        // Act
        val response =
            client.put(href(SessionResource.ById.Name(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(UpdateSessionNameRequest(name = "New Name"))
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
        assertEquals(403, error.statusCode)
        assertEquals("Access denied", error.message)
    }
}
