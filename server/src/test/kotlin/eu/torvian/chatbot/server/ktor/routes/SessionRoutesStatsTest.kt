package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.testutils.auth.authenticate
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Integration tests for `GET /api/v1/sessions/{sessionId}/stats`.
 *
 * The fixtures seed a message tree directly in the database so the endpoint is exercised end to end:
 * branch resolution, aggregation and the authorization gate.
 */
class SessionRoutesStatsTest : SessionRoutesTestBase() {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /** Builds a user message of [sessionId]. */
    private fun userMessage(id: Long, parentId: Long?, sessionId: Long = testSession.id) =
        ChatMessage.UserMessage(
            id = id,
            sessionId = sessionId,
            content = "user $id",
            createdAt = now,
            updatedAt = now,
            parentMessageId = parentId
        )

    /** Builds an assistant message of [sessionId] with the given measurements. */
    private fun assistantMessage(
        id: Long,
        parentId: Long?,
        sessionId: Long = testSession.id,
        usageStats: UsageStats? = null,
        responseDurationMs: Long? = null,
        reasoningItems: List<JsonObject>? = null
    ) = ChatMessage.AssistantMessage(
        id = id,
        sessionId = sessionId,
        content = "assistant $id",
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentId,
        modelId = testModel.id,
        settingsId = testSettings.id,
        reasoningItems = reasoningItems,
        usageStats = usageStats,
        responseDurationMs = responseDurationMs
    )

    /** Seeds a tool call and fails the test when the insert is rejected. */
    private suspend fun seedToolCall(
        messageId: Long,
        status: ToolCallStatus,
        durationMs: Long?
    ) {
        val toolCallDao: ToolCallDao = container.get()
        val result = toolCallDao.insertToolCall(
            messageId = messageId,
            toolDefinitionId = null,
            toolName = "read_file",
            toolCallId = null,
            input = null,
            output = null,
            status = status,
            errorMessage = null,
            denialReason = null,
            executedAt = now,
            durationMs = durationMs,
            errorCode = null,
            errorDetails = null
        )
        assertTrue(result.isRight(), "seeding the tool call failed: $result")
    }

    /** Seeds the session row with its ownership link. */
    private suspend fun seedOwnedSession() {
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, authHelper.defaultTestUser.id)
    }

    @Test
    fun `GET stats returns the statistics of the current thread`() = sessionTestApplication {
        // Arrange: a thread of two user and two assistant messages, the second assistant message
        // carrying reasoning items, plus three tool calls (one still without a duration).
        seedOwnedSession()
        testDataManager.insertChatMessage(userMessage(id = 1L, parentId = null))
        testDataManager.insertChatMessage(
            assistantMessage(
                id = 2L,
                parentId = 1L,
                responseDurationMs = 1_000L,
                usageStats = UsageStats(
                    inputTokens = 1_000,
                    outputTokens = 100,
                    totalTokens = 1_100
                )
            )
        )
        testDataManager.insertChatMessage(userMessage(id = 3L, parentId = 2L))
        testDataManager.insertChatMessage(
            assistantMessage(
                id = 4L,
                parentId = 3L,
                responseDurationMs = 2_000L,
                usageStats = UsageStats(
                    inputTokens = 200,
                    outputTokens = 50,
                    totalTokens = 250,
                    reasoningTokens = 30,
                    cachedTokens = 150
                ),
                reasoningItems = List(3) { JsonObject(emptyMap()) }
            )
        )
        testDataManager.insertSessionCurrentLeaf(
            SessionCurrentLeafEntity(sessionId = testSession.id, messageId = 4L)
        )
        seedToolCall(messageId = 4L, status = ToolCallStatus.SUCCESS, durationMs = 500L)
        seedToolCall(messageId = 4L, status = ToolCallStatus.ERROR, durationMs = null)
        seedToolCall(messageId = 2L, status = ToolCallStatus.SUCCESS, durationMs = 1_000L)

        // Act
        val response = client.get(
            href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = testSession.id)))
        ) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val stats = response.body<ThreadStats>()

        assertEquals(4, stats.totalMessages)
        assertEquals(2, stats.userMessages)
        assertEquals(2, stats.assistantMessages)
        assertEquals(3, stats.toolCalls)
        assertEquals(3, stats.reasoningItems)

        assertEquals(1_200L, stats.inputTokens)
        assertEquals(150L, stats.outputTokens)
        assertEquals(30L, stats.reasoningTokens)
        assertEquals(150L, stats.cachedInputTokens)

        // Durations of 1s and 2s: 3s total, 1.5s mean and a 0.5s population deviation.
        assertEquals(3_000L, stats.responseDuration.totalMs)
        assertEquals(1_500.0, stats.responseDuration.averageMs!!, 1e-9)
        assertEquals(500.0, stats.responseDuration.standardDeviationMs!!, 1e-9)
        assertTrue(stats.responseDuration.outliers.isEmpty())

        // Tool call durations of 500ms and 1s: 1.5s total, 750ms mean and a 250ms deviation.
        assertEquals(1_500L, stats.toolCallDuration.totalMs)
        assertEquals(750.0, stats.toolCallDuration.averageMs!!, 1e-9)
        assertEquals(250.0, stats.toolCallDuration.standardDeviationMs!!, 1e-9)
        assertTrue(stats.toolCallDuration.outliers.isEmpty())

        assertEquals(2.0 / 3.0, stats.toolCallSuccessRate!!, 1e-9)
        assertEquals(1.0, stats.assistantMessageSuccessRate!!, 1e-9)
        // 150 output tokens over the 3 measured seconds.
        assertEquals(50.0, stats.averageOutputTokensPerSecond!!, 1e-9)
        // The first usage-carrying message is excluded: 150 cached of 200 input tokens.
        assertEquals(0.75, stats.inputCacheHitRate!!, 1e-9)
    }

    @Test
    fun `GET stats measures only the branch of the current leaf`() = sessionTestApplication {
        // Arrange: message 1 has two branches; the current leaf points into the first one.
        seedOwnedSession()
        testDataManager.insertChatMessage(userMessage(id = 1L, parentId = null))
        testDataManager.insertChatMessage(assistantMessage(id = 2L, parentId = 1L))
        testDataManager.insertChatMessage(assistantMessage(id = 3L, parentId = 2L))
        testDataManager.insertChatMessage(assistantMessage(id = 4L, parentId = 1L))
        testDataManager.insertChatMessage(assistantMessage(id = 5L, parentId = 4L))
        testDataManager.insertChatMessage(userMessage(id = 6L, parentId = 5L))
        testDataManager.insertSessionCurrentLeaf(
            SessionCurrentLeafEntity(sessionId = testSession.id, messageId = 3L)
        )
        seedToolCall(messageId = 2L, status = ToolCallStatus.SUCCESS, durationMs = 10L)
        // Belongs to the sibling branch and must not be counted.
        seedToolCall(messageId = 5L, status = ToolCallStatus.SUCCESS, durationMs = 10L)

        // Act
        val response = client.get(
            href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = testSession.id)))
        ) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val stats = response.body<ThreadStats>()
        assertEquals(3, stats.totalMessages)
        assertEquals(1, stats.userMessages)
        assertEquals(2, stats.assistantMessages)
        assertEquals(1, stats.toolCalls)
    }

    @Test
    fun `GET stats returns zero counts and null totals for a session without a thread`() = sessionTestApplication {
        // Arrange: an owned session with no message and no current leaf.
        seedOwnedSession()

        // Act
        val response = client.get(
            href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = testSession.id)))
        ) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val stats = response.body<ThreadStats>()
        assertEquals(0, stats.totalMessages)
        assertEquals(0, stats.userMessages)
        assertEquals(0, stats.assistantMessages)
        assertEquals(0, stats.toolCalls)
        assertEquals(0, stats.reasoningItems)
        // No message exists, so no token counter was ever reported.
        assertNull(stats.inputTokens)
        assertNull(stats.outputTokens)
        assertNull(stats.reasoningTokens)
        assertNull(stats.cachedInputTokens)
        assertNull(stats.responseDuration.totalMs)
        assertNull(stats.toolCallDuration.totalMs)
        assertTrue(stats.responseDuration.outliers.isEmpty())
        assertNull(stats.toolCallSuccessRate)
        assertNull(stats.assistantMessageSuccessRate)
        assertNull(stats.averageOutputTokensPerSecond)
        assertNull(stats.inputCacheHitRate)
    }

    @Test
    fun `GET stats fails loudly when the branch of the current leaf cannot be resolved`() =
        sessionTestApplication {
            // Arrange: the parent link must stay a valid foreign key, so the seeded leaf points at a
            // message that exists but belongs to another session. The session-scoped load cannot see
            // it, which is exactly the corrupt chain that must not be papered over with partial numbers.
            seedOwnedSession()
            testDataManager.insertChatSession(testSession2)
            testDataManager.insertChatMessage(userMessage(id = 1L, parentId = null, sessionId = testSession2.id))
            testDataManager.insertChatMessage(assistantMessage(id = 2L, parentId = 1L))
            testDataManager.insertSessionCurrentLeaf(
                SessionCurrentLeafEntity(sessionId = testSession.id, messageId = 2L)
            )

            // Act
            val response = client.get(
                href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = testSession.id)))
            ) {
                authenticate(authToken)
            }

            // Assert: an unresolvable chain surfaces as a server error rather than a fabricated thread.
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

    @Test
    fun `GET stats returns 404 for a non-existent session`() = sessionTestApplication {
        // Act
        val response = client.get(
            href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = 999L)))
        ) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Resource not found", error.message)
    }

    @Test
    fun `GET stats returns 403 for a session owned by another user`() = sessionTestApplication {
        // Arrange
        val otherUser = authHelper.createTestUser(
            id = 999L,
            email = "statsowner@example.com",
            username = "statsowner"
        )
        testDataManager.insertUser(otherUser)
        testDataManager.insertChatSession(testSession)
        testDataManager.insertSessionOwnership(testSession.id, otherUser.id)

        // Act
        val response = client.get(
            href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = testSession.id)))
        ) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
        assertEquals("Access denied", error.message)
    }

    @Test
    fun `GET stats returns 401 without a token`() = sessionTestApplication {
        // Act
        val response = client.get(
            href(SessionResource.ById.Stats(parent = SessionResource.ById(sessionId = testSession.id)))
        )

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
