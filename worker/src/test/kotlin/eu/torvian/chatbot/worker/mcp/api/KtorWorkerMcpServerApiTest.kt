package eu.torvian.chatbot.worker.mcp.api

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestError
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestExecutor
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies error mapping behavior of [KtorWorkerMcpServerApi].
 *
 * Tests that the API correctly maps errors from [WorkerAuthenticatedRequestExecutor]
 * into [WorkerMcpServerApiError] variants. Uses a fake executor to simulate different
 * error scenarios without depending on network infrastructure.
 *
 * Tests cover:
 * - Successful response mapping
 * - Auth error mapping to [WorkerMcpServerApiError.Auth]
 * - HTTP status error mapping to [WorkerMcpServerApiError.UnexpectedHttpStatus]
 * - Transport error mapping to [WorkerMcpServerApiError.TransportError]
 */
class KtorWorkerMcpServerApiTest {

    /**
     * Verifies that successful executor results are returned as-is.
     *
     * Given an executor that returns a list of MCP servers,
     * When the API calls [WorkerMcpServerApi.getAssignedServers],
     * Then the server list is returned without modification.
     */
    @Test
    fun `successful response from executor is mapped`() = runTest {
        val mockServers = listOf(
            LocalMCPServerDto(
                id = 1, userId = 10, workerId = 20, name = "server1",
                command = "cmd1", createdAt = Instant.fromEpochMilliseconds(1000),
                updatedAt = Instant.fromEpochMilliseconds(2000)
            ),
            LocalMCPServerDto(
                id = 2, userId = 10, workerId = 20, name = "server2",
                command = "cmd2", createdAt = Instant.fromEpochMilliseconds(1000),
                updatedAt = Instant.fromEpochMilliseconds(2000)
            )
        )
        val executor = FakeExecutor(result = mockServers.right())
        val api = KtorWorkerMcpServerApi(
            client = HttpClient(),
            authenticatedRequestExecutor = executor
        )

        val result = api.getAssignedServers()

        assertTrue(result.isRight())
        assertEquals(2, result.getOrNull()?.size)
    }

    /**
     * Verifies that executor auth errors map to [WorkerMcpServerApiError.Auth].
     *
     * Given an executor that returns an auth error,
     * When the API calls [WorkerMcpServerApi.getAssignedServers],
     * Then a [WorkerMcpServerApiError.Auth] is returned with the underlying auth manager error.
     */
    @Test
    fun `executor auth error is mapped to WorkerMcpServerApiError Auth`() = runTest {
        val authError = WorkerAuthManagerError.BlankChallengePayload
        val executorError = WorkerAuthenticatedRequestError.Auth(authError)
        val executor = FakeExecutor(result = executorError.left())
        val api = KtorWorkerMcpServerApi(
            client = HttpClient(),
            authenticatedRequestExecutor = executor
        )

        val result = api.getAssignedServers()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerMcpServerApiError.Auth)
    }

    /**
     * Verifies that executor HTTP status errors map to [WorkerMcpServerApiError.UnexpectedHttpStatus].
     *
     * Given an executor that returns an HTTP status error,
     * When the API calls [WorkerMcpServerApi.getAssignedServers],
     * Then an [WorkerMcpServerApiError.UnexpectedHttpStatus] is returned with the status code and response body.
     */
    @Test
    fun `executor http status error is mapped to WorkerMcpServerApiError UnexpectedHttpStatus`() = runTest {
        val executorError = WorkerAuthenticatedRequestError.HttpStatus(
            operation = "fetch assigned servers",
            statusCode = 500,
            responseBody = "server error"
        )
        val executor = FakeExecutor(result = executorError.left())
        val api = KtorWorkerMcpServerApi(
            client = HttpClient(),
            authenticatedRequestExecutor = executor
        )

        val result = api.getAssignedServers()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as WorkerMcpServerApiError.UnexpectedHttpStatus
        assertEquals(500, error.statusCode)
        assertEquals("server error", error.description)
    }

    /**
     * Verifies that executor transport errors map to [WorkerMcpServerApiError.TransportError].
     *
     * Given an executor that returns a transport error,
     * When the API calls [WorkerMcpServerApi.getAssignedServers],
     * Then a [WorkerMcpServerApiError.TransportError] is returned with the operation name and error reason.
     */
    @Test
    fun `executor transport error is mapped to WorkerMcpServerApiError TransportError`() = runTest {
        val executorError = WorkerAuthenticatedRequestError.Transport(
            operation = "fetch assigned servers",
            reason = "connection timeout"
        )
        val executor = FakeExecutor(result = executorError.left())
        val api = KtorWorkerMcpServerApi(
            client = HttpClient(),
            authenticatedRequestExecutor = executor
        )

        val result = api.getAssignedServers()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull() as WorkerMcpServerApiError.TransportError
        assertEquals("connection timeout", error.message)
    }

    /**
     * Fake [WorkerAuthenticatedRequestExecutor] used for unit testing.
     *
     * Returns a pre-configured result regardless of the operation or token provided.
     * This allows tests to simulate executor behavior without actual network calls.
     */
    private class FakeExecutor(
        /**
         * Pre-configured result to return from all [execute] calls.
         *
         * Either a success (list of servers) or an error, depending on test scenario.
         */
        private val result: Either<WorkerAuthenticatedRequestError, List<LocalMCPServerDto>>
    ) : WorkerAuthenticatedRequestExecutor {
        /**
         * Returns the pre-configured result, ignoring the operation and block.
         *
         * Uses type-casting to return the pre-configured list result as the generic type.
         * This is safe because tests control the types and expected results.
         *
         * @param operation Ignored; only used for context in real implementations.
         * @param block Ignored; only used for actual API calls in real implementations.
         * @return The pre-configured result cast to the expected type.
         */
        override suspend fun <T> execute(
            operation: String,
            block: suspend (accessToken: String) -> T
        ): Either<WorkerAuthenticatedRequestError, T> {
            @Suppress("UNCHECKED_CAST")
            return result as Either<WorkerAuthenticatedRequestError, T>
        }
    }
}
