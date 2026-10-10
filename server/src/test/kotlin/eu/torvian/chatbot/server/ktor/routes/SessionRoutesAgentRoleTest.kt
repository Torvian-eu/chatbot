package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.agent.UpdateSessionAgentRoleRequest
import eu.torvian.chatbot.server.testutils.auth.authenticate
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Session agent-role selection integration tests.
 *
 * Covers assigning, clearing and rejecting a session's agent role, including the ownership check.
 */
class SessionRoutesAgentRoleTest : SessionRoutesTestBase() {

    // --- PUT /api/v1/sessions/{sessionId}/agentRole Tests ---

    @Test
    fun `PUT session agentRole should update agent role ID successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        testDataManager.insertAgentRoleOwnership(testAgentRole2.id, authHelper.defaultTestUser.id)
        val newAgentRoleId = testAgentRole2.id
        val updateRequest = UpdateSessionAgentRoleRequest(agentRoleId = newAgentRoleId)

        // Act
        val response =
            client.put(href(SessionResource.ById.AgentRole(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session agent role ID was actually updated
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNotNull(retrievedSession)
        assertEquals(newAgentRoleId, retrievedSession.agentRoleId)
    }

    @Test
    fun `PUT session agentRole should deselect the role when agentRoleId is null`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        val updateRequest = UpdateSessionAgentRoleRequest(agentRoleId = null)

        // Act
        val response =
            client.put(href(SessionResource.ById.AgentRole(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session agent role ID was cleared
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNotNull(retrievedSession)
        assertNull(retrievedSession.agentRoleId)
    }

    @Test
    fun `PUT session agentRole should return 409 when the role is disabled for the requesting user`() =
        sessionTestApplication {
            // Arrange
            testDataManager.insertChatSession(testSession)
            testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
            testDataManager.insertAgentRoleOwnership(testAgentRole2.id, authHelper.defaultTestUser.id)
            // A per-user disabled marker: the role exists and is owned, but the requesting user disabled it.
            testDataManager.insertAgentRoleDisabled(testAgentRole2.id, authHelper.defaultTestUser.id)
            val updateRequest = UpdateSessionAgentRoleRequest(agentRoleId = testAgentRole2.id)

            // Act
            val response =
                client.put(href(SessionResource.ById.AgentRole(parent = SessionResource.ById(sessionId = testSession.id)))) {
                    contentType(ContentType.Application.Json)
                    setBody(updateRequest)
                    authenticate(authToken)
                }

            // Assert
            assertEquals(HttpStatusCode.Conflict, response.status)
            val error = response.body<ApiError>()
            assertEquals(CommonApiErrorCodes.CONFLICT.code, error.code)
            assertEquals("Agent role is disabled", error.message)

            // The attach must not reach the session update: the session keeps its previous role
            // (testSession.agentRoleId = testAgentRole.id), i.e. the disabled role was never attached.
            val retrievedSession = testDataManager.getChatSession(testSession.id)
            assertNotNull(retrievedSession)
            assertEquals(testAgentRole.id, retrievedSession.agentRoleId)
        }

    @Test
    fun `PUT session agentRole should return 404 for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updateRequest = UpdateSessionAgentRoleRequest(agentRoleId = testAgentRole.id)

        // Act
        val response =
            client.put(href(SessionResource.ById.AgentRole(parent = SessionResource.ById(sessionId = nonExistentId)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT session agentRole as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 996L, email = "otheruser4@example.com", username = "otheruser4")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        val updateRequest = UpdateSessionAgentRoleRequest(agentRoleId = testAgentRole2.id)

        // Act
        val response =
            client.put(href(SessionResource.ById.AgentRole(parent = SessionResource.ById(sessionId = testSession.id)))) {
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
