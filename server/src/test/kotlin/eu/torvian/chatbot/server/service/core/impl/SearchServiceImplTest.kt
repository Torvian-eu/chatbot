package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.service.core.error.search.SearchMessagesError
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [SearchServiceImpl].
 *
 * These tests verify that the service keeps ownership-scoped search validation in the service layer while
 * delegating the actual query execution to [MessageDao].
 */
class SearchServiceImplTest {

    /**
     * Mocked DAO dependency used to observe validated search inputs.
     */
    private lateinit var messageDao: MessageDao

    /**
     * Service instance under test.
     */
    private lateinit var searchService: SearchServiceImpl

    /**
     * Example DAO payload reused across successful search tests.
     */
    private val expectedResults = listOf(
        MessageSearchResult(
            sessionId = 1L,
            sessionName = "Test Session",
            messageId = 10L,
            messageRole = eu.torvian.chatbot.common.models.core.ChatMessage.Role.USER,
            snippet = "...needle...",
            matchStartIndex = 3,
            matchEndExclusive = 9,
            createdAt = Instant.fromEpochMilliseconds(1234567890000L)
        )
    )

    /**
     * Creates fresh mocks and the service instance before each test.
     */
    @BeforeEach
    fun setUp() {
        messageDao = mockk()
        searchService = SearchServiceImpl(messageDao)
    }

    /**
     * Clears MockK state after each test to keep interaction assertions isolated.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(messageDao)
    }

    /**
     * Ensures surrounding whitespace is trimmed and requested limits are clamped before the DAO is invoked.
     */
    @Test
    fun `searchMessages should trim query clamp limit and delegate to dao`() = runTest {
        coEvery {
            messageDao.searchMessagesByUserId(7L, "needle", MessageSearchScope.ALL_THREADS, 100)
        } returns expectedResults

        val result = searchService.searchMessages(
            userId = 7L,
            query = "  needle  ",
            scope = MessageSearchScope.ALL_THREADS,
            limit = 500,
        )

        assertTrue(result.isRight())
        assertEquals(expectedResults, result.getOrNull())
        coVerify(exactly = 1) {
            messageDao.searchMessagesByUserId(7L, "needle", MessageSearchScope.ALL_THREADS, 100)
        }
    }

    /**
     * Ensures blank queries are rejected before the DAO is touched.
     */
    @Test
    fun `searchMessages should reject blank query`() = runTest {
        val result = searchService.searchMessages(userId = 7L, query = "   ", limit = 10)

        assertTrue(result.isLeft())
        assertEquals(SearchMessagesError.EmptyQuery, result.leftOrNull())
        coVerify(exactly = 0) { messageDao.searchMessagesByUserId(any(), any(), any(), any()) }
    }

    /**
     * Ensures overlong queries are rejected before the DAO is touched.
     */
    @Test
    fun `searchMessages should reject overly long query`() = runTest {
        val query = "a".repeat(201)

        val result = searchService.searchMessages(userId = 7L, query = query, limit = 10)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<SearchMessagesError.QueryTooLong>(error)
        assertEquals(201, error.actualLength)
        assertEquals(200, error.maxLength)
        coVerify(exactly = 0) { messageDao.searchMessagesByUserId(any(), any(), any(), any()) }
    }
}