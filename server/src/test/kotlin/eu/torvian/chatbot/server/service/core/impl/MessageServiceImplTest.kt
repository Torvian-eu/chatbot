package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.message.GetMessagesError
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MessageServiceImpl].
 *
 * This test suite verifies that [MessageServiceImpl] correctly orchestrates
 * calls to the underlying DAOs and services, and handles business logic validation.
 * All dependencies are mocked using MockK.
 */
class MessageServiceImplTest {

    // Mocked dependencies
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var sessionOwnershipDao: SessionOwnershipDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var modelSettingsService: ModelSettingsService
    private lateinit var llmProviderService: LLMProviderService
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var credentialManager: CredentialManager
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var messageService: MessageServiceImpl

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
        customParams = null
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
        sessionOwnershipDao = mockk()
        llmModelService = mockk()
        modelSettingsService = mockk()
        llmProviderService = mockk()
        llmApiClient = mockk()
        credentialManager = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        messageService = MessageServiceImpl(
            messageDao, sessionDao, sessionOwnershipDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, transactionScope
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
            messageDao, sessionDao, sessionOwnershipDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, transactionScope
        )
    }

    // --- getMessagesBySessionId Tests ---

    @Test
    fun `getMessagesBySessionId should return list of messages when user owns session`() = runTest {
        // Arrange
        val userId = 1L
        val sessionId = 1L
        val expectedMessages = listOf(testMessage1, testMessage2)
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns expectedMessages

        // Act
        val result = messageService.getMessagesBySessionId(userId, sessionId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful retrieval")
        assertEquals(expectedMessages, result.getOrNull(), "Should return the messages from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
    }

    @Test
    fun `getMessagesBySessionId should return empty list when user owns session but no messages exist`() = runTest {
        // Arrange
        val userId = 1L
        val sessionId = 1L
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns emptyList()

        // Act
        val result = messageService.getMessagesBySessionId(userId, sessionId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful retrieval")
        val messages = result.getOrNull()
        assertNotNull(messages, "Messages should not be null")
        assertTrue(messages.isEmpty(), "Should return empty list when no messages exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
    }

    @Test
    fun `getMessagesBySessionId should return SessionNotFound when session does not exist`() = runTest {
        // Arrange
        val userId = 1L
        val sessionId = 999L
        val ownershipError = GetOwnerError.ResourceNotFound("Session not found")
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns ownershipError.left()

        // Act
        val result = messageService.getMessagesBySessionId(userId, sessionId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is GetMessagesError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as GetMessagesError.SessionNotFound).sessionId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 0) { messageDao.getMessagesBySessionId(any()) }
    }

    @Test
    fun `getMessagesBySessionId should return AccessDenied when user does not own session`() = runTest {
        // Arrange
        val userId = 1L
        val otherUserId = 2L
        val sessionId = 1L
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns otherUserId.right()

        // Act
        val result = messageService.getMessagesBySessionId(userId, sessionId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for access denied")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is GetMessagesError.AccessDenied, "Should be AccessDenied error")
        assertEquals("User does not have access to this session", (error as GetMessagesError.AccessDenied).message)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 0) { messageDao.getMessagesBySessionId(any()) }
    }

    // --- updateMessageContent Tests ---

    @Test
    fun `updateMessageContent should update message content successfully`() = runTest {
        // Arrange
        val userId = 1L
        val messageId = 1L
        val newContent = "Updated content"
        val updatedMessage = testMessage1.copy(content = newContent)
        coEvery { messageDao.getMessageById(messageId) } returns testMessage1.right()
        coEvery { sessionOwnershipDao.getOwner(testMessage1.sessionId) } returns userId.right()
        coEvery { messageDao.updateMessageContent(messageId, newContent) } returns updatedMessage.right()

        // Act
        val result = messageService.updateMessageContent(userId, messageId, newContent)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        assertEquals(updatedMessage, result.getOrNull(), "Should return the updated message")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(testMessage1.sessionId) }
        coVerify(exactly = 1) { messageDao.updateMessageContent(messageId, newContent) }
    }

    @Test
    fun `updateMessageContent should return MessageNotFound error when message does not exist`() = runTest {
        // Arrange
        val userId = 1L
        val messageId = 999L
        val newContent = "Updated content"
        val daoError = MessageError.MessageNotFound(messageId)
        coEvery { messageDao.getMessageById(messageId) } returns daoError.left()

        // Act
        val result = messageService.updateMessageContent(userId, messageId, newContent)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for message not found")
        val error = result.leftOrNull()
        assertTrue(error is UpdateMessageContentError.MessageNotFound, "Should be MessageNotFound error")
        assertEquals(messageId, error.id)

        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 0) { sessionOwnershipDao.getOwner(any()) }
        coVerify(exactly = 0) { messageDao.updateMessageContent(any(), any()) }
    }

    @Test
    fun `updateMessageContent should return AccessDenied error when user does not own session`() = runTest {
        // Arrange
        val userId = 1L
        val otherUserId = 2L
        val messageId = 1L
        val newContent = "Updated content"
        coEvery { messageDao.getMessageById(messageId) } returns testMessage1.right()
        coEvery { sessionOwnershipDao.getOwner(testMessage1.sessionId) } returns otherUserId.right()

        // Act
        val result = messageService.updateMessageContent(userId, messageId, newContent)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for access denied")
        val error = result.leftOrNull()
        assertTrue(error is UpdateMessageContentError.AccessDenied, "Should be AccessDenied error")
        assertEquals("User does not own the session containing this message", error.reason)

        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(testMessage1.sessionId) }
        coVerify(exactly = 0) { messageDao.updateMessageContent(any(), any()) }
    }

    // --- validateProcessNewMessageRequest Tests ---

    @Test
    fun `validateProcessNewMessageRequest should return SessionNotFound when session does not exist`() = runTest {
        // Arrange
        val userId = 1L
        val sessionId = 999L
        val ownershipError = GetOwnerError.ResourceNotFound("Session $sessionId not found")
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns ownershipError.left()

        // Act
        val result = messageService.validateProcessNewMessageRequest(userId, sessionId, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is ValidateNewMessageError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as ValidateNewMessageError.SessionNotFound).sessionId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 0) { sessionDao.getSessionById(sessionId) } // Should not reach this call
    }

    @Test
    fun `validateProcessNewMessageRequest should return SessionNotFound when ownership check fails due to non-existent session`() =
        runTest {
            // Arrange
            val userId = 1L
            val sessionId = 999L
            val ownershipError = GetOwnerError.ResourceNotFound("Session not found")
            coEvery { sessionOwnershipDao.getOwner(sessionId) } returns ownershipError.left()

            // Act
            val result = messageService.validateProcessNewMessageRequest(userId, sessionId, null)

            // Assert
            assertTrue(result.isLeft(), "Should return Left for non-existent session")
            val error = result.leftOrNull()
            assertNotNull(error, "Error should not be null")
            assertTrue(error is ValidateNewMessageError.SessionNotFound, "Should be SessionNotFound error")
            assertEquals(sessionId, (error as ValidateNewMessageError.SessionNotFound).sessionId)
            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
            coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        }

    @Test
    fun `validateProcessNewMessageRequest should return AccessDenied when user does not own the session`() = runTest {
        // Arrange
        val userId = 2L // Different user than the session owner
        val sessionId = 1L
        val sessionOwnerId = 1L // Session owned by user 1L
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns sessionOwnerId.right()

        // Act
        val result = messageService.validateProcessNewMessageRequest(userId, sessionId, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for unauthorized access")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is ValidateNewMessageError.AccessDenied, "Should be AccessDenied error")
        assertEquals(
            "User does not have access to this session",
            (error as ValidateNewMessageError.AccessDenied).reason
        )
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
    }

    @Test
    fun `validateProcessNewMessageRequest should return ModelConfigurationError when no model is selected`() = runTest {
        // Arrange
        val userId = 1L
        val sessionId = 1L
        val sessionWithoutModel = testSession.copy(currentModelId = null)
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithoutModel.right()

        // Act
        val result = messageService.validateProcessNewMessageRequest(userId, sessionId, null)

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
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
    }

    @Test
    fun `validateProcessNewMessageRequest should return ModelConfigurationError when no settings are selected`() =
        runTest {
            // Arrange
            val userId = 1L
            val sessionId = 1L
            val sessionWithoutSettings = testSession.copy(currentSettingsId = null)
            coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
            coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithoutSettings.right()

            // Act
            val result = messageService.validateProcessNewMessageRequest(userId, sessionId, null)

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
            coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
            coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        }

    @Test
    fun `validateProcessNewMessageRequest should return ParentNotInSession when parent message does not exist`() =
        runTest {
            // Arrange
            val userId = 1L
            val sessionId = 1L
            val parentMessageId = 999L
            val daoError = MessageError.MessageNotFound(parentMessageId)

            coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
            coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
            coEvery { messageDao.getMessageById(parentMessageId) } returns daoError.left()

            // Act
            val result = messageService.validateProcessNewMessageRequest(userId, sessionId, parentMessageId)

            // Assert
            assertTrue(result.isLeft(), "Should return Left for parent not found")
            val error = result.leftOrNull()
            assertNotNull(error, "Error should not be null")
            assertTrue(error is ValidateNewMessageError.ParentNotInSession, "Should be ParentNotInSession error")
            assertEquals(sessionId, (error as ValidateNewMessageError.ParentNotInSession).sessionId)
            assertEquals(parentMessageId, error.parentId)

            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
            coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
            coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
            coVerify(exactly = 1) { messageDao.getMessageById(parentMessageId) }
        }

    @Test
    fun `validateProcessNewMessageRequest should return session and LLMConfig when validation succeeds`() = runTest {
        // Arrange
        val userId = 1L
        val sessionId = 1L
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { llmModelService.getModelById(testSession.currentModelId!!) } returns testModel.right()
        coEvery { modelSettingsService.getSettingsById(testSession.currentSettingsId!!) } returns testSettings.right()
        coEvery { llmProviderService.getProviderById(testModel.providerId) } returns testProvider.right()
        coEvery { credentialManager.getCredential(testProvider.apiKeyId!!) } returns "test-api-key".right()

        // Act
        val result = messageService.validateProcessNewMessageRequest(userId, sessionId, null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful validation")
        val (session, llmConfig) = result.getOrNull()!!
        assertEquals(testSession, session, "Should return the correct session")
        assertEquals(testProvider, llmConfig.provider, "Should return correct provider")
        assertEquals(testModel, llmConfig.model, "Should return correct model")
        assertEquals(testSettings, llmConfig.settings, "Should return correct settings")
        assertEquals("test-api-key", llmConfig.apiKey, "Should return correct API key")

        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { llmModelService.getModelById(testSession.currentModelId!!) }
        coVerify(exactly = 1) { modelSettingsService.getSettingsById(testSession.currentSettingsId!!) }
        coVerify(exactly = 1) { llmProviderService.getProviderById(testModel.providerId) }
        coVerify(exactly = 1) { credentialManager.getCredential(testProvider.apiKeyId!!) }
    }

    // --- processNewMessage Tests ---

    @Test
    fun `processNewMessage should successfully process message and return user and assistant messages`() = runTest {
        // Arrange
        val content = "Hello, how are you?"
        val userMessage = testMessage1
        val assistantMessage = testMessage2
        val llmResponseContent = "I'm doing well, thank you!"

        // Mock all the dependencies for successful flow
        coEvery { messageDao.insertUserMessage(testSession.id, content, null) } returns userMessage.right()
        coEvery {
            llmApiClient.completeChat(
                any(),
                testModel,
                testProvider,
                testSettings,
                "test-api-key"
            )
        } returns mockLlmResponse.right()
        coEvery {
            messageDao.insertAssistantMessage(
                testSession.id,
                llmResponseContent,
                userMessage.id,
                testModel.id,
                testSettings.id
            )
        } returns assistantMessage.right()
        // Mock both updateSessionLeafMessageId calls: first for user message, then for assistant message
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) } returns Unit.right()
        // Mock getMessageById call to retrieve updated user message
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()

        // Act
        val result = messageService.processNewMessage(testSession, testLlmConfig, content, null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful processing")
        val messages = result.getOrNull()
        assertNotNull(messages, "Messages should not be null")
        assertEquals(2, messages.size, "Should return 2 messages (user and assistant)")
        assertEquals(userMessage, messages[0], "First message should be user message")
        assertEquals(assistantMessage, messages[1], "Second message should be assistant message")

        // Verify all interactions
        coVerify(exactly = 1) { messageDao.insertUserMessage(testSession.id, content, null) }
        coVerify(exactly = 1) {
            llmApiClient.completeChat(
                any(),
                testModel,
                testProvider,
                testSettings,
                "test-api-key"
            )
        }
        coVerify(exactly = 1) {
            messageDao.insertAssistantMessage(
                testSession.id,
                llmResponseContent,
                userMessage.id,
                testModel.id,
                testSettings.id
            )
        }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) }
        coVerify(exactly = 1) { messageDao.getMessageById(userMessage.id) }
    }

    @Test
    fun `processNewMessage should handle parent message relationships correctly`() = runTest {
        // Arrange
        val content = "Follow-up question"
        val parentMessageId = 1L
        val userMessage = testMessage1.copy(id = 3L, parentMessageId = parentMessageId)
        val assistantMessage = testMessage2.copy(id = 4L, parentMessageId = userMessage.id)

        // Mock all the dependencies for successful flow with parent message
        coEvery { messageDao.insertUserMessage(testSession.id, content, parentMessageId) } returns userMessage.right()
        coEvery {
            llmApiClient.completeChat(
                any(),
                testModel,
                testProvider,
                testSettings,
                "test-api-key"
            )
        } returns mockLlmResponse.right()
        coEvery {
            messageDao.insertAssistantMessage(
                testSession.id,
                mockLlmResponse.choices[0].content,
                userMessage.id,
                testModel.id,
                testSettings.id
            )
        } returns assistantMessage.right()
        // Mock both updateSessionLeafMessageId calls: first for user message, then for assistant message
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) } returns Unit.right()
        // Mock getMessageById call to retrieve updated user message
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()

        // Act
        val result = messageService.processNewMessage(testSession, testLlmConfig, content, parentMessageId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful processing with parent")
        val messages = result.getOrNull()
        assertNotNull(messages, "Messages should not be null")
        assertEquals(2, messages.size, "Should return 2 messages (user and assistant)")
        assertEquals(parentMessageId, messages[0].parentMessageId, "User message should have correct parent")
        assertEquals(messages[0].id, messages[1].parentMessageId, "Assistant message should be child of user message")

        // Verify all interactions
        coVerify(exactly = 1) { messageDao.insertUserMessage(testSession.id, content, parentMessageId) }
        coVerify(exactly = 1) {
            llmApiClient.completeChat(
                any(),
                testModel,
                testProvider,
                testSettings,
                "test-api-key"
            )
        }
        coVerify(exactly = 1) {
            messageDao.insertAssistantMessage(
                testSession.id,
                mockLlmResponse.choices[0].content,
                userMessage.id,
                testModel.id,
                testSettings.id
            )
        }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) }
        coVerify(exactly = 1) { messageDao.getMessageById(userMessage.id) }
    }

    @Test
    fun `processNewMessage should return ExternalServiceError when LLM API call fails`() = runTest {
        // Arrange
        val content = "Hello"
        val userMessage = testMessage1

        coEvery { messageDao.insertUserMessage(testSession.id, content, null) } returns userMessage.right()
        coEvery { messageDao.getMessagesBySessionId(testSession.id) } returns listOf(userMessage)
        coEvery {
            llmApiClient.completeChat(
                any(),
                testModel,
                testProvider,
                testSettings,
                "test-api-key"
            )
        } returns LLMCompletionError.NetworkError("API Error", null).left()
        // Mock the updateSessionLeafMessageId call for the user message (this will be called before LLM API call)
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()

        // Act
        val result = messageService.processNewMessage(testSession, testLlmConfig, content, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for LLM API failure")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is ProcessNewMessageError.ExternalServiceError, "Should be ExternalServiceError")

        coVerify(exactly = 1) {
            llmApiClient.completeChat(
                any(),
                testModel,
                testProvider,
                testSettings,
                "test-api-key"
            )
        }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) }
    }
}
