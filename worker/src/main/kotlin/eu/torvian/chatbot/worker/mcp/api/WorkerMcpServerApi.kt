package eu.torvian.chatbot.worker.mcp.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError

/**
 * HTTP API client for fetching worker-assigned MCP server configurations.
 *
 * This abstraction isolates the worker runtime from Ktor and maps transport problems
 * into logical error values.
 */
interface WorkerMcpServerApi {
    /**
     * Fetches all MCP server configurations assigned to this worker.
     *
     * @return List of assigned server configurations or a logical API error.
     */
    suspend fun getAssignedServers(): Either<WorkerMcpServerApiError, List<LocalMCPServerDto>>
}

/**
 * Logical errors surfaced by the worker MCP server HTTP API.
 */
sealed interface WorkerMcpServerApiError {
    /**
     * Worker authentication or token management failed.
     *
     * @property error The underlying auth manager error.
     */
    data class Auth(val error: WorkerAuthManagerError) : WorkerMcpServerApiError

    /**
     * Unexpected HTTP status returned by the server.
     *
     * @property operation Human-readable operation name.
     * @property statusCode HTTP status code returned by the server.
     * @property description Optional error details from server response body.
     */
    data class UnexpectedHttpStatus(
        val operation: String,
        val statusCode: Int,
        val description: String? = null
    ) : WorkerMcpServerApiError

    /**
     * A network or serialization error occurred during the API call.
     *
     * @property operation Human-readable operation name.
     * @property message Error description.
     */
    data class TransportError(
        val operation: String,
        val message: String
    ) : WorkerMcpServerApiError
}
