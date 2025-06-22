package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionsResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.*
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
 * - POST /api/v1/sessions/{sessionId}/messages - Process new message
 */
class SessionRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var sessionTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testGroup = TestDefaults.chatGroup1.copy(id = 1L)
    private val testGroup2 = TestDefaults.chatGroup2.copy(id = 2L)
    private val testModel = TestDefaults.llmModel1.copy(id = 1L)
    private val testModel2 = TestDefaults.llmModel2.copy(id = 2L)
    private val testSettings = TestDefaults.modelSettings1.copy(id = 1L)
    private val testSettings2 = TestDefaults.modelSettings2.copy(id = 2L)
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
                modelSettings = listOf(testSettings, testSettings2)
            )
        )
        testDataManager.createTables(setOf(
            Table.CHAT_SESSIONS,
            Table.CHAT_MESSAGES,
            Table.ASSISTANT_MESSAGES,
            Table.SESSION_CURRENT_LEAF
        ))
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
        testDataManager.insertChatSession(testSession2)

        // Act
        val response = client.get(href(SessionsResource()))

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
        val response = client.get(href(SessionsResource()))

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
        val response = client.post(href(SessionsResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
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
    fun `POST sessions should create a new session successfully with default name when name is null`() = sessionTestApplication {
        // Arrange
        val createRequest = CreateSessionRequest(name = null)

        // Act
        val response = client.post(href(SessionsResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
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
        val response = client.post(href(SessionsResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
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

        // Act
        val response = client.get(href(SessionsResource.ById(sessionId = testSession.id)))

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
        val response = client.get(href(SessionsResource.ById(sessionId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Session not found", error.message)
        assert(error.details?.containsKey("sessionId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("sessionId"))
    }

    // --- DELETE /api/v1/sessions/{sessionId} Tests ---

    @Test
    fun `DELETE session should remove the session successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)

        // Act
        val response = client.delete(href(SessionsResource.ById(sessionId = testSession.id)))

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
        val response = client.delete(href(SessionsResource.ById(sessionId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Session not found", error.message)
        assert(error.details?.containsKey("sessionId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("sessionId"))
    }

    // --- PUT /api/v1/sessions/{sessionId}/name Tests ---

    @Test
    fun `PUT session name should update name successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        val newName = "Updated Session Name"
        val updateRequest = UpdateSessionNameRequest(name = newName)

        // Act
        val response = client.put(href(SessionsResource.ById.Name(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
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
        val response = client.put(href(SessionsResource.ById.Name(parent = SessionsResource.ById(sessionId = nonExistentId)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Session not found", error.message)
        assert(error.details?.containsKey("sessionId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("sessionId"))
    }

    @Test
    fun `PUT session name should return 400 for blank name`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        val blankName = "   "
        val updateRequest = UpdateSessionNameRequest(name = blankName)

        // Act
        val response = client.put(href(SessionsResource.ById.Name(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
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
        val newModelId = 2L
        val updateRequest = UpdateSessionModelRequest(modelId = newModelId)

        // Act
        val response = client.put(href(SessionsResource.ById.Model(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
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
        val newSettingsId = 2L
        val updateRequest = UpdateSessionSettingsRequest(settingsId = newSettingsId)

        // Act
        val response = client.put(href(SessionsResource.ById.Settings(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
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
        val newGroupId = 2L
        val updateRequest = UpdateSessionGroupRequest(groupId = newGroupId)

        // Act
        val response = client.put(href(SessionsResource.ById.Group(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
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
        testDataManager.insertChatMessage(testUserMessage)
        testDataManager.insertChatMessage(testAssistantMessage)
        val updateRequest = UpdateSessionLeafMessageRequest(leafMessageId = testAssistantMessage.id)

        // Act
        val response = client.put(href(SessionsResource.ById.LeafMessage(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the session leaf message ID was actually updated
        val retrievedLeaf = testDataManager.getSessionCurrentLeaf(testSession.id)
        assertNotNull(retrievedLeaf)
        assertEquals(testAssistantMessage.id, retrievedLeaf.messageId)
    }

    // --- POST /api/v1/sessions/{sessionId}/messages Tests ---

    @Test
    fun `POST session message should process new message successfully`() = sessionTestApplication {
        // Arrange
        testDataManager.insertChatSession(testSession)
        val messageContent = "Test message content"
        val processRequest = ProcessNewMessageRequest(content = messageContent)

        // Act
        val response = client.post(href(SessionsResource.ById.Messages(parent = SessionsResource.ById(sessionId = testSession.id)))) {
            contentType(ContentType.Application.Json)
            setBody(processRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val messages = response.body<List<ChatMessage>>()
        assertEquals(2, messages.size) // User message and assistant response
        assertEquals(messageContent, messages[0].content)
        assertEquals(testSession.id, messages[0].sessionId)
    }

    @Test
    fun `POST session message should return 404 for non-existent session`() = sessionTestApplication {
        // Arrange
        val nonExistentId = 999L
        val processRequest = ProcessNewMessageRequest(content = "Test message")

        // Act
        val response = client.post(href(SessionsResource.ById.Messages(parent = SessionsResource.ById(sessionId = nonExistentId)))) {
            contentType(ContentType.Application.Json)
            setBody(processRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Session not found", error.message)
        assert(error.details?.containsKey("sessionId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("sessionId"))
    }
}
