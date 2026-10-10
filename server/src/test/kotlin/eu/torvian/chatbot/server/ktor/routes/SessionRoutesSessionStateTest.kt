package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.core.UpdateSessionGroupRequest
import eu.torvian.chatbot.common.models.api.core.UpdateSessionLeafMessageRequest
import eu.torvian.chatbot.server.testutils.auth.authenticate
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Session group and leaf-message integration tests.
 *
 * Covers updating a session's group and its current leaf message, including the ownership checks.
 */
class SessionRoutesSessionStateTest : SessionRoutesTestBase() {

    // --- PUT /api/v1/sessions/{sessionId}/group Tests ---

    @Test
    fun `PUT session group should update group ID successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        testDataManager.insertGroupOwnership(testGroup2.id, authHelper.defaultTestUser.id)
        val newGroupId = testGroup2.id
        val updateRequest = UpdateSessionGroupRequest(groupId = newGroupId)

        // Act
        val response =
            client.put(href(SessionResource.ById.Group(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session group ID was actually updated
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNotNull(retrievedSession)
        assertEquals(newGroupId, retrievedSession.groupId)
    }

    // --- PUT /api/v1/sessions/{sessionId}/leafMessage Tests ---

    @Test
    fun `PUT session leaf message should update leaf message ID successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        testDataManager.insertChatMessage(testUserMessage)
        testDataManager.insertChatMessage(testAssistantMessage)
        val updateRequest = UpdateSessionLeafMessageRequest(leafMessageId = testAssistantMessage.id)

        // Act
        val response =
            client.put(href(SessionResource.ById.LeafMessage(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session leaf message ID was actually updated
        val retrievedLeaf = testDataManager.getSessionCurrentLeaf(testSession.id)
        assertNotNull(retrievedLeaf)
        assertEquals(testAssistantMessage.id, retrievedLeaf.messageId)
    }

    @Test
    fun `PUT session group as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 994L, email = "otheruser6@example.com", username = "otheruser6")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        val updateRequest = UpdateSessionGroupRequest(groupId = 42L)

        // Act
        val response =
            client.put(href(SessionResource.ById.Group(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
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
    fun `PUT session leaf message as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 993L, email = "otheruser7@example.com", username = "otheruser7")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)
        // Insert messages referenced by the leaf update
        testDataManager.insertChatMessage(testUserMessage)
        testDataManager.insertChatMessage(testAssistantMessage)

        val updateRequest = UpdateSessionLeafMessageRequest(leafMessageId = testAssistantMessage.id)

        // Act
        val response =
            client.put(href(SessionResource.ById.LeafMessage(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
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
