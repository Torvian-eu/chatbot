package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
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
import kotlin.test.assertTrue

/**
 * End-to-end service test with a real Exposed database and the stubbed LLM client.
 *
 * Covers the rolling-window flows against real chunk persistence: window init with an eligible chunk
 * (summary + delta, ledger seeded), one-shot compaction persisting a prefix chunk whose coverage
 * equals the tracked ledger, hybrid context reuse within the threshold, summary-alone window after
 * compaction, `InsufficientReduction`, the disabled-preference (`enabled = false`) path that sends
 * the full thread with no chunk, the null model/settings reference path (error only when compaction
 * is required, pass-through while it fits), edit invalidation with a replacement chunk (old rows
 * retained),
 * branch exclusion (inside range invalidates, at/after range keeps eligible), the fit-sends-originals
 * rule, and the guarantee that no synthetic summary is persisted into the visible transcript.
 */
class ConversationCompactionServiceIntegrationTest {

    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var userPreferenceDao: UserPreferenceDao
    private lateinit var chunkDao: ConversationCompactionChunkDao
    private lateinit var messageDao: MessageDao

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
    private val auxiliaryConfig = LLMConfig(
        provider = provider,
        model = model,
        settings = settings,
        apiKey = null,
        tools = null
        // systemMessage stays empty in this stub: the integration fixtures do not set a preference
        // system prompt, and the compaction instruction is appended as the final user message.
    )

    /**
     * Deterministic fake counter: 150 tokens per message. A 3-message thread counts 450, a 2-message
     * branch counts 300, a one-summary primary counts 150. Thresholds below those values therefore
     * trigger compaction while a single summary always fits a 200-token threshold.
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

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        userPreferenceDao = container.get()
        chunkDao = container.get()
        messageDao = container.get()

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
                Table.CONVERSATION_COMPACTION_CHUNKS,
                Table.CONVERSATION_COMPACTION_CHUNK_MESSAGES
            )
        )
        testDataManager.insertUser(TestDefaults.user1)
        testDataManager.insertLLMProvider(provider)
        testDataManager.insertLLMModel(model)
        testDataManager.insertModelSettings(settings)
        testDataManager.insertChatSession(session)
        testDataManager.insertChatMessage(m1)
        testDataManager.insertChatMessage(m2)
        testDataManager.insertChatMessage(m3)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Builds the runtime service against the real DAOs with a mocked configuration resolver and the
     * stubbed LLM client.
     */
    private suspend fun service(preference: ConversationCompactionPreference): DefaultConversationCompactionService {
        userPreferenceDao.upsertPreference(
            userId = TestDefaults.user1.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.CONVERSATION_COMPACTION,
            value = Json.encodeToString(ConversationCompactionPreference.serializer(), preference)
        )
        val configurationResolver = object : ConversationCompactionConfigurationResolver {
            override suspend fun resolveAuxiliaryConfig(
                userId: Long,
                preference: ConversationCompactionPreference
            ): Either<ConversationCompactionError, LLMConfig> =
                // Mirror the production resolver's null-reference guard so a preference whose
                // model/settings rows were deleted fails resolution exactly like in production.
                if (preference.modelId == null || preference.settingsId == null) {
                    ConversationCompactionError.InvalidConfiguration(
                        "Compaction modelId/settingsId is not set"
                    ).left()
                } else {
                    auxiliaryConfig.right()
                }
        }
        val llmApiClient: LLMApiClient = LLMApiClientStub()
        return DefaultConversationCompactionService(
            userPreferenceDao = userPreferenceDao,
            chunkDao = chunkDao,
            configurationResolver = configurationResolver,
            tokenCounter = FixedTokenCounter,
            llmApiClient = llmApiClient,
            json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        )
    }

    /**
     * Builds the identity context for the thread ending at the given message using the real builder.
     */
    private suspend fun contextEndingAt(messageId: Long) = DefaultChatContextBuilder(
        fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
        toolResultContentBuilder = DefaultToolResultContentBuilder()
    ).buildContext(
        startingMessageId = messageId,
        sessionMessages = messageDao.getMessagesBySessionId(session.id),
        toolCalls = emptyList()
    )

    @Test
    fun `oversized initial thread is compacted to one synthetic summary and persisted`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 200L
            )
        )
        val state = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        val preflight =
            service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)
                .getOrNull()
        assertNotNull(preflight)
        assertEquals(1, preflight.primaryMessages.size)
        assertTrue(
            (preflight.primaryMessages.single() as RawChatMessage.User).content.startsWith(
                ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL
            )
        )
        assertNotNull(preflight.persistedChunkIfAny)

        val chunks = chunkDao.getChunksBySessionId(session.id)
        assertEquals(1, chunks.size)
        assertEquals(listOf(1L, 2L, 3L), chunks.single().coverage.map { it.messageId })
        assertEquals(listOf(0, 1, 2), chunks.single().coverage.map { it.ordinal })
        // Provenance and instruction snapshot are persisted for reproducibility.
        assertEquals("Summarize faithfully", chunks.single().instruction)
        assertEquals("test-fixed-v1", chunks.single().tokenCounterVersion)
    }

    @Test
    fun `disabled preference sends the full thread and persists nothing`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 200L,
                enabled = false
            )
        )
        val state =
            service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        assertIs<CompactionTurnState.Disabled>(state)

        val preflight = service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)
            .getOrNull()
        assertNotNull(preflight)
        // The full thread is sent unchanged even though it exceeds the 200-token threshold: the
        // `enabled = false` flag disables compaction exactly like an absent preference row.
        assertEquals(3, preflight.primaryMessages.size)
        assertNull(preflight.persistedChunkIfAny)
        assertTrue(chunkDao.getChunksBySessionId(session.id).isEmpty())
    }

    @Test
    fun `preference referencing deleted model and settings raises invalid configuration only when compaction is required`() =
        runTest {
            // The stored preference still exists but its model/settings rows were deleted (null ids).
            // It stays enabled: once the 3-message window (450 tokens) exceeds the 200-token threshold
            // the resolver says the compactor is unresolvable instead of compacting.
            val service = service(
                ConversationCompactionPreference(
                    modelId = null,
                    settingsId = null,
                    instruction = "Summarize faithfully",
                    thresholdTokens = 200L
                )
            )
            val state =
                service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
            assertIs<CompactionTurnState.Enabled>(state)

            val result = service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)
            assertIs<ConversationCompactionError.InvalidConfiguration>(result.leftOrNull())
            assertTrue(chunkDao.getChunksBySessionId(session.id).isEmpty())
        }

    @Test
    fun `preference referencing deleted model and settings passes through while the thread fits`() = runTest {
        // Same null-reference preference, but with a threshold above the whole thread (450 tokens):
        // the fit-sends-originals rule applies and no configuration error is raised.
        val service = service(
            ConversationCompactionPreference(
                modelId = null,
                settingsId = null,
                instruction = "Summarize faithfully",
                thresholdTokens = 1_000L
            )
        )
        val state =
            service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        assertIs<CompactionTurnState.Enabled>(state)

        val preflight = service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(3, preflight.primaryMessages.size)
        assertNull(preflight.persistedChunkIfAny)
        assertTrue(chunkDao.getChunksBySessionId(session.id).isEmpty())
    }

    @Test
    fun `window init with an eligible chunk seeds the ledger and the next compaction extends coverage`() =
        runTest {
            val service = service(
                ConversationCompactionPreference(
                    modelId = model.id,
                    settingsId = settings.id,
                    instruction = "Summarize faithfully",
                    thresholdTokens = 200L
                )
            )
            val firstState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
            service.preparePrimaryContext(firstState, auxiliaryConfig, expectedLeafMessageId = m3.id)
            assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)

            // The thread grows past the previously compacted prefix.
            testDataManager.insertChatMessage(m4)

            val secondState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m4.id).units).getOrNull()!!
            val preflight = service.preparePrimaryContext(secondState, auxiliaryConfig, expectedLeafMessageId = m4.id)
                .getOrNull()
            assertNotNull(preflight)
            // The window after the second compaction is the summary alone.
            assertEquals(1, preflight.primaryMessages.size)

            val chunks = chunkDao.getChunksBySessionId(session.id)
            assertEquals(2, chunks.size, "The superseded prefix chunk must be retained")
            val newest = chunks.maxBy { it.createdAt }
            // The newest chunk's coverage equals the tracked ledger: prior prefix + the new leaf.
            assertEquals(listOf(1L, 2L, 3L, 4L), newest.coverage.map { it.messageId })
            assertEquals(listOf(0, 1, 2, 3), newest.coverage.map { it.ordinal })
            assertEquals(t, newest.coverage.last().observedUpdatedAt)
        }

    @Test
    fun `hybrid context reuses the seeded summary and additional messages when the window fits`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 200L
            )
        )
        val firstState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        service.preparePrimaryContext(firstState, auxiliaryConfig, expectedLeafMessageId = m3.id)
        assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)

        // A continuation at/after the covered range keeps the prefix chunk eligible.
        testDataManager.insertChatMessage(m4)

        // With a 300-token threshold, [summary] + the new message (300) fits exactly.
        val fittingService = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 300L
            )
        )
        val hybridState = fittingService.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m4.id).units).getOrNull()!!
        val preflight = fittingService.preparePrimaryContext(hybridState, auxiliaryConfig, expectedLeafMessageId = m4.id)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(2, preflight.primaryMessages.size)
        assertTrue(
            (preflight.primaryMessages[0] as RawChatMessage.User).content.startsWith(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL)
        )
        assertEquals("Fourth message", (preflight.primaryMessages[1] as RawChatMessage.User).content)
        assertNull(preflight.persistedChunkIfAny)
        assertEquals(
            1,
            chunkDao.getChunksBySessionId(session.id).size,
            "A fitting hybrid window persists nothing"
        )
    }

    @Test
    fun `summary alone over the threshold raises insufficient reduction with no chunk`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 100L
            )
        )
        val state = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        val result = service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)

        val error = assertIs<ConversationCompactionError.InsufficientReduction>(result.leftOrNull())
        assertEquals(450L, error.sourceTokenCount)
        assertEquals(150L, error.resultTokenCount)
        assertEquals(100L, error.thresholdTokens)
        assertTrue(
            chunkDao.getChunksBySessionId(session.id).isEmpty(),
            "An insufficient reduction must not persist a chunk"
        )
    }

    @Test
    fun `edited covered message invalidates the old chunk and a replacement is persisted while the old row remains`() =
        runTest {
            val service = service(
                ConversationCompactionPreference(
                    modelId = model.id,
                    settingsId = settings.id,
                    instruction = "Summarize faithfully",
                    thresholdTokens = 200L
                )
            )
            val firstState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
            service.preparePrimaryContext(
                firstState,
                auxiliaryConfig,
                expectedLeafMessageId = m3.id
            )
            assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)

            // Edit the middle message: updatedAt changes, invalidating any chunk covering it.
            messageDao.updateMessageContent(m2.id, "Edited assistant content")

            val secondState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
            val preflight = service.preparePrimaryContext(
                secondState,
                auxiliaryConfig,
                expectedLeafMessageId = m3.id
            )
            assertNotNull(preflight.getOrNull())
            assertNotNull(preflight.getOrNull()!!.persistedChunkIfAny)

            val chunks = chunkDao.getChunksBySessionId(session.id)
            assertEquals(2, chunks.size, "The superseded old chunk must be retained alongside the replacement")
            val newest = chunks.maxBy { it.createdAt }
            // The replacement records the NEW observed timestamp for the edited message.
            val editedCoverage = newest.coverage.first { it.messageId == m2.id }
            val editedRow = messageDao.getMessageById(m2.id).getOrNull() as ChatMessage.AssistantMessage
            assertEquals(editedRow.updatedAt, editedCoverage.observedUpdatedAt)
        }

    @Test
    fun `branch excluding a covered message makes the chunk ineligible and creates a branch chunk`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 200L
            )
        )
        val fullState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        service.preparePrimaryContext(fullState, auxiliaryConfig, expectedLeafMessageId = m3.id)
        assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)

        // A branch that ends at m2 excludes m3; the full-thread chunk becomes ineligible and a new
        // branch chunk covering [1,2] is created when compaction is required.
        val branchState = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m2.id).units).getOrNull()!!
        val preflight = service.preparePrimaryContext(branchState, auxiliaryConfig, expectedLeafMessageId = m2.id)
        assertNotNull(preflight.getOrNull())
        val chunks = chunkDao.getChunksBySessionId(session.id)
        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.coverage.map { c -> c.messageId } == listOf(1L, 2L) })
        assertTrue(chunks.any { it.coverage.map { c -> c.messageId } == listOf(1L, 2L, 3L) })
    }

    @Test
    fun `full raw input that fits sends originals and does not persist a replacement for stale chunks`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 200L
            )
        )
        val state = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)
        assertEquals(1, chunkDao.getChunksBySessionId(session.id).size)

        // Raise the threshold so the full raw thread fits; originals are sent and no new chunk is created.
        val fittingService = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 1_000L
            )
        )
        val fitState = fittingService.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        val preflight = fittingService.preparePrimaryContext(
            fitState,
            auxiliaryConfig,
            expectedLeafMessageId = m3.id
        )
        val result = preflight.getOrNull()
        assertNotNull(result)
        assertNull(result.persistedChunkIfAny)
        assertEquals(contextEndingAt(m3.id).flatten(), result.primaryMessages)
        assertEquals(
            1,
            chunkDao.getChunksBySessionId(session.id).size,
            "No eager replacement chunk is created while the raw thread fits"
        )
    }

    @Test
    fun `original transcript stays intact with no synthetic summary after compaction`() = runTest {
        val service = service(
            ConversationCompactionPreference(
                modelId = model.id,
                settingsId = settings.id,
                instruction = "Summarize faithfully",
                thresholdTokens = 200L
            )
        )
        val state = service.beginTurn(TestDefaults.user1.id, session.id, contextEndingAt(m3.id).units).getOrNull()!!
        service.preparePrimaryContext(state, auxiliaryConfig, expectedLeafMessageId = m3.id)

        val messages = messageDao.getMessagesBySessionId(session.id)
        assertEquals(listOf(1L, 2L, 3L), messages.map { it.id })
        assertTrue(
            messages.none { it.content.contains(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL) },
            "The synthetic summary must never be persisted as a transcript message"
        )
    }
}
