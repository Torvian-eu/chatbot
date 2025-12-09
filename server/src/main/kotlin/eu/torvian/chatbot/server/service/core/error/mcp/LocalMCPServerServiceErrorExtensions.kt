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
 * Converts a [DeleteServerError] to an [ApiError].
 */
fun DeleteServerError.toApiError(): ApiError = when (this) {
    is DeleteServerError.ServerNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "MCP server not found", "serverId" to id.toString()) }

/**
 * Converts a [ValidateOwnershipError] to an [ApiError].
 */
fun ValidateOwnershipError.toApiError(): ApiError = when (this) {
    is ValidateOwnershipError.Unauthorized ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "User is not authorized to access this MCP server", "userId" to userId.toString(), "serverId" to serverId.toString())
}
