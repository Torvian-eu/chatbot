package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.right
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Shared fixture for the [DefaultConversationCompactionService] rolling-window policy tests.
 *
 * Holds the model/provider/settings fixtures, the mocked collaborators and the stub builders used by
 * the automatic-path and forced-path suites. It declares no test cases of its own.
 */
abstract class DefaultConversationCompactionServiceTestFixture {

    protected val t0 = Instant.fromEpochMilliseconds(1_000L)
    protected val t1 = Instant.fromEpochMilliseconds(1_001L)
    protected val t2 = Instant.fromEpochMilliseconds(1_002L)
    protected val t3 = Instant.fromEpochMilliseconds(1_003L)

    protected val model = LLMModel(id = 1L, name = "gpt-4o", providerId = 1L, active = true)
    protected val provider = LLMProvider(
        id = 1L, apiKeyId = null, name = "OpenAI", description = "OpenAI",
        baseUrl = "https://api.openai.com/v1", type = LLMProviderType.OPENAI
    )
    protected val settings = ChatModelSettings(id = 1L, modelId = 1L, name = "Default", stream = false)
    protected val primaryConfig = LLMConfig(provider, model, settings, apiKey = null)

    protected val effectiveSettings = EffectiveCompactionSettings(
        modelId = 1L,
        settingsId = 1L,
        instruction = "Summarize faithfully",
        systemMessage = null,
        summaryLabel = ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
        thresholdTokens = 1_000L
    )

    /**
     * The effective compaction configuration of a session whose preset keeps its defaults: compaction
     * is enabled with the settings' own threshold. Used by every case that is not about the resolved
     * threshold itself.
     */
    protected val defaultResolvedCompaction = enabledUsable(effectiveSettings)

    /** A resolved configuration whose auxiliary configuration is unusable while automatic is disabled. */
    protected val unusableDisabled = ResolvedCompactionConfig.Unusable(
        reason = "no usable auxiliary configuration",
        automaticCompactionEnabled = false
    )

    /**
     * A resolved configuration carrying the given usable settings, with automatic compaction enabled.
     *
     * @param settings The effective compaction settings of the turn.
     */
    protected fun enabledUsable(settings: EffectiveCompactionSettings) =
        ResolvedCompactionConfig.Usable(settings = settings, automaticCompactionEnabled = true)

    /**
     * A resolved configuration carrying the given usable settings with automatic compaction disabled.
     *
     * @param settings The effective compaction settings of the turn.
     */
    protected fun disabledUsable(settings: EffectiveCompactionSettings) =
        ResolvedCompactionConfig.Usable(settings = settings, automaticCompactionEnabled = false)

    protected val chunkDao = mockk<ConversationCompactionChunkDao>()
    protected val auxiliaryConfigResolver = mockk<AuxiliaryCompactionConfigResolver>()
    protected val tokenCounter = mockk<ChatInputTokenCounter>()
    protected val llmApiClient = mockk<LLMApiClient>()

    /** All collaborators are mocked, so no DB is touched. */
    protected fun service(auxiliaryTimeout: Duration = 180.seconds) = DefaultConversationCompactionService(
        chunkDao = chunkDao,
        auxiliaryConfigResolver = auxiliaryConfigResolver,
        tokenCounter = tokenCounter,
        summarizer = AuxiliaryCompactionSummarizer(
            llmApiClient = llmApiClient,
            auxiliaryTimeout = auxiliaryTimeout
        )
    )

    /**
     * Builds a context with one user unit per message id using the given (id, updatedAt) pairs.
     */
    protected fun contextOf(vararg snapshots: Pair<Long, Instant>): ConversationContext = ConversationContext(
        snapshots.map { (id, updatedAt) ->
            ConversationContextUnit(
                source = SourceMessageSnapshot(id, updatedAt),
                rawMessages = listOf(RawChatMessage.User("m$id"))
            )
        }
    )

    /**
     * A counter mock deriving a deterministic count from the messages: every non-summary message
     * counts [unitTokens] and every labeled summary message counts [summaryTokens], mirroring the
     * authoritative counter's whole-input semantics.
     */
    protected fun stubCounter(
        unitTokens: Long = 5_000L,
        summaryTokens: Long = 50L,
        summaryLabel: String = effectiveSettings.summaryLabel
    ) {
        coEvery { tokenCounter.countPrimaryInput(any(), any(), any(), any(), any(), any()) } answers {
            val messages = arg<List<RawChatMessage>>(4)
            val tokens = messages.sumOf { message ->
                if (message.content?.startsWith(summaryLabel) == true) summaryTokens else unitTokens
            }
            tokens.right()
        }
        // The persisted chunk records the counter version for provenance.
        every { tokenCounter.version } returns "test-fixed-v1"
    }

    /**
     * Stubs the retained-chunk read of an active turn to an empty list; unusable turns never read
     * chunks, so a stub set here goes unused for them.
     */
    protected fun stubRetainedChunks() {
        coEvery { chunkDao.getChunksBySessionId(any()) } returns emptyList()
    }

    /**
     * Stubs a successful auxiliary resolution for the given effective settings.
     *
     * @param completion Completion the auxiliary client returns.
     * @param compactionSettings Settings the service must resolve; defaults to the turn's effective
     *            settings.
     */
    protected fun stubAuxiliarySuccess(
        completion: LLMCompletionResult = completionWith("A concise summary."),
        compactionSettings: EffectiveCompactionSettings = effectiveSettings
    ) {
        coEvery { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, compactionSettings) } returns primaryConfig.right()
        coEvery {
            llmApiClient.completeChat(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns completion.right()
    }

    /**
     * Builds a canned completion result with the given content.
     */
    protected fun completionWith(content: String): LLMCompletionResult = LLMCompletionResult(
        id = "c1",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = content,
                finishReason = "stop",
                index = 0
            )
        ),
        usage = UsageStats(1, 1, 2)
    )

    /**
     * Builds a canned persisted chunk for assertion purposes.
     */
    protected fun persistedChunk(id: Long): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = 7L,
        summary = "A concise summary.",
        modelId = 1L,
        settingsId = 1L,
        providerId = 1L,
        modelName = "gpt-4o",
        settingsName = "Default",
        providerName = "OpenAI",
        instruction = "Summarize faithfully",
        thresholdTokens = 1_000L,
        sourceTokenCount = 5_000L,
        resultTokenCount = 50L,
        tokenCounterVersion = "approx_utf16_json_v1",
        coverageCount = 2,
        createdAt = 9_000L,
        coverage = listOf(
            CompactedMessageCoverage(0, 1L, t0),
            CompactedMessageCoverage(1, 2L, t1)
        )
    )

    /**
     * Builds a retained chunk for window-init tests.
     */
    protected fun chunkOf(
        id: Long,
        createdAt: Long,
        coverage: List<CompactedMessageCoverage>
    ): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = 7L,
        summary = "prior summary $id",
        modelId = 1L,
        settingsId = 1L,
        providerId = 1L,
        modelName = "gpt-4o",
        settingsName = "Default",
        providerName = "OpenAI",
        instruction = "Summarize faithfully",
        thresholdTokens = 1_000L,
        sourceTokenCount = 4_000L,
        resultTokenCount = 40L,
        tokenCounterVersion = "approx_utf16_json_v1",
        coverageCount = coverage.size,
        createdAt = createdAt,
        coverage = coverage
    )
}
