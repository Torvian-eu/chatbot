package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.time.Instant

/**
 * Shared fixtures and orchestration setup for the [DefaultConversationTurnOrchestrator] test suites.
 *
 * Provides the mocked collaborators, the default model/provider/settings/session fixtures, and the fresh
 * per-test orchestrator instance. Streaming and non-streaming behavior are exercised by dedicated subclasses.
 */
abstract class DefaultConversationTurnOrchestratorTestBase {

    /** Mocked LLM API client backing the orchestrator's streaming and non-streaming calls. */
    protected lateinit var llmApiClient: LLMApiClient

    /** Mocked tool orchestrator used to execute persisted tool calls. */
    protected lateinit var toolCallOrchestrator: ToolCallOrchestrator

    /** Mocked persistence collaborator owning message and tool-call persistence. */
    protected lateinit var conversationTurnPersistence: ConversationTurnPersistence

    /** Orchestrator under test, recreated before each test. */
    protected lateinit var orchestrator: DefaultConversationTurnOrchestrator

    /** Fixed instant used as the base for every message/posted timestamp. */
    protected val baseInstant = Instant.fromEpochMilliseconds(1234567890000L)

    /** Default non-streaming model used by most tests. */
    protected val testModel = LLMModel(
        id = 1L,
        name = "gpt-4o-mini",
        providerId = 1L,
        active = true,
        displayName = "GPT-4o mini"
    )

    /** Default provider backing the test model. */
    protected val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "test-key",
        name = "OpenAI",
        description = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    /** Default chat settings used by most tests. */
    protected val testSettings = ChatModelSettings(
        id = 1L,
        name = "Default",
        modelId = 1L,
        systemMessage = "You are a helpful assistant.",
        temperature = 0.2f,
        maxTokens = 1000,
        customParams = null,
        stream = false
    )

    /** Agent role id assigned to the default [testSession]; tool execution requires a role. */
    protected val testRoleId = 7L

    /** Default session receiving the turns under test. */
    protected val testSession = ChatSession(
        id = 1L,
        name = "Session",
        createdAt = baseInstant,
        updatedAt = baseInstant,
        groupId = null,
        agentRoleId = testRoleId,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    /**
     * Recreates the orchestrator with fresh mocks for each test.
     */
    @BeforeEach
    fun setUp() {
        llmApiClient = mockk()
        toolCallOrchestrator = mockk()
        conversationTurnPersistence = mockk()

        orchestrator = DefaultConversationTurnOrchestrator(
            llmApiClient = llmApiClient,
            toolCallOrchestrator = toolCallOrchestrator,
            toolResultContentBuilder = DefaultToolResultContentBuilder(),
            chatContextBuilder = DefaultChatContextBuilder(
                fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
                toolResultContentBuilder = DefaultToolResultContentBuilder()
            ),
            conversationTurnPersistence = conversationTurnPersistence
        )
    }

    /**
     * Clears mocks after each test run.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(llmApiClient, toolCallOrchestrator, conversationTurnPersistence)
    }
}
