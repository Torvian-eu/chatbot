package eu.torvian.chatbot.worker.auth

/**
 * Logical errors surfaced by authenticated request execution.
 *
 * Auth errors indicate token acquisition or forced reauth failures.
 * HTTP status errors indicate server rejection or unexpected status codes.
 * Transport errors indicate network or serialization failures.
 */
sealed interface WorkerAuthenticatedRequestError {
    /**
     * Token acquisition or forced reauth failed.
     *
     * @property error The underlying auth manager error.
     */
    data class Auth(val error: WorkerAuthManagerError) : WorkerAuthenticatedRequestError

    /**
     * Server returned an unexpected HTTP status code.
     *
     * @property operation Name of the operation that encountered the status.
     * @property statusCode HTTP status code returned by the server.
     * @property responseBody Optional response body extracted from the failed response.
     */
    data class HttpStatus(
        val operation: String,
        val statusCode: Int,
        val responseBody: String? = null
    ) : WorkerAuthenticatedRequestError

    /**
     * A network, serialization, or other transport-layer error occurred.
     *
     * @property operation Name of the operation that failed.
     * @property reason Human-readable reason from the thrown exception.
     */
    data class Transport(
        val operation: String,
        val reason: String
    ) : WorkerAuthenticatedRequestError
}