package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.UserToolApprovalPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.tool.ToolExecutorFactory
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ChatServiceImpl].
 *
 * This test suite verifies that [ChatServiceImpl] correctly orchestrates
 * calls to the underlying DAOs and services, and handles business logic validation.
 * All dependencies are mocked using MockK.
 */
class ChatServiceImplTest {

    // Mocked dependencies
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var modelSettingsService: ModelSettingsService
    private lateinit var llmProviderService: LLMProviderService
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var credentialManager: CredentialManager
    private lateinit var toolService: ToolService
    private lateinit var toolCallDao: ToolCallDao
    private lateinit var toolExecutorFactory: ToolExecutorFactory
    private lateinit var transactionScope: TransactionScope
    private lateinit var localMcpExecutor: LocalMCPExecutor
    private lateinit var userToolApprovalPreferenceDao: UserToolApprovalPreferenceDao

    // Class under test
    private lateinit var chatService: ChatServiceImpl

    // Test data
    private val testMessage1 = ChatMessage.UserMessage(
        id = 1L,
        sessionId = 1L,
        content = "Hello, how are you?",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    private val testMessage2 = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = 1L,
        content = "I'm doing well, thank you!",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        parentMessageId = 1L,
        childrenMessageIds = emptyList(),
        fileReferences = emptyList(),
        modelId = 1L,
        settingsId = 1L
    )

    private val testSession = ChatSession(
        id = 1L,
        name = "Test Session",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        groupId = null,
        currentModelId = 1L,
        currentSettingsId = 1L,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo",
        type = LLMModelType.CHAT
    )

    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "test-key-id",
        name = "OpenAI",
        description = "OpenAI Provider",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    private val testSettings = ChatModelSettings(
        id = 1L,
        name = "Default",
        modelId = 1L,
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = null,
        stream = false
    )

    private val testLlmConfig = LLMConfig(
        provider = testProvider,
        model = testModel,
        settings = testSettings,
        apiKey = "test-api-key"
    )

    private val mockLlmResponse = LLMCompletionResult(
        id = "test-response-id",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = "I'm doing well, thank you!",
                finishReason = "stop",
                index = 0
            )
        ),
        usage = LLMCompletionResult.UsageStats(
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15
        ),
        metadata = mapOf(
            "api_object" to "chat.completion",
            "api_created" to 1234567890L,
            "api_model" to "gpt-3.5-turbo"
        )
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        messageDao = mockk()
        sessionDao = mockk()
        llmModelService = mockk()
        modelSettingsService = mockk()
        llmProviderService = mockk()
        llmApiClient = mockk()
        credentialManager = mockk()
        toolService = mockk()
        toolCallDao = mockk()
        toolExecutorFactory = mockk()
        transactionScope = mockk()
        localMcpExecutor = mockk()
        userToolApprovalPreferenceDao = mockk()

        // Create the service instance with mocked dependencies
        chatService = ChatServiceImpl(
            messageDao, sessionDao, llmApiClient, toolCallDao, toolExecutorFactory, toolService, llmModelService,
            modelSettingsService, llmProviderService, credentialManager, transactionScope, localMcpExecutor,
            userToolApprovalPreferenceDao
        )

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(
            messageDao, sessionDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, toolService,
            toolCallDao, toolExecutorFactory, transactionScope, localMcpExecutor,
            userToolApprovalPreferenceDao
        )
    }

    // --- validateProcessNewMessageRequest Tests ---

    @Test
    fun `validateProcessNewMessageRequest should return ModelConfigurationError when content is null and parentMessageId is null`() =
        runTest {
            // Arrange
            val sessionId = 1L

            // Act - Branch & Continue mode requires parentMessageId when content is null
            val result = chatService.validateProcessNewMessageRequest(sessionId, null, null, false)

            // Assert
            assertTrue(result.isLeft(), "Should return Left when content is null and parentMessageId is null")
            val error = result.leftOrNull()
            assertNotNull(error, "Error should not be null")
            assertTrue(error is ValidateNewMessageError.ModelConfigurationError, "Should be ModelConfigurationError")
            assertTrue(
                (error as ValidateNewMessageError.ModelConfigurationError).message.contains("Branch & Continue"),
                "Error message should mention Branch & Continue mode"
            )
            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        }

    @Test
    fun `validateProcessNewMessageRequest should return SessionNotFound when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.getSessionById(sessionId) } returns daoError.left()

        // Act
        val result = chatService.validateProcessNewMessageRequest(sessionId, "test content", null, false)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is ValidateNewMessageError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as ValidateNewMessageError.SessionNotFound).sessionId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
    }

    @Test
    fun `validateProcessNewMessageRequest should return ModelConfigurationError when no model is selected`() = runTest {
        // Arrange
        val sessionId = 1L
        val sessionWithoutModel = testSession.copy(currentModelId = null)
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithoutModel.right()

        // Act
        val result = chatService.validateProcessNewMessageRequest(sessionId, "test content", null, false)

        // Assert
        assertTrue(result.isLeft(), "Should return Left when no model is selected")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is ValidateNewMessageError.ModelConfigurationError, "Should be ModelConfigurationError")
        assertEquals(
            "No model selected for session $sessionId",
            (error as ValidateNewMessageError.ModelConfigurationError).message
        )
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
    }

    @Test
    fun `validateProcessNewMessageRequest should return ModelConfigurationError when no settings are selected`() =
        runTest {
            // Arrange
            val sessionId = 1L
            val sessionWithoutSettings = testSession.copy(currentSettingsId = null)
            coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithoutSettings.right()
            coEvery { llmModelService.getModelById(sessionWithoutSettings.currentModelId!!) } returns testModel.right()

            // Act
            val result = chatService.validateProcessNewMessageRequest(sessionId, "test content", null, false)

            // Assert
            assertTrue(result.isLeft(), "Should return Left when no settings are selected")
            val error = result.leftOrNull()
            assertNotNull(error, "Error should not be null")
            assertTrue(error is ValidateNewMessageError.ModelConfigurationError, "Should be ModelConfigurationError")
            assertEquals(
                "No settings selected for session $sessionId",
                (error as ValidateNewMessageError.ModelConfigurationError).message
            )
            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
            coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        }

    @Test
    fun `validateProcessNewMessageRequest should return ParentNotInSession when parent message does not exist`() =
        runTest {
            // Arrange
            val sessionId = 1L
            val parentMessageId = 999L
            val daoError = MessageError.MessageNotFound(parentMessageId)

            coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
            coEvery { messageDao.getMessageById(parentMessageId) } returns daoError.left()

            // Act
            val result = chatService.validateProcessNewMessageRequest(sessionId, "test content", parentMessageId, false)

            // Assert
            assertTrue(result.isLeft(), "Should return Left for parent not found")
            val error = result.leftOrNull()
            assertNotNull(error, "Error should not be null")
            assertTrue(error is ValidateNewMessageError.ParentNotInSession, "Should be ParentNotInSession error")
            assertEquals(sessionId, (error as ValidateNewMessageError.ParentNotInSession).sessionId)
            assertEquals(parentMessageId, error.parentId)

            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
            coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
            coVerify(exactly = 1) { messageDao.getMessageById(parentMessageId) }
        }

    @Test
    fun `validateProcessNewMessageRequest should return session and LLMConfig when validation succeeds`() = runTest {
        // Arrange
        val sessionId = 1L
        val streamingSettings = testSettings.copy(stream = true)
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { llmModelService.getModelById(testSession.currentModelId!!) } returns testModel.right()
        coEvery { modelSettingsService.getSettingsById(testSession.currentSettingsId!!) } returns streamingSettings.right()
        coEvery { llmProviderService.getProviderById(testModel.providerId) } returns testProvider.right()
        coEvery { credentialManager.getCredential(testProvider.apiKeyId!!) } returns "test-api-key".right()
        coEvery { toolService.getEnabledToolsForSession(sessionId) } returns emptyList()

        // Act
        val result = chatService.validateProcessNewMessageRequest(sessionId, "test content", null, true)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful validation")
        val (session, llmConfig) = result.getOrNull()!!
        assertEquals(testSession, session, "Should return the correct session")
        assertEquals(testProvider, llmConfig.provider, "Should return correct provider")
        assertEquals(testModel, llmConfig.model, "Should return correct model")
        assertEquals(streamingSettings, llmConfig.settings, "Should return correct settings")
        assertEquals("test-api-key", llmConfig.apiKey, "Should return correct API key")

        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { llmModelService.getModelById(testSession.currentModelId!!) }
        coVerify(exactly = 1) { modelSettingsService.getSettingsById(testSession.currentSettingsId!!) }
        coVerify(exactly = 1) { llmProviderService.getProviderById(testModel.providerId) }
        coVerify(exactly = 1) { credentialManager.getCredential(testProvider.apiKeyId!!) }
    }

    // --- processNewMessage Tests ---

    @Test
    fun `processNewMessage should successfully process message and emit events`() = runTest {
        // Arrange
        val content = "Hello, how are you?"
        val userMessage = testMessage1
        val assistantMessage = testMessage2
        val llmResponseContent = "I'm doing well, thank you!"

        // Mock all the dependencies for successful flow
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                null,
                any(),
                ChatMessage.Role.USER,
                content,
                null,
                null
            )
        } returns userMessage.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { toolCallDao.getToolCallsBySessionId(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns mockLlmResponse.right()
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                userMessage.id,
                any(),
                ChatMessage.Role.ASSISTANT,
                llmResponseContent,
                testModel.id,
                testSettings.id
            )
        } returns assistantMessage.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) } returns Unit.right()

        // Act
        val events = mutableListOf<Either<ProcessNewMessageError, MessageEvent>>()
        chatService.processNewMessage(
            1L,
            testSession,
            testLlmConfig,
            content,
            null,
            emptyList(),
            emptyFlow(),
            emptyFlow()
        )
            .collect { event -> events.add(event) }

        // Assert
        assertEquals(3, events.size, "Should emit 3 events")

        // Check first event: UserMessageSaved
        assertTrue(events[0].isRight(), "First event should be Right")
        val firstEvent = events[0].getOrNull()
        assertTrue(firstEvent is MessageEvent.UserMessageSaved, "First event should be UserMessageSaved")
        assertEquals(userMessage, firstEvent.userMessage)

        // Check second event: AssistantMessageSaved
        assertTrue(events[1].isRight(), "Second event should be Right")
        val secondEvent = events[1].getOrNull()
        assertTrue(secondEvent is MessageEvent.AssistantMessageSaved, "Second event should be AssistantMessageSaved")
        assertEquals(assistantMessage, secondEvent.assistantMessage)

        // Check third event: StreamCompleted
        assertTrue(events[2].isRight(), "Third event should be Right")
        val thirdEvent = events[2].getOrNull()
        assertTrue(thirdEvent is MessageEvent.StreamCompleted, "Third event should be StreamCompleted")

        // Verify interactions
        coVerify(exactly = 1) {
            messageDao.insertMessage(
                testSession.id,
                null,
                MessageInsertPosition.APPEND,
                ChatMessage.Role.USER,
                content,
                null,
                null
            )
        }
        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            messageDao.insertMessage(
                testSession.id,
                userMessage.id,
                MessageInsertPosition.APPEND,
                ChatMessage.Role.ASSISTANT,
                llmResponseContent,
                testModel.id,
                testSettings.id
            )
        }
    }

    @Test
    fun `processNewMessage should handle parent message relationships correctly`() = runTest {
        // Arrange
        val content = "Follow-up question"
        val parentMessageId = 1L
        val parentMessage = testMessage2.copy(id = parentMessageId)
        val userMessage = testMessage1.copy(id = 3L, parentMessageId = parentMessageId)
        val assistantMessage = testMessage2.copy(id = 4L, parentMessageId = userMessage.id)

        // Mock all the dependencies for successful flow with parent message
        coEvery { messageDao.getMessageById(parentMessageId) } returns parentMessage.right()
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                parentMessageId,
                any(),
                ChatMessage.Role.USER,
                content,
                null,
                null
            )
        } returns userMessage.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { toolCallDao.getToolCallsBySessionId(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns mockLlmResponse.right()
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                userMessage.id,
                any(),
                ChatMessage.Role.ASSISTANT,
                mockLlmResponse.choices[0].content!!,
                testModel.id,
                testSettings.id
            )
        } returns assistantMessage.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) } returns Unit.right()

        // Act
        val events = mutableListOf<Either<ProcessNewMessageError, MessageEvent>>()
        chatService.processNewMessage(
            1L,
            testSession,
            testLlmConfig,
            content,
            parentMessageId,
            emptyList(),
            emptyFlow(),
            emptyFlow()
        )
            .collect { event -> events.add(event) }

        // Assert
        assertEquals(3, events.size, "Should emit 3 events")

        // Check UserMessageSaved event
        val firstEvent = events[0].getOrNull()
        assertTrue(firstEvent is MessageEvent.UserMessageSaved, "First event should be UserMessageSaved")
        assertEquals(
            parentMessageId, firstEvent.userMessage.parentMessageId,
            "User message should have correct parent"
        )

        // Check AssistantMessageSaved event
        val secondEvent = events[1].getOrNull()
        assertTrue(secondEvent is MessageEvent.AssistantMessageSaved, "Second event should be AssistantMessageSaved")
        assertEquals(
            userMessage.id, secondEvent.assistantMessage.parentMessageId,
            "Assistant message should be child of user message"
        )

        // Verify interactions
        coVerify(exactly = 1) {
            messageDao.insertMessage(
                testSession.id,
                parentMessageId,
                MessageInsertPosition.APPEND,
                ChatMessage.Role.USER,
                content,
                null,
                null
            )
        }
        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `processNewMessage should emit ExternalServiceError when LLM API call fails`() = runTest {
        // Arrange
        val content = "Hello"
        val userMessage = testMessage1
        val llmError = LLMCompletionError.NetworkError("API Error", null)

        coEvery {
            messageDao.insertMessage(
                testSession.id,
                null,
                any(),
                ChatMessage.Role.USER,
                content,
                null,
                null
            )
        } returns userMessage.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { toolCallDao.getToolCallsBySessionId(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns llmError.left()

        // Act
        val events = mutableListOf<Either<ProcessNewMessageError, MessageEvent>>()
        chatService.processNewMessage(
            1L,
            testSession,
            testLlmConfig,
            content,
            null,
            emptyList(),
            emptyFlow(),
            emptyFlow()
        )
            .collect { event -> events.add(event) }

        // Assert
        assertEquals(3, events.size, "Should emit 3 events")

        // First event: UserMessageSaved
        assertTrue(events[0].isRight(), "First event should be Right (UserMessageSaved)")
        assertTrue(events[0].getOrNull() is MessageEvent.UserMessageSaved)

        // Second event: ExternalServiceError
        assertTrue(events[1].isLeft(), "Second event should be Left (error)")
        val error = events[1].leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is ProcessNewMessageError.ExternalServiceError, "Should be ExternalServiceError")
        assertEquals(llmError, error.llmError)

        // Third event: StreamCompleted
        assertTrue(events[2].isRight(), "Third event should be Right (StreamCompleted)")
        assertTrue(events[2].getOrNull() is MessageEvent.StreamCompleted)

        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }
}
