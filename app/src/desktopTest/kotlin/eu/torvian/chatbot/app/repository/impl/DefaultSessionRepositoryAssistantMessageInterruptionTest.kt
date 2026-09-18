package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the local settling of a stopped turn (`markAssistantMessageInterrupted`).
 *
 * The marking is the only session mutation the send path still performs: it must set the interruption cause on
 * the one unfinished message this client cancelled, leave every other message and every other field of the cached
 * session exactly as it was, and issue no request at all — the cache is the client's own copy of the session and
 * the next session load reads the persisted truth.
 */
class DefaultSessionRepositoryAssistantMessageInterruptionTest {

    private val sessionId = 100L
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val finishedMessageId = 7L
    private val placeholderId = 8L

    private lateinit var sessionApi: SessionApi
    private lateinit var chatApi: ChatApi
    private lateinit var repository: DefaultSessionRepository

    @BeforeTest
    fun setup() {
        sessionApi = mockk()
        chatApi = mockk()
        repository = DefaultSessionRepository(sessionApi, chatApi)
    }

    /**
     * Builds a session holding one already finalized assistant message and one in-flight placeholder, so both the
     * target of the marking and the neighbour it must not touch live in the same cache entry.
     */
    private fun session() = ChatSession(
        id = sessionId,
        name = "Session",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = null,
        currentLeafMessageId = placeholderId,
        messages = listOf(completedAssistantMessage(), assistantPlaceholder())
    )

    /** Builds the finalized assistant message of the session, which the marking must never touch. */
    private fun completedAssistantMessage() = ChatMessage.AssistantMessage(
        id = finishedMessageId,
        sessionId = sessionId,
        content = "earlier answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = null,
        modelId = 1L,
        settingsId = 2L
    )

    /** Builds the streaming placeholder of the current turn, i.e. the message the marking settles. */
    private fun assistantPlaceholder() = ChatMessage.AssistantMessage(
        id = placeholderId,
        sessionId = sessionId,
        content = "partial answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = finishedMessageId,
        modelId = 1L,
        settingsId = 2L,
        isComplete = false,
        incompleteCause = null
    )

    /** Seeds the cache of the repository under test with [session] through the regular load path. */
    private suspend fun loadSession(): ChatSession {
        coEvery { sessionApi.getSessionDetails(sessionId) } returns Either.Right(session())
        repository.loadSessionDetails(sessionId)
        return cachedSession()
    }

    /** Returns the currently cached session of [sessionId], failing the test when it is not loaded. */
    private suspend fun cachedSession(): ChatSession =
        repository.getSessionDetailsFlow(sessionId).value.dataOrNull
            ?: error("the session under test was expected to be cached")

    /** Returns the cached message with [messageId]. */
    private suspend fun cachedMessage(messageId: Long): ChatMessage =
        cachedSession().messages.first { it.id == messageId }

    @Test
    fun `markAssistantMessageInterrupted sets the cause on the matching unfinished message`() = runTest {
        loadSession()

        repository.markAssistantMessageInterrupted(sessionId, placeholderId)

        val marked = assertIs<ChatMessage.AssistantMessage>(cachedMessage(placeholderId))
        assertEquals(AssistantMessageIncompleteCause.INTERRUPTED_BY_USER, marked.incompleteCause)
        // Cause only (D2): a user interruption carries no code and no reason text, and the notice is derived
        // from the cause alone, so the stopped message starts showing it.
        assertNull(marked.errorCode)
        assertNull(marked.errorMessage)
        assertFalse(marked.isComplete)
        assertTrue(marked.showsIncompleteNotice)
    }

    @Test
    fun `markAssistantMessageInterrupted leaves a completed message and other messages untouched`() = runTest {
        val loaded = loadSession()

        repository.markAssistantMessageInterrupted(sessionId, placeholderId)

        val cached = cachedSession()
        // The already finalized message is the very same instance: the guard refuses to re-flag a message that
        // already carries a terminal state, which is what keeps a server-delivered ending authoritative.
        assertSame(
            loaded.messages.first { it.id == finishedMessageId },
            cached.messages.first { it.id == finishedMessageId }
        )
        assertNull(assertIs<ChatMessage.AssistantMessage>(cachedMessage(finishedMessageId)).incompleteCause)
    }

    @Test
    fun `markAssistantMessageInterrupted does not change the rest of the cached session`() = runTest {
        val loaded = loadSession()

        repository.markAssistantMessageInterrupted(sessionId, placeholderId)

        // Settling one message must not turn the cached session into a different one: no replacement, no
        // Loading/Error transition, and no request — the marking is purely local.
        val state = repository.getSessionDetailsFlow(sessionId).value
        assertIs<DataState.Success<ChatSession>>(state)
        val cached = cachedSession()
        assertEquals(loaded.copy(messages = emptyList()), cached.copy(messages = emptyList()))
        assertEquals(loaded.messages.size, cached.messages.size)
        // Only the seeding load issued a request; the marking itself is purely local.
        coVerify(exactly = 1) { sessionApi.getSessionDetails(sessionId) }
        coVerify(exactly = 0) { sessionApi.getSessionToolCalls(any()) }
    }
}
