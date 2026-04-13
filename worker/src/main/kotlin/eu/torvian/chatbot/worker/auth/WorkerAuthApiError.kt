package eu.torvian.chatbot.worker.auth

/**
 * Logical errors surfaced by the worker auth HTTP API.
 */
sealed interface WorkerAuthApiError {
    /**
     * Indicates that the server could not find the worker identity referenced by the request.
     *
     * @property workerUid Worker UID that was rejected by the server.
     */
    data class WorkerNotFound(val workerUid: String) : WorkerAuthApiError

    /**
     * Indicates that the server rejected the worker certificate credentials.
     *
     * @property reason Human-readable rejection reason reported by the server or client mapping.
     */
    data class InvalidCredentials(val reason: String) : WorkerAuthApiError

    /**
     * Indicates a non-HTTP transport failure (DNS, timeout, TLS, serialization, etc.).
     *
     * @property operation Name of the operation that failed.
     * @property reason Human-readable reason captured from the thrown exception.
     */
    data class TransportFailure(val operation: String, val reason: String) : WorkerAuthApiError

    /**
     * Indicates an unexpected HTTP status was returned for a worker auth operation.
     *
     * @property operation Name of the operation that failed.
     * @property statusCode HTTP status code returned by the server.
     * @property description Optional human-readable description or server message.
     */
    data class UnexpectedHttpStatus(val operation: String, val statusCode: Int, val description: String? = null) : WorkerAuthApiError
}

