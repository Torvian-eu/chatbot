package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.api.core.UpdateMessageRequest
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
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
import kotlin.test.assertNull

/**
 * Integration tests for Message API routes.
 *
 * This test suite verifies the HTTP endpoints for message management:
 * - PUT /api/v1/messages/{messageId}/content - Update message content by ID
 * - DELETE /api/v1/messages/{messageId} - Delete message by ID
 */
class MessageRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var messageTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var authToken: String

    // Test data
    private val testSession = TestDefaults.chatSession1.copy(id = 1L)
    private val testModel = TestDefaults.llmModel1.copy(id = 1L)
    private val testSettings = TestDefaults.modelSettings1.copy(id = 1L)

    private val testUserMessage = ChatMessage.UserMessage(
        id = 1L,
        sessionId = testSession.id,
        content = "Initial user message content",
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        parentMessageId = null,
        childrenMessageIds = listOf(2L)
    )

    private val testAssistantMessage = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = testSession.id,
        content = "Initial assistant message content",
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        parentMessageId = 1L,
        childrenMessageIds = emptyList(),
        modelId = testModel.id,
        settingsId = testSettings.id
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        messageTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureMessageRoutes(this)
            }
        )

        testDataManager = container.get()
        // Need sessions, models, settings, and messages tables
        testDataManager.setup(
            dataSet = TestDataSet(
                chatSessions = listOf(testSession),
                llmProviders = listOf(TestDefaults.llmProvider1),
                llmModels = listOf(testModel),
                modelSettings = listOf(testSettings),
                chatGroups = listOf(TestDefaults.chatGroup1)
            )
        )
        testDataManager.createTables(
            setOf(
                Table.CHAT_MESSAGES, Table.ASSISTANT_MESSAGES,
                Table.USERS,
                Table.USER_SESSIONS,
                Table.CHAT_SESSION_OWNERS
            )
        )

        // Set up authentication
        authHelper = TestAuthHelper(container)
        authToken = authHelper.createUserAndGetToken()

        // Establish ownership relationship between the test user and test session
        // This is crucial for the new authentication/authorization to work
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // --- PUT /api/v1/messages/{messageId}/content Tests ---

    @Test
    fun `PUT message content should update message successfully`() = messageTestApplication {
        // Arrange
        testDataManager.insertChatMessage(testUserMessage)
        val newContent = "Updated user message content"
        val updateRequest = UpdateMessageRequest(content = newContent)

        // Act
        val response =
            client.put(href(MessageResource.ById.Content(parent = MessageResource.ById(messageId = testUserMessage.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val updatedMessage = response.body<ChatMessage>()
        assertEquals(testUserMessage.id, updatedMessage.id)
        assertEquals(newContent, updatedMessage.content)

        // Verify the message was actually updated in the database
        val retrievedMessage = testDataManager.getChatMessage(testUserMessage.id)
        assertNotNull(retrievedMessage)
        assertEquals(newContent, retrievedMessage.content)
    }

    @Test
    fun `PUT message content with non-existent ID should return 404`() = messageTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updateRequest = UpdateMessageRequest(content = "Some content")

        // Act
        val response =
            client.put(href(MessageResource.ById.Content(parent = MessageResource.ById(messageId = nonExistentId)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Message not found", error.message)
        assert(error.details?.containsKey("messageId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("messageId"))
    }

    // --- DELETE /api/v1/messages/{messageId} Tests ---

    @Test
    fun `DELETE message should remove the message successfully`() = messageTestApplication {
        // Arrange
        testDataManager.insertChatMessage(testUserMessage)
        testDataManager.insertChatMessage(testAssistantMessage) // Insert child message too

        // Set up the session's current leaf message ID to point to the assistant message
        // This simulates a real scenario where the session tracks the current conversation leaf
        testDataManager.insertSessionCurrentLeaf(
            SessionCurrentLeafEntity(
                sessionId = testSession.id,
                messageId = testAssistantMessage.id
            )
        )

        // Act
        val response = client.delete(href(MessageResource.ById(messageId = testUserMessage.id))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the message was actually deleted
        val retrievedMessage = testDataManager.getChatMessage(testUserMessage.id)
        assertNull(retrievedMessage)
        // Note: Depending on DAO implementation, deleting a parent might delete children.
        // This test only verifies the parent is gone. A separate DAO test might verify cascade.
    }

    @Test
    fun `DELETE message with non-existent ID should return 404`() = messageTestApplication {
        // Act
        val nonExistentId = 999L
        val response = client.delete(href(MessageResource.ById(messageId = nonExistentId))) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Message not found", error.message)
        assert(error.details?.containsKey("messageId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("messageId"))
    }

    // --- 403 (Forbidden) tests for non-owner access ---

    @Test
    fun `PUT message content as non-owner should return 403`() = messageTestApplication {
        // Arrange: create another user and session owned by them
        val otherUser = authHelper.createTestUser(id = 999L, email = "otheruser@example.com", username = "otheruser")
        testDataManager.insertUser(otherUser)
        val otherSession = testSession.copy(id = 10L)
        testDataManager.insertChatSession(otherSession)
        testDataManager.insertSessionOwnership(otherSession.id, otherUser.id)

        // Insert a message in that session
        val otherUsersMessage = testUserMessage.copy(sessionId = otherSession.id, id = 11L)
        testDataManager.insertChatMessage(otherUsersMessage)

        val updateRequest = UpdateMessageRequest(content = "Updated content")

        // Act
        val response = client.put(href(MessageResource.ById.Content(parent = MessageResource.ById(messageId = otherUsersMessage.id)))) {
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
    fun `DELETE message as non-owner should return 403`() = messageTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 998L, email = "otheruser2@example.com", username = "otheruser2")
        testDataManager.insertUser(otherUser)
        val otherSession = testSession.copy(id = 12L)
        testDataManager.insertChatSession(otherSession)
        testDataManager.insertSessionOwnership(otherSession.id, otherUser.id)

        val otherUsersMessage = testUserMessage.copy(sessionId = otherSession.id, id = 13L)
        testDataManager.insertChatMessage(otherUsersMessage)

        // Act
        val response = client.delete(href(MessageResource.ById(messageId = otherUsersMessage.id))) {
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