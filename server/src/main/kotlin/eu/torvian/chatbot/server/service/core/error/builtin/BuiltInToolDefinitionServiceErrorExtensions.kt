package eu.torvian.chatbot.server.service.core.error.builtin

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.core.BuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.tool.SeedBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.toApiError

/**
 * Extension functions to convert built-in worker tool definition service errors to API errors.
 *
 * These functions transform domain-level errors from the [BuiltInToolDefinitionService]
 * into standardized API error responses with appropriate HTTP status codes
 * and error details.
 */

/**
 * Converts a [GetBuiltInToolsError] to an [ApiError].
 */
fun GetBuiltInToolsError.toApiError(): ApiError = when (this) {
    is GetBuiltInToolsError.WorkerNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Worker not found",
        "workerId" to workerId.toString()
    )

    is GetBuiltInToolsError.Forbidden -> apiError(
        apiCode = CommonApiErrorCodes.PERMISSION_DENIED,
        message = "User does not own the referenced worker",
        "workerId" to workerId.toString(),
        "ownerUserId" to workerOwnerUserId.toString()
    )
}

/**
 * Converts an [UpdateBuiltInToolError] to an [ApiError].
 */
fun UpdateBuiltInToolError.toApiError(): ApiError = when (this) {
    is UpdateBuiltInToolError.ToolNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Built-in tool not found",
        "toolId" to toolId.toString()
    )

    is UpdateBuiltInToolError.Forbidden -> apiError(
        apiCode = CommonApiErrorCodes.PERMISSION_DENIED,
        message = "User does not own the worker that owns this tool",
        "workerId" to workerId.toString(),
        "ownerUserId" to workerOwnerUserId.toString()
    )

    is UpdateBuiltInToolError.ValidationError -> error.toApiError()
}

/**
 * Converts a [ResetBuiltInToolsError] to an [ApiError].
 */
fun ResetBuiltInToolsError.toApiError(): ApiError = when (this) {
    is ResetBuiltInToolsError.WorkerNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "Worker not found",
        "workerId" to workerId.toString()
    )

    is ResetBuiltInToolsError.Forbidden -> apiError(
        apiCode = CommonApiErrorCodes.PERMISSION_DENIED,
        message = "User does not own the referenced worker",
        "workerId" to workerId.toString(),
        "ownerUserId" to workerOwnerUserId.toString()
    )

    is ResetBuiltInToolsError.SeedFailed -> when (error) {
        is SeedBuiltInToolsError.ToolCreationFailed ->
            error.error.toApiError()

        is SeedBuiltInToolsError.LinkageFailed ->
            apiError(
                apiCode = CommonApiErrorCodes.INTERNAL,
                message = "Failed to reset built-in tools: linkage error"
            )
    }
}
