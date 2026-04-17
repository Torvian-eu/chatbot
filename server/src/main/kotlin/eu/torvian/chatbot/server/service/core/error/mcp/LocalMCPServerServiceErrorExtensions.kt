package eu.torvian.chatbot.server.service.core.error.mcp

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Extension functions to convert Local MCP Server service errors to API errors.
 *
 * These functions transform domain-level errors from the LocalMCPServerService
 * into standardized API error responses with appropriate HTTP status codes
 * and error details.
 */

/**
 * Converts a [LocalMCPServerServiceError] to an [ApiError].
 *
 * @receiver Service error to convert.
 * @return Structured API error response.
 */
fun LocalMCPServerServiceError.toApiError(): ApiError = when (this) {
    is LocalMCPServerNotFoundError ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "MCP server not found", "serverId" to serverId.toString())

    is LocalMCPServerUnauthorizedError ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "User is not authorized to access this MCP server",
            "userId" to userId.toString(),
            "serverId" to serverId.toString()
        )

    is LocalMCPServerWorkerNotFoundError ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Worker not found", "workerId" to workerId.toString())

    is LocalMCPServerWorkerOwnershipMismatchError ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "Worker is not owned by the requesting user",
            "userId" to userId.toString(),
            "workerId" to workerId.toString(),
            "workerOwnerUserId" to workerOwnerUserId.toString()
        )

    is LocalMCPServerSecretStorageError ->
        apiError(
            CommonApiErrorCodes.INTERNAL,
            "Failed to securely store secret environment variable",
            "variableKey" to variableKey
        )

    is LocalMCPServerSecretResolutionError ->
        apiError(
            CommonApiErrorCodes.INTERNAL,
            "Failed to resolve secret environment variable",
            "variableKey" to variableKey,
            "alias" to alias
        )

    is LocalMCPServerValidationError ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid Local MCP server configuration", "reason" to reason)
}
