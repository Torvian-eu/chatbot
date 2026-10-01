package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.UsageStats
import io.mockk.coEvery
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies that the usage a turn reports reaches the cached session through the existing message events.
 *
 * The client has no usage event of its own: the value travels inside the assistant message of both the streaming
 * end and the non-streaming saved event, and the cached message must carry it afterwards.
 */
class DefaultSessionRepositoryUsageStatsTest {

    private val sessionId = 100L
    private val parentId = 7L
    private val messageId = 8L
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private lateinit var sessionApi: SessionApi
    private lateinit var chatApi: ChatApi
    private lateinit var repository: DefaultSessionRepository

    /** Usage reported for the generation under test. */
    private val usage = UsageStats(
        inputTokens = 120,
        outputTokens = 30,
        totalTokens = 150,
        reasoningTokens = 12,
        cachedTokens = 8,
        cacheWriteTokens = 4
    )

    @BeforeTest
    fun setup() {
        sessionApi = mockk()
        chatApi = mockk()
        repository = DefaultSessionRepository(sessionApi, chatApi)
    }

    /** Builds a session with one finalized parent and one streaming placeholder. */
    private fun session() = ChatSession(
        id = sessionId,
        name = "Session",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = null,
        currentLeafMessageId = messageId,
        messages = listOf(parentMessage(), placeholder())
    )

    /** Builds the finalized parent message the placeholder replies to. */
    private fun parentMessage() = ChatMessage.AssistantMessage(
        id = parentId,
        sessionId = sessionId,
        content = "earlier answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = null,
        modelId = 1L,
        settingsId = 2L
    )

    /** Builds the streaming placeholder that the turn finalizes. */
    private fun placeholder() = ChatMessage.AssistantMessage(
        id = messageId,
        sessionId = sessionId,
        content = "partial answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentId,
        modelId = 1L,
        settingsId = 2L,
        isComplete = false,
        incompleteCause = null
    )

    /** Seeds the cache through the regular load path. */
    private suspend fun loadSession() {
        coEvery { sessionApi.getSessionDetails(sessionId) } returns Either.Right(session())
        repository.loadSessionDetails(sessionId)
    }

    /** Stubs the streaming API with exactly the given events. */
    private fun stubStream(events: List<ChatStreamEvent>) {
        val stream: Flow<Either<ApiResourceError, ChatStreamEvent>> =
            flowOf(*events.map { event -> event.right() }.toTypedArray())
        every { chatApi.processNewMessageStreaming(sessionId, any()) } returns stream
    }

    /** Returns the cached message with [id]. */
    private suspend fun cachedMessage(id: Long): ChatMessage =
        repository.getSessionDetailsFlow(sessionId).value.dataOrNull
            ?.messages
            ?.first { it.id == id }
            ?: error("the session under test was expected to be cached")

    @Test
    fun `assistant message end replaces the cached placeholder including its usage`() = runTest {
        loadSession()
        val finalized = placeholder().copy(content = "final answer", isComplete = true, usageStats = usage)
        stubStream(listOf(ChatStreamEvent.AssistantMessageEnd(finalized)))

        repository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        val cached = assertIs<ChatMessage.AssistantMessage>(cachedMessage(messageId))
        assertEquals(finalized, cached)
        assertEquals(usage, cached.usageStats)
    }

    @Test
    fun `assistant message end without usage clears the cached usage`() = runTest {
        loadSession()
        val finalized = placeholder().copy(content = "final answer", isComplete = true, usageStats = null)
        stubStream(listOf(ChatStreamEvent.AssistantMessageEnd(finalized)))

        repository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        val cached = assertIs<ChatMessage.AssistantMessage>(cachedMessage(messageId))
        assertNull(cached.usageStats, "A generation without reported usage caches no usage")
    }

    @Test
    fun `assistant message saved carries the usage of a non-streaming turn`() = runTest {
        loadSession()
        val saved = ChatMessage.AssistantMessage(
            id = 9L,
            sessionId = sessionId,
            content = "answer",
            createdAt = now,
            updatedAt = now,
            parentMessageId = parentId,
            modelId = 1L,
            settingsId = 2L,
            usageStats = usage
        )
        val chatEventStream: Flow<Either<ApiResourceError, ChatEvent>> = flowOf(
            ChatEvent.AssistantMessageSaved(saved, parentMessage()).right()
        )
        every { chatApi.processNewMessage(sessionId, any()) } returns chatEventStream

        repository.processNewMessage(sessionId, emptyFlow()).toList()

        val cached = assertIs<ChatMessage.AssistantMessage>(cachedMessage(saved.id))
        assertEquals(usage, cached.usageStats)
    }
}
