package eu.torvian.chatbot.server.service.core.error.mcp

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Converts runtime-control errors into API error payloads.
 *
 * @receiver Runtime-control error to transform.
 * @return Structured API error suitable for route responses.
 */
fun LocalMCPRuntimeControlError.toApiError(): ApiError = when (this) {
    is LocalMCPRuntimeControlServerNotFoundError ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "MCP server not found", "serverId" to serverId.toString())

    is LocalMCPRuntimeControlUnauthorizedError ->
        apiError(
            CommonApiErrorCodes.PERMISSION_DENIED,
            "User is not authorized to control this MCP server",
            "userId" to userId.toString(),
            "serverId" to serverId.toString()
        )

    is LocalMCPRuntimeControlRuntimeUnavailableError ->
        apiError(
            CommonApiErrorCodes.UNAVAILABLE,
            "Local MCP runtime control is unavailable",
            "serverId" to serverId.toString(),
            "reason" to reason
        )

    is LocalMCPRuntimeControlInternalError ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to process Local MCP runtime control request", "reason" to message)
}


