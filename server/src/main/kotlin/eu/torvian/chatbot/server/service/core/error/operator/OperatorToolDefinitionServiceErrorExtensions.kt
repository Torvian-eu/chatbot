package eu.torvian.chatbot.server.service.core.error.operator

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.core.OperatorToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.tool.SeedOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.tool.toApiError

/**
 * Extension functions to convert operator tool definition service errors to API errors.
 *
 * These functions transform domain-level errors from the [OperatorToolDefinitionService]
 * into standardized API error responses with appropriate HTTP status codes
 * and error details.
 */

/**
 * Converts an [UpdateOperatorToolError] to an [ApiError].
 */
fun UpdateOperatorToolError.toApiError(): ApiError = when (this) {
    is UpdateOperatorToolError.ToolNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Operator tool not found",
        "toolId" to toolId.toString()
    )

    is UpdateOperatorToolError.Forbidden -> apiError(
        apiCode = CommonApiErrorCodes.PERMISSION_DENIED,
        message = "User does not own this operator tool",
        "toolId" to toolId.toString(),
        "ownerUserId" to ownerUserId.toString()
    )

    is UpdateOperatorToolError.ValidationError -> error.toApiError()
}

/**
 * Converts a [ResetOperatorToolsError] to an [ApiError].
 */
fun ResetOperatorToolsError.toApiError(): ApiError = when (this) {
    is ResetOperatorToolsError.SeedFailed -> when (error) {
        is SeedOperatorToolsError.ToolCreationFailed ->
            error.error.toApiError()

        is SeedOperatorToolsError.LinkageFailed ->
            apiError(
                apiCode = CommonApiErrorCodes.INTERNAL,
                message = "Failed to reset operator tools: linkage error"
            )
    }
}
