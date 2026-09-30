package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies how streamed reasoning deltas are folded into the cached session.
 *
 * The live reasoning has to land in the same item shape the server persists, so the section renders from one place
 * while streaming and after the completing event replaces the message; the delta must not disturb the rest of the
 * message or its neighbours.
 */
class DefaultSessionRepositoryReasoningStreamingTest {

    private val sessionId = 100L
    private val neighbourId = 7L
    private val placeholderId = 8L
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

    /** Builds a session with one finalized neighbour and one in-flight reasoning placeholder. */
    private fun session() = ChatSession(
        id = sessionId,
        name = "Session",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = null,
        currentLeafMessageId = placeholderId,
        messages = listOf(neighbourMessage(), placeholder())
    )

    /** Builds the finalized neighbour whose fields the streaming deltas must never touch. */
    private fun neighbourMessage() = ChatMessage.AssistantMessage(
        id = neighbourId,
        sessionId = sessionId,
        content = "earlier answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = null,
        modelId = 1L,
        settingsId = 2L
    )

    /** Builds the streaming placeholder that receives the live reasoning. */
    private fun placeholder() = ChatMessage.AssistantMessage(
        id = placeholderId,
        sessionId = sessionId,
        content = "partial answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = neighbourId,
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

    /** Stubs the streaming API with exactly [events]. */
    private fun stubStream(events: List<ChatStreamEvent>) {
        val stream: Flow<Either<ApiResourceError, ChatStreamEvent>> =
            flowOf(*events.map { event -> event.right() }.toTypedArray())
        every { chatApi.processNewMessageStreaming(sessionId, any()) } returns stream
    }

    /** Returns the cached message with [messageId]. */
    private suspend fun cachedMessage(messageId: Long): ChatMessage =
        repository.getSessionDetailsFlow(sessionId).value.dataOrNull
            ?.messages
            ?.first { it.id == messageId }
            ?: error("the session under test was expected to be cached")

    /**
     * Returns the plaintext of the reasoning items of a cached assistant message.
     *
     * @param message Cached message to read the reasoning of.
     * @return Joined `content[].text` of the message's reasoning items.
     */
    private fun ChatMessage.reasoningText(): String =
        (this as ChatMessage.AssistantMessage).reasoningItems.orEmpty()
            .flatMap { item -> (item["content"] as? JsonArray).orEmpty() }
            .joinToString("") { part -> ((part as JsonObject)["text"] as? JsonPrimitive)?.content.orEmpty() }

    @Test
    fun `reasoning delta appends the live text to the cached placeholder`() = runTest {
        loadSession()
        stubStream(
            listOf(
                ChatStreamEvent.AssistantMessageReasoningDelta(placeholderId, "think"),
                ChatStreamEvent.AssistantMessageReasoningDelta(placeholderId, "ing")
            )
        )

        repository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        val cached = assertIs<ChatMessage.AssistantMessage>(cachedMessage(placeholderId))
        assertEquals("thinking", cached.reasoningText())
    }

    @Test
    fun `reasoning delta leaves content, state and neighbours untouched`() = runTest {
        loadSession()
        val neighbourBefore = cachedMessage(neighbourId)
        stubStream(listOf(ChatStreamEvent.AssistantMessageReasoningDelta(placeholderId, "thought")))

        repository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        val cached = assertIs<ChatMessage.AssistantMessage>(cachedMessage(placeholderId))
        assertEquals("partial answer", cached.content)
        assertFalse(cached.isComplete)
        assertNull(cached.incompleteCause)
        assertEquals("earlier answer", cachedMessage(neighbourId).content)
        assertEquals(neighbourBefore, cachedMessage(neighbourId))
        assertTrue(cached.updatedAt > now, "Applying a delta refreshes the message timestamp")
    }

    @Test
    fun `reasoning delta for an unknown message is ignored`() = runTest {
        loadSession()
        stubStream(listOf(ChatStreamEvent.AssistantMessageReasoningDelta(999L, "stray")))

        repository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        assertEquals(2, assertIs<DataState.Success<ChatSession>>(repository.getSessionDetailsFlow(sessionId).value).data.messages.size)
        assertEquals("", cachedMessage(placeholderId).reasoningText())
        assertEquals("", cachedMessage(neighbourId).reasoningText())
    }

    @Test
    fun `assistant message end replaces the live reasoning with the persisted items`() = runTest {
        loadSession()
        val persistedItem = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_persisted")
        }
        val persisted = placeholder().copy(
            content = "final answer",
            isComplete = true,
            reasoningItems = listOf(persistedItem)
        )
        stubStream(
            listOf(
                ChatStreamEvent.AssistantMessageReasoningDelta(placeholderId, "live"),
                ChatStreamEvent.AssistantMessageEnd(persisted)
            )
        )

        repository.processNewMessageStreaming(sessionId, emptyFlow()).toList()

        val cached = assertIs<ChatMessage.AssistantMessage>(cachedMessage(placeholderId))
        assertEquals(persisted, cached)
        // The synthetic live item disappears with the placeholder it lived in.
        assertEquals("", cached.reasoningText())
        assertEquals(listOf(persistedItem), cached.reasoningItems)
    }
}
