package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies behavior of [DefaultWorkerAuthenticatedRequestExecutor].
 *
 * Tests cover:
 * - Successful requests using cached tokens
 * - Auth acquisition failures
 * - Transport error handling
 * - Token refresh on auth rejection
 */
class DefaultWorkerAuthenticatedRequestExecutorTest {

    /**
     * Verifies that executor uses cached token when the operation succeeds.
     *
     * Given a valid cached token and a successful operation,
     * When the executor executes the operation,
     * Then the operation receives the cached token and the result is returned.
     */
    @Test
    fun `uses cached token when request succeeds`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("cached-token", Instant.parse("2026-04-10T12:30:00Z")),
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        val result = executor.execute("test operation") { token ->
            assertEquals("cached-token", token)
            "success"
        }

        assertTrue(result.isRight())
        assertEquals("success", result.getOrNull())
    }

    /**
     * Verifies that executor returns auth error when initial token acquisition fails.
     *
     * Given an auth manager that fails to provide a valid token,
     * When the executor attempts to execute an operation,
     * Then an [WorkerAuthenticatedRequestError.Auth] error is returned without invoking the operation.
     */
    @Test
    fun `returns auth error when initial getValidToken fails`() = runTest {
        val authManager = FakeAuthManager(
            getValidTokenError = WorkerAuthManagerError.BlankChallengePayload
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        val result = executor.execute("test operation") { "should not run" }

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthenticatedRequestError.Auth)
    }

    /**
     * Verifies that executor returns transport error when operation throws an exception.
     *
     * Given an operation that throws a non-HTTP exception,
     * When the executor executes the operation,
     * Then a [WorkerAuthenticatedRequestError.Transport] error is returned with the exception message.
     */
    @Test
    fun `returns transport error on exception`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        val result = executor.execute("test op") { _ ->
            throw RuntimeException("timeout")
        }

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as WorkerAuthenticatedRequestError.Transport
        assertEquals("timeout", error.reason)
    }

    /**
     * Verifies that initial auth acquisition failure is returned without attempting operation.
     *
     * Given an auth manager that fails on initial token acquisition,
     * When the executor attempts to execute an operation,
     * Then it returns the auth error without calling the operation block.
     */
    @Test
    fun `returns auth error when initial auth acquisition fails`() = runTest {
        val authManager = FakeAuthManager(
            getValidTokenError = WorkerAuthManagerError.BlankChallengePayload
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        var operationCalled = false
        val result = executor.execute("test op") { _ ->
            operationCalled = true
            "should not execute"
        }

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthenticatedRequestError.Auth)
        assertFalse(operationCalled, "operation should not be called when initial auth fails")
    }

    /**
     * Verifies that 401 response triggers forceReauthenticate and retries exactly once with success.
     *
     * Given an operation that fails with 401 on first call and succeeds on retry,
     * And an auth manager with a refreshed token,
     * When the executor executes the operation,
     * Then forceReauthenticate is called exactly once and the retry succeeds.
     */
    @Test
    fun `401 response triggers forceReauthenticate and retries exactly once with success`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("initial-token", Instant.parse("2026-04-10T12:30:00Z")),
            refreshedToken = StoredServiceToken("refreshed-token", Instant.parse("2026-04-10T13:00:00Z"))
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        var callCount = 0
        val result = executor.execute("test operation") { token ->
            callCount++
            when (callCount) {
                1 -> {
                    // First call fails with 401
                    assertEquals("initial-token", token)
                    throw createResponseException(401)
                }
                2 -> {
                    // Second call succeeds
                    assertEquals("refreshed-token", token)
                    "success"
                }
                else -> error("Operation should not be called more than twice")
            }
        }

        assertTrue(result.isRight())
        assertEquals("success", result.getOrNull())
        assertEquals(2, callCount)
        assertEquals(1, authManager.forceReauthenticateCalls)
    }

    /**
     * Verifies that 403 response triggers forceReauthenticate and retries exactly once with success.
     *
     * Given an operation that fails with 403 on first call and succeeds on retry,
     * And an auth manager with a refreshed token,
     * When the executor executes the operation,
     * Then forceReauthenticate is called exactly once and the retry succeeds.
     */
    @Test
    fun `403 response triggers forceReauthenticate and retries exactly once with success`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("initial-token", Instant.parse("2026-04-10T12:30:00Z")),
            refreshedToken = StoredServiceToken("refreshed-token", Instant.parse("2026-04-10T13:00:00Z"))
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        var callCount = 0
        val result = executor.execute("test operation") { token ->
            callCount++
            when (callCount) {
                1 -> {
                    // First call fails with 403
                    assertEquals("initial-token", token)
                    throw createResponseException(403)
                }
                2 -> {
                    // Second call succeeds
                    assertEquals("refreshed-token", token)
                    "success"
                }
                else -> error("Operation should not be called more than twice")
            }
        }

        assertTrue(result.isRight())
        assertEquals("success", result.getOrNull())
        assertEquals(2, callCount)
        assertEquals(1, authManager.forceReauthenticateCalls)
    }

    /**
     * Verifies that forced reauth failure after 401 returns WorkerAuthenticatedRequestError.Auth.
     *
     * Given an operation that fails with 401,
     * And forceReauthenticate that also fails,
     * When the executor executes the operation,
     * Then an auth error wrapping the forced reauth failure is returned without retrying the operation.
     */
    @Test
    fun `forced reauth failure after 401 returns Auth error`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("initial-token", Instant.parse("2026-04-10T12:30:00Z")),
            forceReauthenticateError = WorkerAuthManagerError.BlankChallengePayload
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        var callCount = 0
        val result = executor.execute("test operation") { token ->
            callCount++
            // Only called once; no retry occurs because forced reauth fails
            assertEquals("initial-token", token)
            throw createResponseException(401)
        }

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthenticatedRequestError.Auth)
        assertEquals(1, callCount, "Operation should only be called once when forced reauth fails")
        assertEquals(1, authManager.forceReauthenticateCalls)
    }

    /**
     * Verifies that non-401/403 HTTP status does not trigger forceReauthenticate.
     *
     * Given an operation that fails with a non-auth HTTP status (e.g., 500),
     * When the executor executes the operation,
     * Then the error is returned immediately without calling forceReauthenticate.
     */
    @Test
    fun `non-401-403 HTTP status does not trigger forceReauthenticate`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("token", Instant.parse("2026-04-10T12:30:00Z"))
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        val result = executor.execute("test op") { _ ->
            throw createResponseException(500)
        }

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthenticatedRequestError.HttpStatus)
        assertEquals(500, (error as WorkerAuthenticatedRequestError.HttpStatus).statusCode)
        assertEquals(0, authManager.forceReauthenticateCalls, "forceReauthenticate should not be called for 500")
    }

    /**
     * Verifies that retry failure after forced reauth returns the retry error.
     *
     * Given an operation that fails with 401 on first call and fails again on retry,
     * And forceReauthenticate that succeeds,
     * When the executor executes the operation,
     * Then the second error is returned (not converted to Auth error).
     */
    @Test
    fun `retry failure after forced reauth returns the retry error`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("initial-token", Instant.parse("2026-04-10T12:30:00Z")),
            refreshedToken = StoredServiceToken("refreshed-token", Instant.parse("2026-04-10T13:00:00Z"))
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        var callCount = 0
        val result = executor.execute("test operation") {
            callCount++
            when (callCount) {
                1 -> throw createResponseException(401)
                2 -> throw createResponseException(500)
                else -> error("Unexpected call")
            }
        }

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthenticatedRequestError.HttpStatus)
        assertEquals(500, (error as WorkerAuthenticatedRequestError.HttpStatus).statusCode)
        assertEquals(2, callCount)
        assertEquals(1, authManager.forceReauthenticateCalls)
    }

    /**
     * Verifies that forceReauthenticate is called exactly once for a single 401 rejection.
     *
     * Given an operation that fails with 401 and then succeeds,
     * When the executor executes the operation,
     * Then forceReauthenticate is called exactly one time, not more.
     */
    @Test
    fun `forceReauthenticate is called exactly once for single 401 rejection`() = runTest {
        val authManager = FakeAuthManager(
            cachedToken = StoredServiceToken("initial-token", Instant.parse("2026-04-10T12:30:00Z")),
            refreshedToken = StoredServiceToken("refreshed-token", Instant.parse("2026-04-10T13:00:00Z"))
        )
        val executor = DefaultWorkerAuthenticatedRequestExecutor(authManager)

        var callCount = 0
        executor.execute("test op") {
            callCount++
            if (callCount == 1) {
                throw createResponseException(401)
            }
            "success"
        }

        assertEquals(1, authManager.forceReauthenticateCalls, "forceReauthenticate must be called exactly once")
    }

    /**
     * Fake [WorkerAuthManager] used for unit testing.
     *
     * Allows test scenarios to control token availability and auth failures.
     *
     * @property cachedToken Token returned by [getValidToken] when no error is configured.
     * @property refreshedToken Token returned by [forceReauthenticate] when no error is configured.
     * @property getValidTokenError Error returned by [getValidToken] instead of a token, if set.
     * @property forceReauthenticateError Error returned by [forceReauthenticate] instead of a token, if set.
     */
    private class FakeAuthManager(
        private val cachedToken: StoredServiceToken? = null,
        private val refreshedToken: StoredServiceToken? = null,
        private val getValidTokenError: WorkerAuthManagerError? = null,
        private val forceReauthenticateError: WorkerAuthManagerError? = null
    ) : WorkerAuthManager {
        /**
         * Counter tracking how many times [forceReauthenticate] was called.
         *
         * Allows tests to verify that retry logic triggered forced reauth.
         */
        var forceReauthenticateCalls = 0

        /**
         * Returns either a cached token or a configured error.
         *
         * @return The cached token if no error is configured; otherwise the configured error.
         * @throws IllegalStateException if neither token nor error is configured.
         */
        override suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken> {
            return getValidTokenError?.left() ?: (cachedToken?.right()
                ?: throw IllegalStateException("FakeAuthManager needs token or error"))
        }

        /**
         * Increments the forced reauth counter and returns either a refreshed token or a configured error.
         *
         * @return The refreshed token if configured; otherwise the configured error.
         * @throws IllegalStateException if neither token nor error is configured.
         */
        override suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken> {
            forceReauthenticateCalls++
            return forceReauthenticateError?.left() ?: (refreshedToken?.right()
                ?: throw IllegalStateException("FakeAuthManager needs refreshedToken or forceReauthenticateError"))
        }
    }

    /**
     * Helper to create a ResponseException with the given status code.
     *
     * Creates a minimal mock exception suitable for testing retry logic.
     * The exception is constructed without a real HTTP response object,
     * instead using a simple stub that provides only the status code.
     *
     * @param statusCode HTTP status code for the mock exception.
     * @return A ResponseException with the specified status code.
     */
    private fun createResponseException(statusCode: Int): ResponseException {
        // Create a minimal mock that provides only what ResponseException needs.
        // We use mockk for this since HttpResponse is complex to create.
        val mockHttpResponse = mockk<io.ktor.client.statement.HttpResponse> {
            coEvery { status } returns HttpStatusCode.fromValue(statusCode)
            coEvery { bodyAsText() } returns ""
        }
        return ResponseException(mockHttpResponse, "HTTP $statusCode")
    }
}
