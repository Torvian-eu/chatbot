package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.impl.session.SessionDetailsStore
import eu.torvian.chatbot.app.repository.impl.session.SessionEventApplier
import eu.torvian.chatbot.app.repository.impl.session.SessionListReconciler
import eu.torvian.chatbot.app.repository.impl.session.SessionSummaryStore
import eu.torvian.chatbot.app.repository.impl.session.ToolCallStore
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.CompactionCompletedPayload
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers the two halves of the live session ordering: the optimistic `updatedAt` bump a persisted turn message
 * produces, and the post-turn reconciliation with the server's authoritative list.
 *
 * Assertions are expressed through `SessionListData.groupedSessions`, i.e. the order the panel actually renders,
 * rather than through the raw cached list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListOrderingSyncTest {

    private val sessionId = 100L
    private val otherSessionId = 200L
    private val thirdSessionId = 300L
    private val groupId = 5L

    private val base = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val group = ChatGroup(id = groupId, name = "Group", createdAt = base)

    private lateinit var sessionApi: SessionApi
    private lateinit var chatApi: ChatApi

    /** The list half under test, sharing its collaborators with [conversationRepository]. */
    private lateinit var sessionRepository: DefaultSessionListRepository

    /**
     * The conversation half under test, sharing its collaborators with [sessionRepository].
     *
     * Both halves read one [SessionSummaryStore], one [SessionDetailsStore] and one [ToolCallStore], which is the
     * seam these cases cover: a turn raises the ordering instant in the very store the list is rendered from.
     */
    private lateinit var conversationRepository: DefaultConversationRepository

    @BeforeTest
    fun setup() {
        sessionApi = mockk()
        chatApi = mockk()
    }

    /**
     * Builds both halves over one shared set of collaborators, with [refreshScope] hosting the post-turn
     * reconciliation.
     *
     * @param refreshScope The scope the scheduled reconciliation runs in.
     */
    private fun repositories(refreshScope: CoroutineScope) {
        val details = SessionDetailsStore()
        val toolCalls = ToolCallStore()
        val summaryStore = SessionSummaryStore()
        sessionRepository = DefaultSessionListRepository(
            sessionApi = sessionApi,
            summaryStore = summaryStore,
            details = details
        )
        conversationRepository = DefaultConversationRepository(
            sessionApi = sessionApi,
            chatApi = chatApi,
            details = details,
            toolCalls = toolCalls,
            eventApplier = SessionEventApplier(details = details, toolCalls = toolCalls),
            summaryStore = summaryStore,
            reconciler = SessionListReconciler(sessionApi, summaryStore, refreshScope)
        )
    }

    /**
     * Builds a pair whose scheduled reconciliation is queued on a scheduler the case never advances, so the
     * case observes the order the optimistic bump alone produces.
     */
    private fun bumpOnlyRepositories() = repositories(CoroutineScope(StandardTestDispatcher()))

    /** Builds a pair whose scheduled reconciliation runs to completion within the surrounding test. */
    private fun reconcilingRepositories(testScheduler: TestCoroutineScheduler) =
        repositories(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    /** An instant [offsetMillis] after the fixed base instant of the case. */
    private fun at(offsetMillis: Long) = Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + offsetMillis)

    /** Builds the summary of a session of [groupId]. */
    private fun summary(id: Long, updatedAt: Instant, groupId: Long? = null) = ChatSessionSummary(
        id = id,
        name = "Session $id",
        createdAt = base,
        updatedAt = updatedAt,
        groupId = groupId
    )

    /** Builds a persisted user message carrying [updatedAt]. */
    private fun userMessage(updatedAt: Instant, id: Long = 1L) = ChatMessage.UserMessage(
        id = id,
        sessionId = sessionId,
        content = "Hello",
        createdAt = base,
        updatedAt = updatedAt,
        parentMessageId = null
    )

    /** Builds a persisted assistant message carrying [updatedAt]. */
    private fun assistantMessage(updatedAt: Instant, id: Long = 2L) = ChatMessage.AssistantMessage(
        id = id,
        sessionId = sessionId,
        content = "Answer",
        createdAt = base,
        updatedAt = updatedAt,
        parentMessageId = 1L,
        modelId = 1L,
        settingsId = 2L
    )

    /** Seeds the cached summary list through the regular load path. */
    private suspend fun seed(vararg sessions: ChatSessionSummary) {
        coEvery { sessionApi.getAllSessions() } returns Either.Right(sessions.toList())
        sessionRepository.loadSessions()
    }

    /** The cached summaries, failing the case when the list was never loaded. */
    private fun cachedSummaries(): List<ChatSessionSummary> =
        sessionRepository.sessions.value.dataOrNull ?: error("the session list was expected to be loaded")

    /** The cached summary of [sessionId], failing the case when it is absent. */
    private fun summaryOf(sessionId: Long): ChatSessionSummary =
        cachedSummaries().first { it.id == sessionId }

    /** The rendered order of the section [section] (`null` being the ungrouped section). */
    private fun orderIn(section: ChatGroup?): List<Long> =
        SessionListData(allSessions = cachedSummaries(), allGroups = listOf(group))
            .groupedSessions.getValue(section)
            .map { it.id }

    /** Stubs the streaming socket with exactly [events]. */
    private fun stubStream(vararg events: ChatStreamEvent) {
        val stream: Flow<Either<ApiResourceError, ChatStreamEvent>> =
            flowOf(*events.map { event -> event.right() }.toTypedArray())
        every { chatApi.processNewMessageStreaming(sessionId, any()) } returns stream
    }

    /** Stubs the non-streaming socket with exactly [events]. */
    private fun stubNonStreaming(vararg events: ChatEvent) {
        val stream: Flow<Either<ApiResourceError, ChatEvent>> =
            flowOf(*events.map { event -> event.right() }.toTypedArray())
        every { chatApi.processNewMessage(sessionId, any()) } returns stream
    }

    /** Builds the compaction notification payload of the case. */
    private fun compactionCompleted() = CompactionCompletedPayload(
        chunkId = 42L,
        sessionId = sessionId,
        coveredMessageIds = listOf(1L),
        modelId = 1L,
        settingsId = 2L,
        providerId = 3L,
        modelName = "Model",
        settingsName = "Settings",
        providerName = "Provider",
        sourceTokenCount = 4_500L,
        resultTokenCount = 2_000L,
        summaryPreview = "A concise summary.",
        createdAt = base.toEpochMilliseconds()
    )

    /** Builds the captured provider request payload of the case. */
    private fun rawLlmRequest() = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.example.com/v1/chat/completions",
        headers = emptyMap(),
        body = """{"model":"gpt-4"}"""
    )

    @Test
    fun `a persisted user message moves its session to the top of the ungrouped section`() = runTest {
        bumpOnlyRepositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        assertEquals(listOf(otherSessionId, sessionId), orderIn(null))
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `a persisted user message moves its session to the top of its group section`() = runTest {
        bumpOnlyRepositories()
        seed(
            summary(otherSessionId, at(2_000), groupId = groupId),
            summary(sessionId, at(0), groupId = groupId),
            summary(thirdSessionId, at(9_000))
        )
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(listOf(sessionId, otherSessionId), orderIn(group))
        // The bump touches only the touched session, so the ungrouped section keeps its own order.
        assertEquals(listOf(thirdSessionId), orderIn(null))
    }

    @Test
    fun `an assistant message start moves its session to the top`() = runTest {
        bumpOnlyRepositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        stubStream(
            ChatStreamEvent.AssistantMessageStart(
                assistantMessage = assistantMessage(updatedAt = at(3_000)),
                updatedParentMessage = userMessage(updatedAt = at(0))
            )
        )

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(3_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `an assistant message end moves its session to the top`() = runTest {
        bumpOnlyRepositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        stubStream(ChatStreamEvent.AssistantMessageEnd(assistantMessage(updatedAt = at(4_000))))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(4_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `the non-streaming events move their session to the top`() = runTest {
        bumpOnlyRepositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        stubNonStreaming(
            ChatEvent.UserMessageSaved(userMessage(updatedAt = at(3_000)), null),
            ChatEvent.AssistantMessageSaved(
                assistantMessage = assistantMessage(updatedAt = at(4_000)),
                updatedParentMessage = userMessage(updatedAt = at(3_000))
            )
        )

        conversationRepository.processNewMessage(sessionId, emptyFlow()).toList()

        assertEquals(at(4_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `an older persisted timestamp never lowers the cached summary`() = runTest {
        bumpOnlyRepositories()
        val seeded = listOf(summary(sessionId, at(5_000)), summary(otherSessionId, at(0)))
        seed(*seeded.toTypedArray())
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(1_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `events without a persisted message leave the summary list untouched`() = runTest {
        bumpOnlyRepositories()
        val seeded = listOf(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        seed(*seeded.toTypedArray())
        stubStream(
            ChatStreamEvent.AssistantMessageDelta(messageId = 2L, deltaContent = "chunk"),
            ChatStreamEvent.AssistantMessageReasoningDelta(messageId = 2L, deltaContent = "thought"),
            ChatStreamEvent.ToolCallDelta(
                messageId = 2L,
                index = null,
                id = null,
                name = "sample-tool",
                argumentsDelta = "{}"
            ),
            ChatStreamEvent.ToolCallsReceived(
                listOf(
                    ToolCall(
                        id = 42L,
                        messageId = 2L,
                        toolDefinitionId = null,
                        toolName = "sample-tool",
                        status = ToolCallStatus.SUCCESS,
                        executedAt = base
                    )
                )
            ),
            ChatStreamEvent.StreamCompleted,
            ChatStreamEvent.RawLlmRequest(rawLlmRequest()),
            ChatStreamEvent.CompactionCompleted(compactionCompleted())
        )

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(seeded, cachedSummaries())
    }

    @Test
    fun `a completing turn reconciles the list with the server snapshot`() = runTest {
        reconcilingRepositories(testScheduler)
        val seeded = listOf(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        val authoritative = listOf(summary(sessionId, at(7_000)), summary(otherSessionId, at(6_000)))
        coEvery { sessionApi.getAllSessions() } returnsMany
            listOf<Either<ApiResourceError, List<ChatSessionSummary>>>(Either.Right(seeded), Either.Right(authoritative))
        sessionRepository.loadSessions()
        stubStream(ChatStreamEvent.StreamCompleted)

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        // The reconciliation adopts the server values, so the rendered order follows the snapshot.
        assertEquals(
            authoritative.associateBy { it.id },
            cachedSummaries().associateBy { it.id }
        )
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
        // One call seeds the list through the load path, the other is the reconciliation of this turn.
        coVerify(exactly = 2) { sessionApi.getAllSessions() }
    }

    @Test
    fun `a cancelled turn keeps its optimistic value and does not reconcile`() = runTest {
        reconcilingRepositories(testScheduler)
        val serverSnapshot = listOf(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        coEvery { sessionApi.getAllSessions() } returns Either.Right(serverSnapshot)
        sessionRepository.loadSessions()
        val released = CompletableDeferred<Unit>()
        var delivered = false
        val stream: Flow<Either<ApiResourceError, ChatStreamEvent>> = flow {
            emit(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null).right())
            delivered = true
            released.await()
        }
        every { chatApi.processNewMessageStreaming(sessionId, any()) } returns stream

        val collecting = launch { conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).collect() }
        advanceUntilIdle()
        collecting.cancelAndJoin()

        assertTrue(delivered, "The turn was expected to deliver its persisted message before being cancelled")
        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        // Only the seed load: a stopped turn leaves the reconciliation to the next load.
        coVerify(exactly = 1) { sessionApi.getAllSessions() }
    }

    @Test
    fun `a failed reconciliation keeps the optimistic values and the success state`() = runTest {
        reconcilingRepositories(testScheduler)
        val seeded = listOf(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        val failure: Either<ApiResourceError, List<ChatSessionSummary>> =
            ApiResourceError.UnknownError("offline", null).left()
        coEvery { sessionApi.getAllSessions() } returnsMany
            listOf(Either.Right(seeded), failure)
        sessionRepository.loadSessions()
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
        assertTrue(sessionRepository.sessions.value.isSuccess)
    }

    @Test
    fun `a reconciliation never emits a loading state`() = runTest {
        reconcilingRepositories(testScheduler)
        val seeded = listOf(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        val authoritative = listOf(summary(sessionId, at(5_000)), summary(otherSessionId, at(2_000)))
        coEvery { sessionApi.getAllSessions() } returnsMany
            listOf<Either<ApiResourceError, List<ChatSessionSummary>>>(Either.Right(seeded), Either.Right(authoritative))
        sessionRepository.loadSessions()
        val observed = mutableListOf<DataState<RepositoryError, List<ChatSessionSummary>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sessionRepository.sessions.collect { state -> observed += state }
        }
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        // A loading state would replace the panel and drop the collapse state and the scroll position with it.
        assertTrue(observed.none { it.isLoading }, "the list must stay in a success state while it is reconciled")
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `a stale snapshot keeps the newer instant while adopting the server fields`() = runTest {
        reconcilingRepositories(testScheduler)
        val seeded = listOf(summary(sessionId, at(0)), summary(otherSessionId, at(0)))
        // The snapshot was taken before this turn, so the row it carries is renamed but older than the raised value.
        val stale = listOf(
            summary(sessionId, at(1_000)).copy(name = "Renamed on the server"),
            summary(otherSessionId, at(4_000))
        )
        coEvery { sessionApi.getAllSessions() } returnsMany
            listOf<Either<ApiResourceError, List<ChatSessionSummary>>>(Either.Right(seeded), Either.Right(stale))
        sessionRepository.loadSessions()
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(9_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        // The row takes the server's name, but its ordering instant cannot drop back below the older session.
        assertEquals("Renamed on the server", summaryOf(sessionId).name)
        assertEquals(at(9_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `a locally created row is kept while known rows take the server values`() = runTest {
        reconcilingRepositories(testScheduler)
        val seeded = listOf(
            summary(otherSessionId, at(0)),
            summary(sessionId, at(1_000)),
            summary(thirdSessionId, at(9_000))
        )
        val authoritative = listOf(summary(sessionId, at(5_000)), summary(otherSessionId, at(4_000)))
        coEvery { sessionApi.getAllSessions() } returnsMany
            listOf<Either<ApiResourceError, List<ChatSessionSummary>>>(Either.Right(seeded), Either.Right(authoritative))
        sessionRepository.loadSessions()
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        // The snapshot is not allowed to add or drop rows: it only refreshes the timestamps of the rows the client
        // holds, while the row the snapshot does not carry keeps the value this client last saw.
        assertEquals(
            mapOf(sessionId to at(5_000), otherSessionId to at(4_000), thirdSessionId to at(9_000)),
            cachedSummaries().associate { it.id to it.updatedAt }
        )
        assertEquals(listOf(thirdSessionId, sessionId, otherSessionId), orderIn(null))
    }
}
