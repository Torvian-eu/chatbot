package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.CompactionCompletedPayload
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
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
 * Verifies that the manual compaction path is a pure pass-through: it forwards the server's terminal
 * events unchanged and never mutates the cached session, because a compaction writes no transcript row.
 */
class DefaultSessionRepositoryCompactionTest {

    private val sessionId = 100L
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private lateinit var sessionApi: SessionApi
    private lateinit var chatApi: ChatApi
    private lateinit var repository: DefaultSessionRepository

    @BeforeTest
    fun setup() {
        sessionApi = mockk()
        chatApi = mockk()
        repository = DefaultSessionRepository(sessionApi, chatApi)
    }

    /** Builds a session with one message whose cached state the compaction must not touch. */
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

    /** Stubs the compaction socket with exactly [events]. */
    private fun stubCompaction(events: List<CompactionEvent>) {
        val flow: Flow<Either<eu.torvian.chatbot.app.service.api.ApiResourceError, CompactionEvent>> =
            flowOf(*events.map { event -> event.right() }.toTypedArray())
        every { chatApi.compactConversation(sessionId) } returns flow
    }

    @Test
    fun `the terminal events are forwarded unchanged and the cached session is untouched`() = runTest {
        loadSession()
        val before = repository.getSessionDetailsFlow(sessionId).value
        val payload = CompactionCompletedPayload(
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
            createdAt = 1_700_000_000_100L
        )
        stubCompaction(
            listOf(
                CompactionEvent.Completed(payload),
                CompactionEvent.Skipped(CompactionSkipReason.ALREADY_COMPACTED),
                CompactionEvent.StreamCompleted
            )
        )

        val emitted = repository.compactConversation(sessionId).toList()

        assertEquals(
            listOf(
                CompactionEvent.Completed(payload),
                CompactionEvent.Skipped(CompactionSkipReason.ALREADY_COMPACTED),
                CompactionEvent.StreamCompleted
            ),
            emitted.map { either -> assertIs<Either.Right<CompactionEvent>>(either).value }
        )
        // No transcript row was created, replaced or edited by the compaction.
        assertEquals(before, repository.getSessionDetailsFlow(sessionId).value)
    }

    @Test
    fun `an api failure is mapped to a repository error`() = runTest {
        loadSession()
        val failure = eu.torvian.chatbot.app.service.api.ApiResourceError.UnknownError("socket down", null)
        val flow: Flow<Either<eu.torvian.chatbot.app.service.api.ApiResourceError, CompactionEvent>> =
            flowOf(failure.left())
        every { chatApi.compactConversation(sessionId) } returns flow

        val emitted = repository.compactConversation(sessionId).toList()

        val error = assertIs<RepositoryError>(assertIs<Either.Left<RepositoryError>>(emitted.single()).value)
        assertEquals("Failed to compact the conversation: ${failure.message}", error.message)
    }
}
