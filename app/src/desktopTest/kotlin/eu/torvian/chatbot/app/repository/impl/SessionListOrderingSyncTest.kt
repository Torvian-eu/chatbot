package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.repository.impl.session.SessionDetailsStore
import eu.torvian.chatbot.app.repository.impl.session.SessionEventApplier
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Covers the live session ordering: the optimistic `updatedAt` bump a persisted turn message produces, and the fact
 * that a turn raises that order from its own events instead of re-reading the session list.
 *
 * Assertions are expressed through `SessionListData.groupedSessions`, i.e. the order the panel actually renders,
 * rather than through the raw cached list.
 */
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

    /** Builds both halves over one shared set of collaborators. */
    private fun repositories() {
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
            summaryStore = summaryStore
        )
    }

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
        repositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        assertEquals(listOf(otherSessionId, sessionId), orderIn(null))
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `a persisted user message moves its session to the top of its group section`() = runTest {
        repositories()
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
        repositories()
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
        repositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        stubStream(ChatStreamEvent.AssistantMessageEnd(assistantMessage(updatedAt = at(4_000))))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(4_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `the non-streaming events move their session to the top`() = runTest {
        repositories()
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
        repositories()
        val seeded = listOf(summary(sessionId, at(5_000)), summary(otherSessionId, at(0)))
        seed(*seeded.toTypedArray())
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(1_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        assertEquals(listOf(sessionId, otherSessionId), orderIn(null))
    }

    @Test
    fun `events without a persisted message leave the summary list untouched`() = runTest {
        repositories()
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
    fun `a completed turn issues no session list request`() = runTest {
        repositories()
        seed(summary(otherSessionId, at(2_000)), summary(sessionId, at(0)))
        stubStream(ChatStreamEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))
        stubNonStreaming(ChatEvent.UserMessageSaved(userMessage(updatedAt = at(5_000)), null))

        conversationRepository.processNewMessageStreaming(sessionId, emptyFlow()).toList()
        conversationRepository.processNewMessage(sessionId, emptyFlow()).toList()

        assertEquals(at(5_000), summaryOf(sessionId).updatedAt)
        // Only the seed load above reads the list: a turn raises the order from the events it applies itself.
        coVerify(exactly = 1) { sessionApi.getAllSessions() }
    }
}
