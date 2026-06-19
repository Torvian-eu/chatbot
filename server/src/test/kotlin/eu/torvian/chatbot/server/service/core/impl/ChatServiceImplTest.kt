package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnEvent
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.security.CredentialManager
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    private lateinit var credentialManager: CredentialManager
    private lateinit var toolService: ToolService
    private lateinit var transactionScope: TransactionScope
    private lateinit var conversationTurnOrchestrator: ConversationTurnOrchestrator

    // Class under test
    private lateinit var chatService: ChatServiceImpl

    // Test data
    private val testMessage1 = eu.torvian.chatbot.common.models.core.ChatMessage.UserMessage(
        id = 1L,
        sessionId = 1L,
        content = "Hello, how are you?",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    private val testMessage2 = eu.torvian.chatbot.common.models.core.ChatMessage.AssistantMessage(
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

    /**
     * Recreates the chat service with fresh mocks and explicit helper collaborators for each test.
     */
    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        messageDao = mockk()
        sessionDao = mockk()
        llmModelService = mockk()
        modelSettingsService = mockk()
        llmProviderService = mockk()
        credentialManager = mockk()
        toolService = mockk()
        transactionScope = mockk()
        conversationTurnOrchestrator = mockk()

        // Create the service instance with mocked dependencies
        chatService = ChatServiceImpl(
            messageDao,
            sessionDao,
            toolService,
            llmModelService,
            modelSettingsService,
            llmProviderService,
            credentialManager,
            transactionScope,
            conversationTurnOrchestrator
        )

        // Mock the transaction scope to execute blocks directly.
        coEvery { transactionScope.transaction(any<suspend () -> Any?>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(
            messageDao, sessionDao, llmModelService, modelSettingsService,
            llmProviderService, credentialManager, toolService,
            transactionScope, conversationTurnOrchestrator
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
            assertIs<ValidateNewMessageError.ModelConfigurationError>(error, "Should be ModelConfigurationError")
            assertTrue(
                error.message.contains("Branch & Continue"),
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
        assertIs<ValidateNewMessageError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.sessionId)
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
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error, "Should be ModelConfigurationError")
        assertEquals(
            "No model selected for session $sessionId",
            error.message
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
            assertIs<ValidateNewMessageError.ModelConfigurationError>(error, "Should be ModelConfigurationError")
            assertEquals(
                "No settings selected for session $sessionId",
                error.message
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
            assertIs<ValidateNewMessageError.ParentNotInSession>(error, "Should be ParentNotInSession error")
            assertEquals(sessionId, error.sessionId)
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
    fun `processNewMessage should delegate to orchestrator and map non-streaming events`() = runTest {
        every {
            conversationTurnOrchestrator.processNonStreamingTurn(any())
        } returns flowOf(
            ConversationTurnEvent.UserMessageSaved(testMessage1, null),
            ConversationTurnEvent.AssistantMessageSaved(testMessage2, testMessage1),
            ConversationTurnEvent.TurnCompleted
        )

        val events = mutableListOf<Either<ProcessNewMessageError, MessageEvent>>()
        chatService.processNewMessage(1L, testSession, testLlmConfig, "Hello", null, emptyList(), emptyFlow())
            .collect { event -> events.add(event) }

        assertEquals(3, events.size)
        assertIs<MessageEvent.UserMessageSaved>(events[0].getOrNull())
        assertIs<MessageEvent.AssistantMessageSaved>(events[1].getOrNull())
        assertIs<MessageEvent.StreamCompleted>(events[2].getOrNull())
    }

    @Test
    fun `processNewMessage should map orchestrator external service errors`() = runTest {
        val llmError = LLMCompletionError.NetworkError("API Error", null)
        every {
            conversationTurnOrchestrator.processNonStreamingTurn(any())
        } returns flowOf(
            ConversationTurnEvent.ExternalServiceError(llmError),
            ConversationTurnEvent.TurnCompleted
        )

        val events = mutableListOf<Either<ProcessNewMessageError, MessageEvent>>()
        chatService.processNewMessage(1L, testSession, testLlmConfig, "Hello", null, emptyList(), emptyFlow())
            .collect { event -> events.add(event) }

        assertIs<ProcessNewMessageError.ExternalServiceError>(events[0].leftOrNull())
        assertEquals(llmError, (events[0].leftOrNull() as ProcessNewMessageError.ExternalServiceError).llmError)
        assertIs<MessageEvent.StreamCompleted>(events[1].getOrNull())
    }

    @Test
    fun `processNewMessageStreaming should delegate to orchestrator and map streaming events`() = runTest {
        every {
            conversationTurnOrchestrator.processStreamingTurn(any())
        } returns flowOf(
            ConversationTurnEvent.UserMessageSaved(testMessage1, null),
            ConversationTurnEvent.AssistantMessageStarted(testMessage2.copy(content = ""), testMessage1),
            ConversationTurnEvent.AssistantMessageDelta(testMessage2.id, "Hi "),
            ConversationTurnEvent.ToolCallDelta(testMessage2.id, 0, "call-1", "search", "{"),
            ConversationTurnEvent.AssistantMessageFinished(testMessage2),
            ConversationTurnEvent.TurnCompleted
        )

        val events = mutableListOf<Either<ProcessNewMessageError, MessageStreamEvent>>()
        chatService.processNewMessageStreaming(
            1L,
            testSession,
            testLlmConfig.copy(settings = testSettings.copy(stream = true)),
            "Hello",
            null,
            emptyList(),
            emptyFlow()
        )
            .collect { event -> events.add(event) }

        assertEquals(6, events.size)
        assertIs<MessageStreamEvent.UserMessageSaved>(events[0].getOrNull())
        assertIs<MessageStreamEvent.AssistantMessageStarted>(events[1].getOrNull())
        assertIs<MessageStreamEvent.AssistantMessageDelta>(events[2].getOrNull())
        assertIs<MessageStreamEvent.ToolCallDelta>(events[3].getOrNull())
        assertIs<MessageStreamEvent.AssistantMessageFinished>(events[4].getOrNull())
        assertIs<MessageStreamEvent.StreamCompleted>(events[5].getOrNull())
    }

    @Test
    fun `processNewMessage should preserve unexpected error mapping`() = runTest {
        every { conversationTurnOrchestrator.processNonStreamingTurn(any()) } throws IllegalStateException("boom")

        val events = mutableListOf<Either<ProcessNewMessageError, MessageEvent>>()
        chatService.processNewMessage(1L, testSession, testLlmConfig, "Hello", null, emptyList(), emptyFlow())
            .collect { event -> events.add(event) }

        assertIs<ProcessNewMessageError.UnexpectedError>(events[0].leftOrNull())
        assertIs<MessageEvent.StreamCompleted>(events[1].getOrNull())
    }

    @Test
    fun `processNewMessageStreaming should preserve unexpected error mapping`() = runTest {
        every { conversationTurnOrchestrator.processStreamingTurn(any()) } throws IllegalStateException("boom")

        val events = mutableListOf<Either<ProcessNewMessageError, MessageStreamEvent>>()
        chatService.processNewMessageStreaming(
            1L,
            testSession,
            testLlmConfig.copy(settings = testSettings.copy(stream = true)),
            "Hello",
            null,
            emptyList(),
            emptyFlow()
        )
            .collect { event -> events.add(event) }

        val error = events[0].leftOrNull()
        assertIs<ProcessNewMessageError.ExternalServiceError>(error)
        assertEquals("Unexpected error: boom", (error.llmError as LLMCompletionError.InvalidResponseError).message)
        assertIs<MessageStreamEvent.StreamCompleted>(events[1].getOrNull())
    }


}
