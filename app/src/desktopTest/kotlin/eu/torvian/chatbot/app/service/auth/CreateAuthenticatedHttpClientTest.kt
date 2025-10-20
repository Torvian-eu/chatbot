package eu.torvian.chatbot.app.service.auth

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

/**
 * Comprehensive test class for [createAuthenticatedHttpClient] function.
 *
 * Tests cover:
 * - HTTP client creation with authentication
 * - Token loading and refresh mechanisms through actual HTTP requests
 * - Error handling scenarios
 * - Event emission for authentication failures
 * - Integration with TokenStorage and EventBus
 */
class CreateAuthenticatedHttpClientTest {

    private val baseUri = "http://localhost:8080"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var mockTokenStorage: TokenStorage
    private lateinit var mockEventBus: EventBus

    @BeforeTest
    fun setup() {
        mockTokenStorage = mockk()
        mockEventBus = mockk(relaxed = true)

        // Default successful token responses - using answers to ensure fresh responses each time
        coEvery { mockTokenStorage.getAccessToken() } answers { "default-access-token".right() }
        coEvery { mockTokenStorage.getRefreshToken() } answers { "default-refresh-token".right() }
        coEvery { mockTokenStorage.getExpiry() } answers { Clock.System.now().right() }
        // Updated to include permissions parameter
        coEvery { mockTokenStorage.saveAuthData(any(), any(), any(), any(), any()) } answers { Unit.right() }
        coEvery { mockTokenStorage.clearAuthData() } answers { Unit.right() }
    }

    @AfterTest
    fun cleanup() {
        clearMocks(mockTokenStorage, mockEventBus)
    }

    @Test
    fun `createAuthenticatedHttpClient should create client with auth plugin installed`() = runTest {
        // Given
        val mockEngine = MockEngine { respond("OK", HttpStatusCode.OK) }
        val baseHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        coEvery { mockTokenStorage.getAccessToken() } returns "access-token".right()
        coEvery { mockTokenStorage.getRefreshToken() } returns "refresh-token".right()

        // When
        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // Then
        assertNotNull(client)
        assertTrue(client.pluginOrNull(Auth) != null, "Auth plugin should be installed")

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }

    @Test
    fun `should load tokens and make authenticated request successfully`() = runTest {
        // Given
        val accessToken = "valid-access-token"
        val refreshToken = "valid-refresh-token"

        coEvery { mockTokenStorage.getAccessToken() } returns accessToken.right()
        coEvery { mockTokenStorage.getRefreshToken() } returns refreshToken.right()

        // Create a mock engine that expects the authorization header
        val mockEngine = MockEngine { request ->
            when {
                request.headers[HttpHeaders.Authorization] == "Bearer $accessToken" -> {
                    respond(
                        content = """{"message": "success"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                else -> {
                    respond(
                        content = "Unauthorized",
                        status = HttpStatusCode.Unauthorized
                    )
                }
            }
        }

        val baseHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // When - Make a request to a protected endpoint
        val response = client.get("/api/v1/protected")

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"message": "success"}""", response.bodyAsText())

        // Verify tokens were loaded
        coVerify(exactly = 1) { mockTokenStorage.getAccessToken() }
        coVerify(exactly = 1) { mockTokenStorage.getRefreshToken() }
        coVerify(exactly = 0) { mockEventBus.emitEvent(any<AuthenticationFailureEvent>()) }

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }

    @Test
    fun `should emit AuthenticationFailureEvent when access token is missing`() = runTest {
        // Given
        coEvery { mockTokenStorage.getAccessToken() } returns TokenStorageError.NotFound("Access token not found")
            .left()
        coEvery { mockTokenStorage.getRefreshToken() } returns "refresh-token".right()

        val mockEngine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val baseHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // When - Make a request that will trigger token loading
        val response = client.get("/api/v1/protected")

        // Then
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // Verify authentication failure event was emitted
        coVerify(exactly = 1) { mockEventBus.emitEvent(any<AuthenticationFailureEvent>()) }

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }

    @Test
    fun `should emit AuthenticationFailureEvent when refresh token is missing`() = runTest {
        // Given
        coEvery { mockTokenStorage.getAccessToken() } returns "access-token".right()
        coEvery { mockTokenStorage.getRefreshToken() } returns TokenStorageError.NotFound("Refresh token not found")
            .left()

        val mockEngine = MockEngine { respond("Unauthorized", HttpStatusCode.Unauthorized) }
        val baseHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // When - Make a request that will trigger token loading
        val response = client.get("/api/v1/protected")

        // Then
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // Verify authentication failure event was emitted
        coVerify(exactly = 1) { mockEventBus.emitEvent(any<AuthenticationFailureEvent>()) }

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }

    @Test
    fun `should refresh tokens when receiving 401 response and save new tokens`() = runTest {
        // Given
        val oldAccessToken = "old-access-token"
        val oldRefreshToken = "old-refresh-token"
        val newAccessToken = "new-access-token"
        val newRefreshToken = "new-refresh-token"
        val newExpiresAt = Clock.System.now() + 2.hours
        val now = Clock.System.now()

        coEvery { mockTokenStorage.getAccessToken() } returns oldAccessToken.right()
        coEvery { mockTokenStorage.getRefreshToken() } returns oldRefreshToken.right()

        var requestCount = 0
        val mainMockEngine = MockEngine { request ->
            requestCount++
            when {
                // First request with old token - return 401 to trigger refresh
                requestCount == 1 && request.headers[HttpHeaders.Authorization] == "Bearer $oldAccessToken" -> {
                    respond("Unauthorized", HttpStatusCode.Unauthorized)
                }
                // Second request with new token after refresh - return success
                requestCount > 1 && request.headers[HttpHeaders.Authorization] == "Bearer $newAccessToken" -> {
                    respond(
                        content = """{"message": "success with new token"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                else -> {
                    respond("Unexpected request", HttpStatusCode.BadRequest)
                }
            }
        }

        // Create mock unauthenticated client for refresh token requests
        val refreshMockEngine = MockEngine { request ->
            when {
                // Use href for AuthResource.Refresh()
                request.url.encodedPath.contains(href(AuthResource.Refresh())) -> {
                    respond(
                        content = json.encodeToString(
                            LoginResponse.serializer(),
                            LoginResponse(
                                user = User(
                                    id = 1L,
                                    username = "testuser",
                                    email = "test@example.com",
                                    status = UserStatus.ACTIVE,
                                    createdAt = now,
                                    lastLogin = now
                                ),
                                accessToken = newAccessToken,
                                refreshToken = newRefreshToken,
                                expiresAt = newExpiresAt
                            )
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                else -> {
                    respond("Not found", HttpStatusCode.NotFound)
                }
            }
        }

        val baseHttpClient = HttpClient(mainMockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(refreshMockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // When - Make a request that will trigger token refresh
        val response = client.get("/api/v1/protected")

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"message": "success with new token"}""", response.bodyAsText())

        // Verify tokens were saved after refresh - include permissions argument in verification
        coVerify(exactly = 1) { mockTokenStorage.saveAuthData(newAccessToken, newRefreshToken, newExpiresAt, any(), any()) }
        coVerify(exactly = 0) { mockEventBus.emitEvent(any<AuthenticationFailureEvent>()) }

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }

    @Test
    fun `should emit AuthenticationFailureEvent when refresh token request fails`() = runTest {
        // Given
        val oldAccessToken = "old-access-token"
        val oldRefreshToken = "old-refresh-token"

        coEvery { mockTokenStorage.getAccessToken() } returns oldAccessToken.right()
        coEvery { mockTokenStorage.getRefreshToken() } returns oldRefreshToken.right()

        val mainMockEngine = MockEngine { request ->
            when {
                request.headers[HttpHeaders.Authorization] == "Bearer $oldAccessToken" -> {
                    respond("Unauthorized", HttpStatusCode.Unauthorized)
                }

                else -> {
                    respond("Unauthorized", HttpStatusCode.Unauthorized)
                }
            }
        }

        // Create mock unauthenticated client that fails refresh requests
        val refreshMockEngine = MockEngine { request ->
            when {
                // Use href for AuthResource.Refresh()
                request.url.encodedPath.contains(href(AuthResource.Refresh())) -> {
                    respond("Bad Request", HttpStatusCode.BadRequest)
                }

                else -> {
                    respond("Not found", HttpStatusCode.NotFound)
                }
            }
        }

        val baseHttpClient = HttpClient(mainMockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(refreshMockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // When - Make a request that will trigger failed token refresh
        val response = client.get("/api/v1/protected")

        // Then
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // Verify authentication failure event was emitted due to refresh failure
        coVerify(atLeast = 1) { mockEventBus.emitEvent(any<AuthenticationFailureEvent>()) }
        coVerify(exactly = 1) { mockTokenStorage.clearAuthData() }

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }

    @Test
    fun `should exclude auth endpoints from automatic authentication`() = runTest {
        // Given
        coEvery { mockTokenStorage.getAccessToken() } returns "access-token".right()
        coEvery { mockTokenStorage.getRefreshToken() } returns "refresh-token".right()

        // Mock engine that checks if Authorization header is NOT present for auth endpoints
        val mockEngine = MockEngine { request ->
            when {
                // Use href for AuthResource()
                request.url.encodedPath.startsWith(href(AuthResource())) -> {
                    // Auth endpoints should not have Authorization header due to sendWithoutRequest
                    if (request.headers[HttpHeaders.Authorization] == null) {
                        respond(
                            content = """{"message": "auth endpoint accessed without auth header"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond("Should not have auth header", HttpStatusCode.BadRequest)
                    }
                }

                else -> {
                    respond("Protected endpoint", HttpStatusCode.OK)
                }
            }
        }

        val baseHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }
        val unauthenticatedHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(Resources)
        }

        val client = createAuthenticatedHttpClient(
            tokenStorage = mockTokenStorage,
            unauthenticatedHttpClient = unauthenticatedHttpClient,
            eventBus = mockEventBus,
            baseClient = baseHttpClient
        )

        // When - Make a request to an auth endpoint
        val response = client.get(href(AuthResource.Login()))

        // Then
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"message": "auth endpoint accessed without auth header"}""", response.bodyAsText())

        client.close()
        unauthenticatedHttpClient.close()
        baseHttpClient.close()
    }
}
