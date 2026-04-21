package eu.torvian.chatbot.worker.auth

import arrow.core.Either

/**
 * Shared authenticated request executor for worker REST API calls.
 *
 * This abstraction centralizes auth token acquisition and 401/403 retry logic,
 * ensuring that all authenticated REST calls follow a consistent pattern and
 * share the same token refresh behavior. It owns the auth/retry policy but
 * delegates outer cycle retry to the caller's connection loop.
 */
interface WorkerAuthenticatedRequestExecutor {
    /**
     * Executes an authenticated REST operation with automatic token refresh on 401/403.
     *
     * Behavior:
     * - Acquires a valid token via WorkerAuthManager
     * - Invokes the operation block with the token
     * - On 401 or 403: calls forceReauthenticate and retries exactly once
     * - For other HTTP statuses: returns immediately without retry
     * - For transport errors: returns immediately without retry
     *
     * @param operation Human-readable operation name used for error reporting.
     * @param block Suspended operation that receives the access token and returns a result.
     * @return Either a logical error or the operation result.
     */
    suspend fun <T> execute(
        operation: String,
        block: suspend (accessToken: String) -> T
    ): Either<WorkerAuthenticatedRequestError, T>
}

