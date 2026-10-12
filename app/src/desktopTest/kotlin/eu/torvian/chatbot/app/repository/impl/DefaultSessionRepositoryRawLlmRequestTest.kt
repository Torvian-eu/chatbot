package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Verifies that the captured provider request is a pure pass-through on both chat surfaces: it reaches the
 * caller unchanged and never mutates the cached session, because the viewer renders it from chat state rather
 * than from a transcript row.
 */
class DefaultSessionRepositoryRawLlmRequestTest {

    private val sessionId = 100L
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private lateinit var sessionApi: SessionApi
    private lateinit var chatApi: ChatApi
    private lateinit var repository: DefaultSessionRepository

    private val payload = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.example.com/v1/chat/completions",
        headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer sk-abcdef..."),
        body = """{"model":"gpt-4"}"""
    )

    @BeforeTest
    fun setup() {
        sessionApi = mockk()
        chatApi = mockk()
        repository = DefaultSessionRepository(sessionApi, chatApi)
        // A completing turn reconciles the session list in the background; the strict mock needs the fetch stubbed
        // even though these cases are about the cached session details.
        coEvery { sessionApi.getAllSessions() } returns Either.Right(emptyList())
    }

    /** Builds a session with one message whose cached state the capture must not touch. */
    private fun session() = ChatSession(
        id = sessionId,
        name = "Session",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = null,
        currentLeafMessageId = 1L,
        messages = listOf(
            ChatMessage.UserMessage(
                id = 1L,
                sessionId = sessionId,
                content = "Hello",
                createdAt = now,
                updatedAt = now,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
        )
    )

    /** Seeds the cache through the regular load path. */
    private suspend fun loadSession() {
        coEvery { sessionApi.getSessionDetails(sessionId) } returns Either.Right(session())
        repository.loadSessionDetails(sessionId)
    }

    @Test
    fun `the non-streaming capture is forwarded and the cached session is untouched`() = runTest {
        loadSession()
        val before = repository.getSessionDetailsFlow(sessionId).value
        val events: Flow<Either<ApiResourceError, ChatEvent>> = flowOf(ChatEvent.RawLlmRequest(payload).right())
        every { chatApi.processNewMessage(sessionId, any()) } returns events

        val emitted = repository.processNewMessage(sessionId, flowOf()).toList()

        assertEquals(payload, assertIs<ChatEvent.RawLlmRequest>(
            assertIs<Either.Right<ChatEvent>>(emitted.single()).value
        ).payload)
        assertEquals(before, repository.getSessionDetailsFlow(sessionId).value)
    }

    @Test
    fun `the streaming capture is forwarded and the cached session is untouched`() = runTest {
        loadSession()
        val before = repository.getSessionDetailsFlow(sessionId).value
        val events: Flow<Either<ApiResourceError, ChatStreamEvent>> =
            flowOf(ChatStreamEvent.RawLlmRequest(payload).right())
        every { chatApi.processNewMessageStreaming(sessionId, any()) } returns events

        val emitted = repository.processNewMessageStreaming(sessionId, flowOf()).toList()

        assertEquals(payload, assertIs<ChatStreamEvent.RawLlmRequest>(
            assertIs<Either.Right<ChatStreamEvent>>(emitted.single()).value
        ).payload)
        assertEquals(before, repository.getSessionDetailsFlow(sessionId).value)
    }
}
