package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.impl.session.SessionDetailsStore
import eu.torvian.chatbot.app.repository.impl.session.SessionSummaryStore
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.core.DurationStats
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Verifies that the thread statistics pass through the session list repository unchanged and that a failing
 * fetch is wrapped with the operation's context.
 */
class DefaultSessionListRepositoryThreadStatsTest {

    private val sessionId = 42L

    private lateinit var sessionApi: SessionApi
    private lateinit var repository: DefaultSessionListRepository

    private val stats = ThreadStats(
        totalMessages = 4,
        userMessages = 2,
        assistantMessages = 2,
        toolCalls = 3,
        reasoningItems = 3,
        inputTokens = 1_200L,
        outputTokens = 150L,
        reasoningTokens = 30L,
        cachedInputTokens = 150L,
        responseDuration = DurationStats(
            totalMs = 3_000L,
            averageMs = 1_500.0,
            standardDeviationMs = 500.0,
            outliers = listOf(4L)
        ),
        toolCallDuration = DurationStats(
            totalMs = 1_500L,
            averageMs = 750.0,
            standardDeviationMs = 250.0,
            outliers = emptyList()
        ),
        toolCallSuccessRate = 0.75,
        assistantMessageSuccessRate = 1.0,
        averageOutputTokensPerSecond = 50.0,
        inputCacheHitRate = 0.75
    )

    @BeforeTest
    fun setup() {
        sessionApi = mockk()
        repository = DefaultSessionListRepository(
            sessionApi = sessionApi,
            summaryStore = SessionSummaryStore(),
            details = SessionDetailsStore()
        )
    }

    @Test
    fun `returns the payload reported by the api`() = runTest {
        coEvery { sessionApi.getThreadStats(sessionId) } returns Either.Right(stats)

        when (val result = repository.getThreadStats(sessionId)) {
            is Either.Right -> assertEquals(stats, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `wraps an api failure with the operation context`() = runTest {
        val apiError = ApiResourceError.ServerError(
            apiError(CommonApiErrorCodes.INTERNAL, "Database error")
        )
        coEvery { sessionApi.getThreadStats(sessionId) } returns Either.Left(apiError)

        when (val result = repository.getThreadStats(sessionId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = assertIs<RepositoryError.DataFetchError>(result.value)
                assertEquals(apiError, error.apiResourceError)
                assertEquals("Failed to load thread stats", error.contextMessage)
            }
        }
    }
}
