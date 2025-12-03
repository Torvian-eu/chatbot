package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
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
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for Session API routes.
 *
 * This test suite verifies the HTTP endpoints for session management:
 * - GET /api/v1/sessions - List all sessions
 * - POST /api/v1/sessions - Create a new session
 * - GET /api/v1/sessions/{sessionId} - Get session by ID
 * - DELETE /api/v1/sessions/{sessionId} - Delete session by ID
 * - PUT /api/v1/sessions/{sessionId}/name - Update session name
 * - PUT /api/v1/sessions/{sessionId}/model - Update session model
 * - PUT /api/v1/sessions/{sessionId}/settings - Update session settings
 * - PUT /api/v1/sessions/{sessionId}/leafMessage - Update session leaf message
 * - PUT /api/v1/sessions/{sessionId}/group - Update session group
 * - WS /api/v1/sessions/{sessionId}/messages - Process new message
 */
class SessionRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var sessionTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var authToken: String
    private val json = Json

    // Test data
    private val testGroup = TestDefaults.chatGroup1.copy(id = 1L)
    private val testGroup2 = TestDefaults.chatGroup2.copy(id = 2L)
    private val testModel = TestDefaults.llmModel1.copy(id = 1L)
    private val testModel2 = TestDefaults.llmModel2.copy(id = 2L)
    private val testSettings = TestDefaults.modelSettings1.copy(id = 1L)
    private val testSettings2 = TestDefaults.modelSettings2.copy(id = 2L)

    // Create additional settings that belong to the same model as testSession for testing
    private val testSettings3 = TestDefaults.modelSettings1.copy(
        id = 3L,
        modelId = testModel.id, // Same model as testSession
        name = "Alternative Settings for Model 1"
    )

    // Create non-streaming settings for testing non-streaming message processing
    private val testNonStreamingSettings = TestDefaults.modelSettings1.copy(
        id = 4L,
        modelId = testModel.id,
        name = "Non-Streaming Settings for Model 1",
        stream = false
    )
    private val testSession = TestDefaults.chatSession1.copy(
        id = 1L,
        name = "Test Session",
        groupId = testGroup.id,
        currentModelId = testModel.id,
        currentSettingsId = testSettings.id
    )
    private val testSession2 = TestDefaults.chatSession2.copy(
        id = 2L,
        name = "Test Session 2",
        groupId = testGroup.id,
        currentModelId = testModel.id,
        currentSettingsId = testSettings.id
    )

    // Create a test session configured for non-streaming
    private val testNonStreamingSession = TestDefaults.chatSession1.copy(
        id = 3L,
        name = "Non-Streaming Test Session",
        groupId = testGroup.id,
        currentModelId = testModel.id,
        currentSettingsId = testNonStreamingSettings.id
    )
    private val testUserMessage = TestDefaults.chatMessage1.copy(
        id = 1L,
        sessionId = testSession.id
    )
    private val testAssistantMessage = TestDefaults.chatMessage2.copy(
        id = 2L,
        sessionId = testSession.id,
        parentMessageId = testUserMessage.id
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        sessionTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureSessionRoutes(this)
            }
        )

        testDataManager = container.get()
        // Setup required tables and test data
        testDataManager.setup(
            dataSet = TestDataSet(
                apiSecrets = listOf(TestDefaults.apiSecret1),
                chatGroups = listOf(testGroup, testGroup2),
                llmProviders = listOf(TestDefaults.llmProvider1, TestDefaults.llmProvider2),
                llmModels = listOf(testModel, testModel2),
                modelSettings = listOf(testSettings, testSettings2, testSettings3, testNonStreamingSettings)
            )
        )
        testDataManager.createTables(
            setOf(
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.SESSION_CURRENT_LEAF,
                Table.TOOL_CALLS,
                Table.USERS,
                Table.ROLES, Table.USER_ROLE_ASSIGNMENTS,
                Table.USER_SESSIONS,
                Table.CHAT_SESSION_OWNERS,
                Table.CHAT_GROUP_OWNERS,
                Table.LLM_MODEL_OWNERS,
                Table.MODEL_SETTINGS_OWNERS
            )
        )

        // Set up authentication
        authHelper = TestAuthHelper(container)
        authToken = authHelper.createUserAndGetToken()
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

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
    fun `POST sessions should create a new session successfully with default name when name is null`() =
        sessionTestApplication {
            // Arrange
            val createRequest = CreateSessionRequest(name = null)

            // Act
            val response = client.post(href(SessionResource())) {
                contentType(ContentType.Application.Json)
                setBody(createRequest)
                authenticate(authToken)
            }

            // Assert
            assertEquals(HttpStatusCode.Created, response.status)
            val createdSession = response.body<ChatSession>()
            assertEquals("New Chat", createdSession.name) // Default name from SessionServiceImpl

            // Verify the session was actually created in the database
            val retrievedSession = testDataManager.getChatSession(createdSession.id)
            assertNotNull(retrievedSession)
            assertEquals("New Chat", retrievedSession.name)
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
        assertEquals(testSession.currentModelId, session.currentModelId)
        assertEquals(testSession.currentSettingsId, session.currentSettingsId)
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

    // --- PUT /api/v1/sessions/{sessionId}/model Tests ---

    @Test
    fun `PUT session model should update model ID successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        testDataManager.insertModelOwnership(testModel2.id, authHelper.defaultTestUser.id)
        val newModelId = testModel2.id
        val updateRequest = UpdateSessionModelRequest(modelId = newModelId)

        // Act
        val response =
            client.put(href(SessionResource.ById.Model(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session model ID was actually updated
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNotNull(retrievedSession)
        assertEquals(newModelId, retrievedSession.currentModelId)
    }

    // --- PUT /api/v1/sessions/{sessionId}/settings Tests ---

    @Test
    fun `PUT session settings should update settings ID successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        testDataManager.insertSettingsOwnership(testSettings3.id, authHelper.defaultTestUser.id)
        val newSettingsId = testSettings3.id // Use settings that belong to the same model as the session
        val updateRequest = UpdateSessionSettingsRequest(settingsId = newSettingsId)

        // Act
        val response =
            client.put(href(SessionResource.ById.Settings(parent = SessionResource.ById(sessionId = testSession.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
                authenticate(authToken)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session settings ID was actually updated
        val retrievedSession = testDataManager.getChatSession(testSession.id)
        assertNotNull(retrievedSession)
        assertEquals(newSettingsId, retrievedSession.currentSettingsId)
    }

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

    // --- WS /api/v1/sessions/{sessionId}/messages Tests ---

    @Test
    fun `WS session message should process new message successfully and emit WebSocket events`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testNonStreamingSession)
        testDataManager.insertSessionOwnership(testNonStreamingSession.id, authHelper.defaultTestUser.id)
        val messageContent = "Test message content"
        val processRequest = ProcessNewMessageRequest(content = messageContent, isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testNonStreamingSession.id))),
            request = { authenticate(authToken) }
        ) {
            // Send initial message
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            // Collect incoming events
            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket events
        assertTrue(receivedEvents.isNotEmpty(), "Should have received WebSocket events")

        // Should have user_message_saved event
        val userMessageEvent = receivedEvents.filterIsInstance<ChatEvent.UserMessageSaved>().firstOrNull()
        assertNotNull(userMessageEvent, "Should receive user_message_saved event")
        assertEquals(messageContent, userMessageEvent.userMessage.content)

        // Should have assistant_message_saved event
        val assistantMessageEvent = receivedEvents.filterIsInstance<ChatEvent.AssistantMessageSaved>().firstOrNull()
        assertNotNull(assistantMessageEvent, "Should receive assistant_message_saved event")
        assertNotNull(assistantMessageEvent.assistantMessage)

        // Should end with done event
        val doneEvent = receivedEvents.filterIsInstance<ChatEvent.StreamCompleted>().firstOrNull()
        assertNotNull(doneEvent, "Should receive done event")

        // Verify the messages were actually created in the database
        val messages = testDataManager.getChatMessagesForSession(testNonStreamingSession.id)
        assertEquals(2, messages.size, "Should create 2 messages (user and assistant)")

        val userMessage = messages.find { it is ChatMessage.UserMessage } as? ChatMessage.UserMessage
        assertNotNull(userMessage, "Should have created user message")
        assertEquals(messageContent, userMessage.content)
        assertEquals(testNonStreamingSession.id, userMessage.sessionId)
        assertNull(userMessage.parentMessageId)

        val assistantMessage = messages.find { it is ChatMessage.AssistantMessage } as? ChatMessage.AssistantMessage
        assertNotNull(assistantMessage, "Should have created assistant message")
        assertEquals(testNonStreamingSession.id, assistantMessage.sessionId)
        assertEquals(userMessage.id, assistantMessage.parentMessageId)
        assertEquals(testModel.id, assistantMessage.modelId)
        assertEquals(testNonStreamingSettings.id, assistantMessage.settingsId)

        // Verify the session leaf message ID was actually updated
        val leaf = testDataManager.getSessionCurrentLeaf(testNonStreamingSession.id)
        assertNotNull(leaf, "Expected leaf message to be created")
        assertEquals(assistantMessage.id, leaf.messageId)
    }

    @Test
    fun `WS session message should emit error event for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L
        val processRequest = ProcessNewMessageRequest(content = "Test message", isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = nonExistentId))),
            request = { authenticate(authToken) }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket error event
        val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
        assertNotNull(errorEvent, "Should receive error event")
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, errorEvent.error.code)
    }

    @Test
    fun `WS session message should emit error event for missing model`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession.copy(currentModelId = null))
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
        val processRequest = ProcessNewMessageRequest(content = "Test message", isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testSession.id))),
            request = { authenticate(authToken) }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket error event
        val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
        assertNotNull(errorEvent, "Should receive error event")
        assertTrue(
            errorEvent.error.message.contains("LLM configuration error", ignoreCase = true) ||
                    errorEvent.error.code == ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR.code,
            "Error should indicate missing model"
        )

        // Verify no messages were created
        val messages = testDataManager.getChatMessagesForSession(testSession.id)
        assertEquals(0, messages.size, "No messages should be created on error")
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

    @Test
    fun `PUT session model as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 996L, email = "otheruser4@example.com", username = "otheruser4")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        val updateRequest = UpdateSessionModelRequest(modelId = 2L)

        // Act
        val response =
            client.put(href(SessionResource.ById.Model(parent = SessionResource.ById(sessionId = testSession.id)))) {
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
    fun `PUT session settings as non-owner should return 403`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 995L, email = "otheruser5@example.com", username = "otheruser5")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        val updateRequest = UpdateSessionSettingsRequest(settingsId = testSettings3.id)

        // Act
        val response =
            client.put(href(SessionResource.ById.Settings(parent = SessionResource.ById(sessionId = testSession.id)))) {
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

    @Test
    fun `WS session message as non-owner should emit error event for forbidden access`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(id = 992L, email = "otheruser8@example.com", username = "otheruser8")
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        val processRequest = ProcessNewMessageRequest(content = "Hi", parentMessageId = null, isStreaming = false)

        // Act
        val receivedEvents = mutableListOf<ChatEvent>()
        client.webSocket(
            urlString = href(SessionResource.ById.Messages(parent = SessionResource.ById(sessionId = testSession.id))),
            request = { authenticate(authToken) }
        ) {
            val initialEvent: ChatClientEvent = ChatClientEvent.ProcessNewMessage(processRequest)
            send(Frame.Text(json.encodeToString(initialEvent)))

            for (frame in incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val chatEvent = json.decodeFromString<ChatEvent>(textFrame.readText())
                receivedEvents.add(chatEvent)
            }
        }

        // Assert - WebSocket error event
        val errorEvent = receivedEvents.filterIsInstance<ChatEvent.ErrorOccurred>().firstOrNull()
        assertNotNull(errorEvent, "Should receive error event")
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, errorEvent.error.code)
    }
}
