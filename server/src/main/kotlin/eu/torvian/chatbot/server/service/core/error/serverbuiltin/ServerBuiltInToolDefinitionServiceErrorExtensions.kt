package eu.torvian.chatbot.server.service.core.error.serverbuiltin

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.toApiError

/**
 * Extension functions to convert server built-in tool definition service errors to API errors.
 *
 * These functions transform domain-level errors from the [ServerBuiltInToolDefinitionService] into
 * standardized API error responses with appropriate HTTP status codes and error details.
 */

/**
 * Converts an [UpdateServerBuiltInToolError] to an [ApiError].
 */
fun UpdateServerBuiltInToolError.toApiError(): ApiError = when (this) {
    is UpdateServerBuiltInToolError.ToolNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Server built-in tool not found",
        "toolId" to toolId.toString()
    )

    is UpdateServerBuiltInToolError.Forbidden -> apiError(
        apiCode = CommonApiErrorCodes.PERMISSION_DENIED,
        message = "User does not own this server built-in tool",
        "toolId" to toolId.toString()
    )

    is UpdateServerBuiltInToolError.ValidationError -> error.toApiError()
}

/**
 * Converts a [ResetServerBuiltInToolsError] to an [ApiError].
 */
fun ResetServerBuiltInToolsError.toApiError(): ApiError = when (this) {
    is ResetServerBuiltInToolsError.SeedFailed -> when (error) {
        is SeedServerBuiltInToolsError.ToolCreationFailed ->
            error.error.toApiError()

        is SeedServerBuiltInToolsError.LinkageFailed ->
            apiError(
                apiCode = CommonApiErrorCodes.INTERNAL,
                message = "Failed to reset server built-in tools: linkage error"
            )

        is SeedServerBuiltInToolsError.ToolDeletionFailed ->
            apiError(
                apiCode = CommonApiErrorCodes.INTERNAL,
                message = "Failed to reset server built-in tools: deletion error"
            )
    }
}
