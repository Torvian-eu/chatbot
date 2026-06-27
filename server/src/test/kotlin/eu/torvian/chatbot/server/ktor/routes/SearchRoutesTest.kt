package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SearchResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Integration tests for the cross-session search routes.
 */
class SearchRoutesTest {
    /**
     * Dependency injection container backing the test application.
     */
    private lateinit var container: DIContainer

    /**
     * Configured Ktor test application used by each test case.
     */
    private lateinit var searchTestApplication: KtorTestApp

    /**
     * Helper for seeding and cleaning database state between tests.
     */
    private lateinit var testDataManager: TestDataManager

    /**
     * Authentication helper used to create users, sessions, and JWTs for test requests.
     */
    private lateinit var authHelper: TestAuthHelper

    /**
     * Access token representing the authenticated user under test.
     */
    private lateinit var authToken: String

    /**
     * First owned session used in positive search cases.
     */
    private val ownedSession1 = TestDefaults.chatSession1.copy(id = 1L, name = "Owned Session 1")

    /**
     * Second owned session used to verify recency ordering.
     */
    private val ownedSession2 = TestDefaults.chatSession2.copy(id = 2L, name = "Owned Session 2")

    /**
     * Session owned by another user to verify ownership scoping.
     */
    private val foreignSession = TestDefaults.chatSession1.copy(id = 3L, name = "Foreign Session")

    /**
     * Creates a fresh authenticated test application and the minimum reference data required by chat sessions.
     */
    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        searchTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureSearchRoutes(this)
            }
        )

        testDataManager = container.get()
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(TestDefaults.chatGroup1, TestDefaults.chatGroup2),
                llmProviders = listOf(TestDefaults.llmProvider1, TestDefaults.llmProvider2),
                llmModels = listOf(TestDefaults.llmModel1, TestDefaults.llmModel2),
                modelSettings = listOf(TestDefaults.modelSettings1, TestDefaults.modelSettings2)
            )
        )
        testDataManager.createTables(
            setOf(
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.SESSION_CURRENT_LEAF,
                Table.USERS,
                Table.USER_SESSIONS,
                Table.CHAT_SESSION_OWNERS
            )
        )

        authHelper = TestAuthHelper(container)
        authToken = authHelper.createUserAndGetToken()
    }

    /**
     * Removes database state and releases the DI container after each test.
     */
    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Verifies that the default scope searches only the visible branch of each owned session.
     */
    @Test
    fun `GET search messages should default to visible threads only`() = searchTestApplication {
        val otherUser = authHelper.createTestUser(id = 2L, email = "other@example.com", username = "other")
        val ownedRootMessage = TestDefaults.chatMessage1.copy(
            id = 21L,
            sessionId = ownedSession1.id,
            content = "Owned root message.",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            childrenMessageIds = listOf(22L, 23L)
        )
        val visibleOwnedMessage = TestDefaults.chatMessage2.copy(
            id = 22L,
            sessionId = ownedSession1.id,
            content = "Visible branch with needle inside.",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            parentMessageId = ownedRootMessage.id,
            childrenMessageIds = emptyList(),
            modelId = TestDefaults.llmModel1.id,
            settingsId = TestDefaults.modelSettings1.id,
        )
        val hiddenOwnedMessage = TestDefaults.chatMessage1.copy(
            id = 23L,
            sessionId = ownedSession1.id,
            content = "Hidden sibling with needle should stay out of the default scope.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(1.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(1.hours),
            parentMessageId = ownedRootMessage.id,
            childrenMessageIds = emptyList(),
        )
        val newerOwnedRootMessage = TestDefaults.chatMessage3.copy(
            id = 24L,
            sessionId = ownedSession2.id,
            content = "Second root message.",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            childrenMessageIds = listOf(25L),
        )
        val newerVisibleOwnedMessage = TestDefaults.chatMessage4.copy(
            id = 25L,
            sessionId = ownedSession2.id,
            content = "Newest visible NEEDLE match.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            parentMessageId = newerOwnedRootMessage.id,
            childrenMessageIds = emptyList(),
            modelId = TestDefaults.llmModel2.id,
            settingsId = TestDefaults.modelSettings2.id,
        )
        val foreignRootMessage = TestDefaults.chatMessage1.copy(
            id = 26L,
            sessionId = foreignSession.id,
            content = "Foreign root message.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            childrenMessageIds = listOf(27L)
        )
        val foreignLeafMessage = TestDefaults.chatMessage2.copy(
            id = 27L,
            sessionId = foreignSession.id,
            content = "Foreign needle should remain hidden.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(3.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(3.hours),
            parentMessageId = foreignRootMessage.id,
            childrenMessageIds = emptyList(),
            modelId = TestDefaults.llmModel1.id,
            settingsId = TestDefaults.modelSettings1.id,
        )

        testDataManager.insertUser(otherUser)
        testDataManager.setup(
            TestDataSet(
                chatSessions = listOf(ownedSession1, ownedSession2, foreignSession),
                chatMessages = listOf(
                    ownedRootMessage,
                    visibleOwnedMessage,
                    hiddenOwnedMessage,
                    newerOwnedRootMessage,
                    newerVisibleOwnedMessage,
                    foreignRootMessage,
                    foreignLeafMessage,
                ),
                sessionCurrentLeaves = listOf(
                    SessionCurrentLeafEntity(sessionId = ownedSession1.id, messageId = visibleOwnedMessage.id),
                    SessionCurrentLeafEntity(sessionId = ownedSession2.id, messageId = newerVisibleOwnedMessage.id),
                    SessionCurrentLeafEntity(sessionId = foreignSession.id, messageId = foreignLeafMessage.id),
                ),
            )
        )
        testDataManager.insertSessionOwnership(ownedSession1.id, authHelper.defaultTestUser.id)
        testDataManager.insertSessionOwnership(ownedSession2.id, authHelper.defaultTestUser.id)
        testDataManager.insertSessionOwnership(foreignSession.id, otherUser.id)

        val response = client.get(href(SearchResource.Messages(query = "needle"))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val results = response.body<List<MessageSearchResult>>()
        assertEquals(listOf(newerVisibleOwnedMessage.id, visibleOwnedMessage.id), results.map { it.messageId })
        assertEquals(listOf(ownedSession2.name, ownedSession1.name), results.map { it.sessionName })
        assertTrue(results.all { result ->
            result.snippet.substring(result.matchStartIndex, result.matchEndExclusive)
                .equals("needle", ignoreCase = true)
        })
        assertTrue(results.none { it.messageId == hiddenOwnedMessage.id })
        assertTrue(results.none { it.sessionId == foreignSession.id })
    }

    /**
     * Verifies that opting into the all-thread scope includes hidden branches while still honoring ownership.
     */
    @Test
    fun `GET search messages should include hidden branches when all threads scope is requested`() = searchTestApplication {
        val otherUser = authHelper.createTestUser(id = 2L, email = "other@example.com", username = "other")
        val ownedRootMessage = TestDefaults.chatMessage1.copy(
            id = 31L,
            sessionId = ownedSession1.id,
            content = "Owned root message.",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            childrenMessageIds = listOf(32L, 33L)
        )
        val visibleOwnedMessage = TestDefaults.chatMessage2.copy(
            id = 32L,
            sessionId = ownedSession1.id,
            content = "Visible branch with needle inside.",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            parentMessageId = ownedRootMessage.id,
            childrenMessageIds = emptyList(),
            modelId = TestDefaults.llmModel1.id,
            settingsId = TestDefaults.modelSettings1.id,
        )
        val hiddenOwnedMessage = TestDefaults.chatMessage1.copy(
            id = 33L,
            sessionId = ownedSession1.id,
            content = "Hidden sibling with needle becomes searchable in all-thread mode.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(1.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(1.hours),
            parentMessageId = ownedRootMessage.id,
            childrenMessageIds = emptyList(),
        )
        val newerOwnedRootMessage = TestDefaults.chatMessage3.copy(
            id = 34L,
            sessionId = ownedSession2.id,
            content = "Second root message.",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            childrenMessageIds = listOf(35L),
        )
        val newerVisibleOwnedMessage = TestDefaults.chatMessage4.copy(
            id = 35L,
            sessionId = ownedSession2.id,
            content = "Newest visible NEEDLE match.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            parentMessageId = newerOwnedRootMessage.id,
            childrenMessageIds = emptyList(),
            modelId = TestDefaults.llmModel2.id,
            settingsId = TestDefaults.modelSettings2.id,
        )
        val foreignRootMessage = TestDefaults.chatMessage1.copy(
            id = 36L,
            sessionId = foreignSession.id,
            content = "Foreign root message.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours),
            childrenMessageIds = listOf(37L)
        )
        val foreignLeafMessage = TestDefaults.chatMessage2.copy(
            id = 37L,
            sessionId = foreignSession.id,
            content = "Foreign needle should remain hidden.",
            createdAt = TestDefaults.DEFAULT_INSTANT.plus(3.hours),
            updatedAt = TestDefaults.DEFAULT_INSTANT.plus(3.hours),
            parentMessageId = foreignRootMessage.id,
            childrenMessageIds = emptyList(),
            modelId = TestDefaults.llmModel1.id,
            settingsId = TestDefaults.modelSettings1.id,
        )

        testDataManager.insertUser(otherUser)
        testDataManager.setup(
            TestDataSet(
                chatSessions = listOf(ownedSession1, ownedSession2, foreignSession),
                chatMessages = listOf(
                    ownedRootMessage,
                    visibleOwnedMessage,
                    hiddenOwnedMessage,
                    newerOwnedRootMessage,
                    newerVisibleOwnedMessage,
                    foreignRootMessage,
                    foreignLeafMessage,
                ),
                sessionCurrentLeaves = listOf(
                    SessionCurrentLeafEntity(sessionId = ownedSession1.id, messageId = visibleOwnedMessage.id),
                    SessionCurrentLeafEntity(sessionId = ownedSession2.id, messageId = newerVisibleOwnedMessage.id),
                    SessionCurrentLeafEntity(sessionId = foreignSession.id, messageId = foreignLeafMessage.id),
                ),
            )
        )
        testDataManager.insertSessionOwnership(ownedSession1.id, authHelper.defaultTestUser.id)
        testDataManager.insertSessionOwnership(ownedSession2.id, authHelper.defaultTestUser.id)
        testDataManager.insertSessionOwnership(foreignSession.id, otherUser.id)

        val response = client.get(
            href(SearchResource.Messages(query = "needle", scope = MessageSearchScope.ALL_THREADS))
        ) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val results = response.body<List<MessageSearchResult>>()
        assertEquals(
            listOf(newerVisibleOwnedMessage.id, hiddenOwnedMessage.id, visibleOwnedMessage.id),
            results.map { it.messageId }
        )
        assertTrue(results.none { it.sessionId == foreignSession.id })
    }

    /**
     * Verifies that blank queries are rejected with a validation error.
     */
    @Test
    fun `GET search messages should return 400 for blank query`() = searchTestApplication {
        val response = client.get(href(SearchResource.Messages(query = "   "))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals("Search query cannot be blank", error.message)
    }

    /**
     * Verifies that the search endpoint remains protected by user authentication.
     */
    @Test
    fun `GET search messages without authentication should return 401`() = searchTestApplication {
        val response = client.get(href(SearchResource.Messages(query = "needle")))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}