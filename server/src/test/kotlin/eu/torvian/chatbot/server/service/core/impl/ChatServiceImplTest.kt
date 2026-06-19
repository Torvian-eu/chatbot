package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.preparation.PreparedConversationTurn
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnEvent
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Unit tests for [ChatServiceImpl].
 *
 * This test suite verifies that [ChatServiceImpl] delegates validation/preparation and correctly maps
 * orchestrator events onto the public service API.
 */
class ChatServiceImplTest {

    // Mocked dependencies
    private lateinit var conversationTurnOrchestrator: ConversationTurnOrchestrator
    private lateinit var conversationTurnPreparationService: ConversationTurnPreparationService

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
        conversationTurnOrchestrator = mockk()
        conversationTurnPreparationService = mockk()

        chatService = ChatServiceImpl(
            conversationTurnOrchestrator,
            conversationTurnPreparationService
        )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(conversationTurnOrchestrator, conversationTurnPreparationService)
    }

    // --- validateProcessNewMessageRequest delegation tests ---

    @Test
    fun `validateProcessNewMessageRequest should delegate to preparation service and map prepared turn`() = runTest {
        val preparedTurn = PreparedConversationTurn(testSession, testLlmConfig)
        coEvery {
            conversationTurnPreparationService.prepareNewMessageTurn(1L, "test content", 2L, true)
        } returns preparedTurn.right()

        val result = chatService.validateProcessNewMessageRequest(1L, "test content", 2L, true)

        assertEquals((testSession to testLlmConfig).right(), result)
        coVerify(exactly = 1) {
            conversationTurnPreparationService.prepareNewMessageTurn(1L, "test content", 2L, true)
        }
    }

    @Test
    fun `validateProcessNewMessageRequest should return preparation errors unchanged`() = runTest {
        val error = ValidateNewMessageError.SessionNotFound(999L)
        coEvery {
            conversationTurnPreparationService.prepareNewMessageTurn(999L, "test content", null, false)
        } returns error.left()

        val result = chatService.validateProcessNewMessageRequest(999L, "test content", null, false)

        assertEquals(error.left(), result)
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
