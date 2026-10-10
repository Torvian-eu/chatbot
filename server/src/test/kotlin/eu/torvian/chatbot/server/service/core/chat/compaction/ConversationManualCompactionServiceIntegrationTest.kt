package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.preparation.PreparedConversationTurn
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end test of the user-requested compaction against a real Exposed database.
 *
 * The session runtime is resolved by a stub that delegates to the real session DAO and the production
 * compaction-configuration resolver, so the test exercises the real thread reconstruction, eligibility
 * selection, forced summarization, verified insert and the subsequent turn's forced-chunk reuse
 * without booting the whole turn-preparation stack. Cases cover the below-threshold manual chunk, the
 * fully covered no-op, a summary that is not smaller than the thread it replaces, and the superseding
 * re-compaction after a message is appended.
 */
class ConversationManualCompactionServiceIntegrationTest {

    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var userPreferenceDao: UserPreferenceDao
    private lateinit var modelPresetDao: ModelPresetDao
    private lateinit var chunkDao: ConversationCompactionChunkDao
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao

    private val session = TestDefaults.chatSession1.copy(agentRoleId = null, groupId = null)
    private val t = TestDefaults.DEFAULT_INSTANT

    private val m1 =
        TestDefaults.chatMessage1.copy(sessionId = session.id, parentMessageId = null, childrenMessageIds = listOf(2L))
    private val m2 =
        TestDefaults.chatMessage2.copy(sessionId = session.id, parentMessageId = 1L, childrenMessageIds = listOf(3L))
    private val m3 = ChatMessage.UserMessage(
        id = 3L,
        sessionId = session.id,
        content = "Third message",
        createdAt = t,
        updatedAt = t,
        parentMessageId = 2L,
        childrenMessageIds = listOf(4L)
    )

    /** Appended after the first compaction to exercise the superseding re-compaction. */
    private val m4 = ChatMessage.UserMessage(
        id = 4L,
        sessionId = session.id,
        content = "Fourth message",
        createdAt = t,
        updatedAt = t,
        parentMessageId = 3L,
        childrenMessageIds = emptyList()
    )

    private val provider = TestDefaults.llmProvider1.copy(apiKeyId = null)
    private val model = TestDefaults.llmModel1.copy(providerId = provider.id)
    private val settings = TestDefaults.modelSettings1.copy(modelId = model.id, stream = false)
    private val primaryConfig = LLMConfig(
        provider = provider,
        model = model,
        settings = settings,
        apiKey = null,
        tools = null
    )

    private val compactionSettings = EffectiveCompactionSettings(
        modelId = model.id,
        settingsId = settings.id,
        instruction = "Summarize faithfully",
        systemMessage = null,
        summaryLabel = ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
        thresholdTokens = 1_000L
    )

    /** User requesting the compaction; also the owner of the preference and the preset. */
    private val userId = TestDefaults.user1.id

    /**
     * Deterministic fake counter: 150 tokens per message, so a three-message thread counts 450 and a
     * one-message window counts 150. A 1,000-token threshold therefore keeps a summary sufficient.
     */
    private object FixedTokenCounter : ChatInputTokenCounter {
        override val version: String = "test-fixed-v1"

        override fun countPrimaryInput(
            model: LLMModel,
            provider: LLMProvider,
            settings: ModelSettings,
            systemMessage: String?,
            messages: List<RawChatMessage>,
            tools: List<ToolDefinition>?
        ): Either<ConversationCompactionError, Long> = (150L * messages.size).right()
    }

    /**
     * Fake counter that charges one token per content character, so the fixture's short messages are
     * cheap and the much longer summary the stub returns is not smaller than the thread it replaces.
     */
    private object ContentLengthTokenCounter : ChatInputTokenCounter {
        override val version: String = "test-content-length-v1"

        override fun countPrimaryInput(
            model: LLMModel,
            provider: LLMProvider,
            settings: ModelSettings,
            systemMessage: String?,
            messages: List<RawChatMessage>,
            tools: List<ToolDefinition>?
        ): Either<ConversationCompactionError, Long> =
            messages.sumOf { message -> (message.content?.length ?: 0).toLong() }.right()
    }

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        userPreferenceDao = container.get()
        modelPresetDao = container.get()
        chunkDao = container.get()
        messageDao = container.get()
        sessionDao = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
                Table.CHAT_GROUPS,
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.MODEL_SETTINGS,
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.MODEL_PRESETS,
                Table.MODEL_PRESET_OWNERS,
                Table.SESSION_CURRENT_LEAF,
                Table.TOOL_CALLS,
                Table.CONVERSATION_COMPACTION_CHUNKS,
                Table.CONVERSATION_COMPACTION_CHUNK_MESSAGES
            )
        )
        testDataManager.insertUser(TestDefaults.user1)
        testDataManager.insertLLMProvider(provider)
        testDataManager.insertLLMModel(model)
        testDataManager.insertModelSettings(settings)
        testDataManager.insertModelPreset(TestDefaults.modelPreset1)
        testDataManager.insertModelPresetOwnership(TestDefaults.modelPreset1.id, userId)
        testDataManager.insertChatSession(session)
        testDataManager.insertChatMessage(m1)
        testDataManager.insertChatMessage(m2)
        testDataManager.insertChatMessage(m3)
        testDataManager.insertSessionCurrentLeaf(SessionCurrentLeafEntity(sessionId = session.id, messageId = m3.id))
        storePreference()
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /** Stores the preference row the tests resolve their effective compaction configuration from. */
    private suspend fun storePreference(thresholdTokens: Long = 1_000L) {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        userPreferenceDao.upsertPreference(
            userId = userId,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.CONVERSATION_COMPACTION,
            value = json.encodeToString(
                ConversationCompactionPreference.serializer(),
                ConversationCompactionPreference(
                    modelId = model.id,
                    settingsId = settings.id,
                    instruction = "Summarize faithfully",
                    thresholdTokens = thresholdTokens
                )
            )
        )
    }

    /**
     * Builds the service under test with the real DAOs, the real context builder and the real
     * compaction policy, and a counting auxiliary client.
     *
     * @param llmApiClient Auxiliary client answering the summarization call.
     * @param tokenCounter Fake counter deciding the window and summary sizes.
     */
    private fun manualService(
        llmApiClient: LLMApiClient = LLMApiClientStub(),
        tokenCounter: ChatInputTokenCounter = FixedTokenCounter
    ): ConversationManualCompactionService =
        DefaultConversationManualCompactionService(
            preparationService = StubSessionRuntimePreparation(),
            conversationTurnPersistence = container.get(),
            chatContextBuilder = DefaultChatContextBuilder(
                fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
                toolResultContentBuilder = DefaultToolResultContentBuilder()
            ),
            chunkDao = chunkDao,
            compactionService = compactionService(llmApiClient, tokenCounter)
        )

    /**
     * Builds the automatic compaction policy over the real chunk DAO and a counting auxiliary client.
     *
     * @param llmApiClient Auxiliary client answering the summarization call.
     * @param tokenCounter Fake counter deciding the window and summary sizes.
     */
    private fun compactionService(
        llmApiClient: LLMApiClient,
        tokenCounter: ChatInputTokenCounter = FixedTokenCounter
    ): ConversationCompactionService {
        // The effective settings carry the auxiliary ids; capture the fixture's expected pair before the
        // anonymous resolver shadows the outer `settings` property with its own parameter.
        val expectedModelId = model.id
        val expectedSettingsId = settings.id
        return DefaultConversationCompactionService(
            chunkDao = chunkDao,
            auxiliaryConfigResolver = object : AuxiliaryCompactionConfigResolver {
                override suspend fun resolveAuxiliaryConfig(
                    userId: Long,
                    settings: EffectiveCompactionSettings
                ): Either<ConversationCompactionError, LLMConfig> =
                    // Mirror the production row lookup so settings pointing at rows that do not exist
                    // fail resolution exactly like a deleted model/settings would.
                    if (settings.modelId != expectedModelId || settings.settingsId != expectedSettingsId) {
                        ConversationCompactionError.InvalidConfiguration(
                            "Compaction settings ${settings.modelId}/${settings.settingsId} not found"
                        ).left()
                    } else {
                        primaryConfig.right()
                    }
            },
            tokenCounter = tokenCounter,
            llmApiClient = llmApiClient
        )
    }

    /** Builds the identity context for the thread ending at the given message using the real builder. */
    private suspend fun contextEndingAt(messageId: Long) = DefaultChatContextBuilder(
        fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
        toolResultContentBuilder = DefaultToolResultContentBuilder()
    ).buildContext(
        startingMessageId = messageId,
        sessionMessages = messageDao.getMessagesBySessionId(session.id),
        toolCalls = emptyList()
    )

    /**
     * Resolves the session runtime the way turn preparation would, without booting the whole
     * turn-preparation stack: the real session read plus the production compaction-configuration
     * resolver.
     */
    private inner class StubSessionRuntimePreparation : ConversationTurnPreparationService {

        override suspend fun prepareNewMessageTurn(
            userId: Long,
            sessionId: Long,
            content: String?,
            parentMessageId: Long?,
            isStreaming: Boolean
        ): Either<ValidateNewMessageError, PreparedConversationTurn> = error("A manual compaction starts no turn")

        override suspend fun prepareSessionRuntime(
            userId: Long,
            sessionId: Long
        ): Either<ValidateNewMessageError, PreparedConversationTurn> = either {
            val loadedSession = withError({ error: SessionError.SessionNotFound ->
                ValidateNewMessageError.SessionNotFound(error.id)
            }) {
                sessionDao.getSessionById(sessionId).bind()
            }
            // The resolver never fails: an unusable configuration is a value the manual path reports
            // itself, so it cannot be mapped to a preparation error here.
            val resolved = DefaultEffectiveCompactionConfigResolver(
                modelPresetDao = modelPresetDao,
                userPreferenceDao = userPreferenceDao,
                json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
            ).resolve(userId, TestDefaults.modelPreset1.id)
            PreparedConversationTurn(
                session = loadedSession,
                llmConfig = primaryConfig,
                resolvedCompaction = resolved
            )
        }
    }

    @Test
    fun `a manual compaction below the threshold persists a chunk the next turn uses`() = runTest {
        val outcome = manualService().compactThread(userId, session.id).getOrNull()

        val persisted = assertIs<ManualCompactionOutcome.Persisted>(outcome)
        assertEquals(listOf(1L, 2L, 3L), persisted.chunk.coverage.map { it.messageId })
        assertEquals(listOf(t, t, t), persisted.chunk.coverage.map { it.observedUpdatedAt })
        assertEquals(450L, persisted.chunk.sourceTokenCount)
        assertEquals(150L, persisted.chunk.resultTokenCount)
        // No transcript row was created or changed.
        assertEquals(listOf(1L, 2L, 3L), messageDao.getMessagesBySessionId(session.id).map { it.id })

        // The next turn on the same thread uses the persisted summary alone, without summarizing again.
        val countingClient = CountingCompactionClient()
        val turnService = compactionService(countingClient)
        val state = turnService.beginTurn(
            userId = userId,
            sessionId = session.id,
            initialUnits = contextEndingAt(m3.id).units,
            resolvedCompaction = ResolvedCompactionConfig.Usable(
                settings = compactionSettings,
                automaticCompactionEnabled = true
            )
        )
        val preflight = assertNotNull(
            turnService.preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = m3.id).getOrNull()
        )

        assertEquals(1, preflight.primaryMessages.size)
        assertEquals(
            compactionSettings.summaryLabel + persisted.chunk.summary,
            (preflight.primaryMessages.single() as RawChatMessage.User).content
        )
        assertNull(preflight.persistedChunkIfAny)
        assertEquals(0, countingClient.completeChatCalls)
        assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)
    }

    @Test
    fun `a summary that does not shrink the thread is skipped and persists nothing`() = runTest {
        // One token per content character, and a deliberately verbose summary: the generated text is
        // longer than every message of the short thread it would replace, so the reduction rule refuses
        // to trade faithful raw content for a bigger, lossy equivalent.
        val countingClient = CountingCompactionClient(VerboseSummaryClient())
        val service = manualService(countingClient, ContentLengthTokenCounter)

        val outcome = service.compactThread(userId, session.id).getOrNull()

        assertEquals(ManualCompactionOutcome.Skipped(CompactionSkipReason.SUMMARY_NOT_SMALLER), outcome)
        assertEquals(1, countingClient.completeChatCalls, "The summary was generated before being rejected")
        assertEquals(0, chunkDao.getChunksBySessionId(session.id).size)
        // The thread is untouched: the skip is not a transcript operation either.
        assertEquals(listOf(1L, 2L, 3L), messageDao.getMessagesBySessionId(session.id).map { it.id })
    }

    @Test
    fun `a second manual trigger on a fully covered thread is a no-op`() = runTest {
        val countingClient = CountingCompactionClient()
        val service = manualService(countingClient)
        assertIs<ManualCompactionOutcome.Persisted>(service.compactThread(userId, session.id).getOrNull())

        val second = service.compactThread(userId, session.id).getOrNull()

        assertEquals(ManualCompactionOutcome.Skipped(CompactionSkipReason.ALREADY_COMPACTED), second)
        assertEquals(1, countingClient.completeChatCalls, "The no-op must not summarize again")
        assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)
    }

    @Test
    fun `appending a message re-summarizes the summary plus the tail into a superseding chunk`() = runTest {
        val countingClient = CountingCompactionClient()
        val service = manualService(countingClient)
        val first = assertIs<ManualCompactionOutcome.Persisted>(
            service.compactThread(userId, session.id).getOrNull()
        )

        testDataManager.insertChatMessage(m4)
        sessionDao.updateSessionLeafMessageId(session.id, m4.id)

        val second = assertIs<ManualCompactionOutcome.Persisted>(
            service.compactThread(userId, session.id).getOrNull()
        )

        // The second input is the prior summary plus the uncovered tail, with the instruction appended.
        val secondInput = countingClient.inputs[1]
        assertEquals(3, secondInput.size)
        assertEquals(
            compactionSettings.summaryLabel + first.chunk.summary,
            (secondInput[0] as RawChatMessage.User).content
        )
        assertEquals(m4.content, (secondInput[1] as RawChatMessage.User).content)
        assertEquals("Summarize faithfully", (secondInput[2] as RawChatMessage.User).content)

        // The new chunk drops nothing: coverage is the cumulative root-to-leaf identity list.
        assertEquals(listOf(1L, 2L, 3L, 4L), second.chunk.coverage.map { it.messageId })
        val chunks = chunkDao.getChunksBySessionId(session.id)
        assertEquals(2, chunks.size, "The superseded chunk must be retained")
        assertEquals(second.chunk.id, chunks.maxBy { it.id }.id)
    }
}

/**
 * [LLMApiClient] delegating to a stub while counting non-streaming completions and recording their
 * inputs, so a test can assert both that no auxiliary call happened and what it was asked to summarize.
 *
 * @property delegate Client that answers the actual calls.
 */
private class CountingCompactionClient(
    private val delegate: LLMApiClient = LLMApiClientStub()
) : LLMApiClient by delegate {
    /** Number of non-streaming completions requested through this client. */
    var completeChatCalls: Int = 0
        private set

    /** Message lists of the non-streaming completions requested through this client, in order. */
    val inputs: MutableList<List<RawChatMessage>> = mutableListOf()

    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Either<LLMCompletionError, LLMCompletionResult> {
        completeChatCalls++
        inputs.add(messages)
        return delegate.completeChat(messages, modelConfig, provider, settings, apiKey, tools, systemMessage)
    }
}

/**
 * [LLMApiClient] whose completions are deliberately verbose, standing in for a model that restates the
 * conversation at least as long as the conversation itself.
 *
 * @property delegate Client answering the methods this client does not override.
 */
private class VerboseSummaryClient(
    private val delegate: LLMApiClient = LLMApiClientStub()
) : LLMApiClient by delegate {

    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Either<LLMCompletionError, LLMCompletionResult> = LLMCompletionResult(
        id = "verbose-completion",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = "The conversation begins with a question and continues from there. ".repeat(5).trim(),
                finishReason = "stop",
                index = 0
            )
        ),
        usage = UsageStats(inputTokens = 10, outputTokens = 60, totalTokens = 70)
    ).right()
}
