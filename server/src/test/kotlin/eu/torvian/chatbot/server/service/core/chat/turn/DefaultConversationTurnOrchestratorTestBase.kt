package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactedMessageCoverage
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactionTurnState
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.PrimaryContextPreflight
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.ReasoningCapabilityRecorder
import io.mockk.clearMocks
import io.mockk.coEvery
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

    /** Mocked reasoning-capability recorder invoked after each assistant response. */
    protected lateinit var reasoningCapabilityRecorder: ReasoningCapabilityRecorder

    /** Mocked compaction policy returning a disabled preflight so existing behavior stays unchanged. */
    protected lateinit var conversationCompactionService: ConversationCompactionService

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
        reasoningCapabilityRecorder = mockk()
        conversationCompactionService = mockk()
        // Recording is a no-op by default so tests not focused on reasoning do not need explicit stubs;
        // dedicated tests verify the recorder is invoked with the expected model and reasoning items.
        coEvery { reasoningCapabilityRecorder.record(any(), any()) } returns Unit
        // Compaction is disabled by default: beginTurn yields a Disabled state carrying the initial
        // units the orchestrator handed over, and every preflight returns the original flattened
        // window with no persisted chunk.
        coEvery { conversationCompactionService.beginTurn(any(), any(), any()) } coAnswers {
            CompactionTurnState.Disabled(
                testSession.id,
                thirdArg<List<ConversationContextUnit>>().toMutableList()
            ).right()
        }
        coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } coAnswers {
            PrimaryContextPreflight(
                primaryMessages = firstArg<CompactionTurnState>().units.flatMap { it.rawMessages },
                persistedChunkIfAny = null
            ).right()
        }

        orchestrator = DefaultConversationTurnOrchestrator(
            llmApiClient = llmApiClient,
            toolCallOrchestrator = toolCallOrchestrator,
            toolResultContentBuilder = DefaultToolResultContentBuilder(),
            chatContextBuilder = DefaultChatContextBuilder(
                fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
                toolResultContentBuilder = DefaultToolResultContentBuilder()
            ),
            conversationTurnPersistence = conversationTurnPersistence,
            reasoningCapabilityRecorder = reasoningCapabilityRecorder,
            conversationCompactionService = conversationCompactionService
        )
    }

    /**
     * Clears mocks after each test run.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(
            llmApiClient,
            toolCallOrchestrator,
            conversationTurnPersistence,
            reasoningCapabilityRecorder,
            conversationCompactionService
        )
    }

    /**
     * Builds a fake persisted compaction chunk for tests that verify the compaction-completed event.
     *
     * The chunk mirrors the shape the production service returns from `preparePrimaryContext` but is
     * fully deterministic so event assertions can check ids, coverage, and token counts.
     *
     * @param id Primary key of the fake chunk.
     * @return A fake persisted [ConversationCompactionChunk].
     */
    protected fun compactionChunk(id: Long = 55L): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = testSession.id,
        summary = "Summarized conversation",
        modelId = 1L,
        settingsId = 1L,
        providerId = 1L,
        modelName = "gpt-4o-mini",
        settingsName = "Default",
        providerName = "OpenAI",
        instruction = "Summarize",
        thresholdTokens = 100_000L,
        sourceTokenCount = 450L,
        resultTokenCount = 150L,
        tokenCounterVersion = "test-v1",
        coverageCount = 3,
        createdAt = baseInstant.toEpochMilliseconds(),
        coverage = listOf(
            CompactedMessageCoverage(ordinal = 0, messageId = 1L, observedUpdatedAt = baseInstant),
            CompactedMessageCoverage(ordinal = 1, messageId = 2L, observedUpdatedAt = baseInstant),
            CompactedMessageCoverage(ordinal = 2, messageId = 3L, observedUpdatedAt = baseInstant)
        )
    )
}
