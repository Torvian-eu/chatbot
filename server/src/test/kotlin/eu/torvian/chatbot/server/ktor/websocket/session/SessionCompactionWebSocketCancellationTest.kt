package eu.torvian.chatbot.server.ktor.websocket.session

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.compaction.ChatInputTokenCounter
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunkCandidate
import eu.torvian.chatbot.server.service.core.chat.compaction.AuxiliaryCompactionConfigResolver
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationManualCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.EffectiveCompactionSettings
import eu.torvian.chatbot.server.service.core.chat.compaction.ResolvedCompactionConfig
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.preparation.PreparedConversationTurn
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.llm.BlockingLLMApiClient
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies that a client leaving the compaction socket aborts the running compaction.
 *
 * The whole production chain is exercised — the WebSocket handler, the manual compaction service and
 * the shared one-shot policy — over a socket whose inbound channel is closed mid-compaction, which is
 * how Ktor signals that the peer is gone. The auxiliary call never returns, so the verified chunk
 * insert must never be reached and no terminal frame may be sent.
 */
class SessionCompactionWebSocketCancellationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val t = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val userId = 1L
    private val sessionId = 7L
    private val firstMessageId = 10L
    private val leafMessageId = 11L

    private val primaryConfig = LLMConfig(
        provider = TestDefaults.llmProvider1,
        model = TestDefaults.llmModel1,
        settings = TestDefaults.modelSettings1,
        apiKey = null
    )

    private val compactionSettings = EffectiveCompactionSettings(
        modelId = TestDefaults.llmModel1.id,
        settingsId = TestDefaults.modelSettings1.id,
        instruction = "Summarize faithfully",
        systemMessage = null,
        summaryLabel = ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
        thresholdTokens = 100_000L
    )

    private val preparationService = mockk<ConversationTurnPreparationService>()
    private val conversationTurnPersistence = mockk<ConversationTurnPersistence>()
    private val chatContextBuilder = mockk<ChatContextBuilder>()
    private val authorizationService = mockk<AuthorizationService>()

    /** Records the verified-insert calls the policy must never make while the peer is gone. */
    private val chunkDao = RecordingChunkDao()

    /** Auxiliary client whose completion never returns, standing in for a long-running summarization. */
    private val auxiliaryClient = BlockingLLMApiClient()

    /**
     * Builds the handler over the real manual compaction service and the real one-shot policy.
     *
     * @return The handler under test.
     */
    private fun handler(): SessionCompactionWebSocketHandler = SessionCompactionWebSocketHandler(
        manualCompactionService = DefaultConversationManualCompactionService(
            preparationService = preparationService,
            conversationTurnPersistence = conversationTurnPersistence,
            chatContextBuilder = chatContextBuilder,
            chunkDao = chunkDao,
            compactionService = DefaultConversationCompactionService(
                chunkDao = chunkDao,
                auxiliaryConfigResolver = object : AuxiliaryCompactionConfigResolver {
                    override suspend fun resolveAuxiliaryConfig(
                        userId: Long,
                        settings: EffectiveCompactionSettings
                    ): Either<ConversationCompactionError, LLMConfig> = primaryConfig.right()
                },
                tokenCounter = object : ChatInputTokenCounter {
                    override val version: String = "test-constant-v1"

                    override fun countPrimaryInput(
                        model: LLMModel,
                        provider: LLMProvider,
                        settings: ModelSettings,
                        systemMessage: String?,
                        messages: List<RawChatMessage>,
                        tools: List<ToolDefinition>?
                    ): Either<ConversationCompactionError, Long> = (100L * messages.size).right()
                },
                llmApiClient = auxiliaryClient
            )
        ),
        authorizationService = authorizationService,
        json = json
    )

    /**
     * Stubs one compactable two-message thread and grants session access to the caller.
     *
     * The counter charges 100 tokens per message, so the thread (200) would shrink to its summary (100)
     * and the compaction would persist a chunk if it were allowed to run to completion.
     */
    private fun stubCompactableThread() {
        coEvery { authorizationService.requireAccess(userId, ResourceType.SESSION, sessionId, AccessMode.WRITE) } returns
            Unit.right()
        coEvery { preparationService.prepareSessionRuntime(userId, sessionId) } returns PreparedConversationTurn(
            session = ChatSession(
                id = sessionId,
                name = "Session",
                createdAt = t,
                updatedAt = t,
                groupId = null,
                agentRoleId = 1L,
                currentLeafMessageId = leafMessageId,
                messages = listOf(
                    TestDefaults.chatMessage1.copy(id = firstMessageId, sessionId = sessionId),
                    TestDefaults.chatMessage1.copy(
                        id = leafMessageId,
                        sessionId = sessionId,
                        parentMessageId = firstMessageId
                    )
                )
            ),
            llmConfig = primaryConfig,
            resolvedCompaction = ResolvedCompactionConfig.Usable(
                settings = compactionSettings,
                automaticCompactionEnabled = true
            )
        ).right()
        coEvery { conversationTurnPersistence.loadSessionToolCalls(sessionId) } returns emptyList()
        every { chatContextBuilder.buildContext(leafMessageId, any(), any()) } returns ConversationContext(
            listOf(
                ConversationContextUnit(
                    source = SourceMessageSnapshot(firstMessageId, t),
                    rawMessages = listOf(RawChatMessage.User("Hello"))
                ),
                ConversationContextUnit(
                    source = SourceMessageSnapshot(leafMessageId, t),
                    rawMessages = listOf(RawChatMessage.User("And again"))
                )
            )
        )
    }

    @Test
    fun `closing the socket mid-compaction aborts the compaction and persists no chunk`() = runTest {
        stubCompactableThread()
        val (socket, outgoing, incoming) = socketHarness()

        val handlerJob = launch { handler().handle(socket, userId, sessionId) }
        // The compaction is in flight: the auxiliary call is suspended and nothing was persisted yet.
        auxiliaryClient.started.await()
        assertTrue(chunkDao.insertedCandidates.isEmpty())

        // The client goes away: Ktor completes the inbound channel, and only the handler's read of it
        // turns that into a cancellation of the running compaction.
        incoming.close()
        // Bounded wait: without an inbound read the handler would keep the compaction suspended forever.
        withTimeout(5.seconds) { handlerJob.join() }

        assertTrue(handlerJob.isCancelled, "A closed socket must cancel the compaction handler")
        assertTrue(auxiliaryClient.cancelled.isCompleted, "The auxiliary call must be aborted")
        assertTrue(chunkDao.insertedCandidates.isEmpty(), "No chunk may be persisted after the socket closed")
        assertTrue(drain(outgoing).isEmpty(), "A cancelled compaction sends no outcome frame")
    }

    /**
     * Builds a relaxed socket mock whose inbound and outbound channels are real.
     *
     * @return The socket with the channels it reads from and writes to.
     */
    private fun socketHarness(): SocketHarness {
        val outgoing = Channel<Frame>(Channel.UNLIMITED)
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val socket = mockk<DefaultWebSocketServerSession>(relaxed = true)
        every { socket.outgoing } returns outgoing
        every { socket.incoming } returns incoming
        return SocketHarness(socket, outgoing, incoming)
    }

    /**
     * Drains the text frames a socket sent, in order, ignoring any other frame kind.
     *
     * @param outgoing Channel the socket wrote to.
     * @return The decoded text-frame payloads.
     */
    private fun drain(outgoing: Channel<Frame>): List<String> {
        val payloads = mutableListOf<String>()
        while (true) {
            val frame = outgoing.tryReceive().getOrNull() ?: break
            (frame as? Frame.Text)?.let { textFrame -> payloads.add(textFrame.readText()) }
        }
        return payloads
    }
}

/** Socket mock plus the channels the handler under test reads from and writes to. */
private data class SocketHarness(
    /** Relaxed mock of a live Ktor WebSocket session. */
    val socket: DefaultWebSocketServerSession,
    /** Channel collecting the frames the handler sent. */
    val outgoing: Channel<Frame>,
    /** Channel standing in for the peer's inbound stream; closing it simulates the client leaving. */
    val incoming: Channel<Frame>
)

/** Verifies chunk persistence without touching a database. */
private class RecordingChunkDao : ConversationCompactionChunkDao {

    /** Candidates the policy tried to persist; the aborted compaction must leave this empty. */
    val insertedCandidates: MutableList<ConversationCompactionChunkCandidate> = mutableListOf()

    override suspend fun getChunksBySessionId(sessionId: Long): List<ConversationCompactionChunk> = emptyList()

    override suspend fun insertVerifiedChunk(
        candidate: ConversationCompactionChunkCandidate,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionChunkDaoError, ConversationCompactionChunk> {
        insertedCandidates.add(candidate)
        return ConversationCompactionChunkDaoError.PersistenceFailed("unexpected insert", cause = null).left()
    }
}
