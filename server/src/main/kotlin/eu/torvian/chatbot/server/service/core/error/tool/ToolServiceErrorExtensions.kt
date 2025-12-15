package eu.torvian.chatbot.server.service.core.error.tool

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Extension functions to convert tool service errors to API errors.
 *
 * These functions transform domain-level errors from the ToolService
 * into standardized API error responses with appropriate HTTP status codes
 * and error details.
 */

/**
 * Converts a [GetToolError] to an [ApiError].
 */
fun GetToolError.toApiError(): ApiError = when (this) {
    is GetToolError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())
}

/**
 * Converts an [UpdateToolError] to an [ApiError].
 */
fun UpdateToolError.toApiError(): ApiError = when (this) {
    is UpdateToolError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())

    is UpdateToolError.ValidationError -> error.toApiError()
}

/**
 * Converts a [DeleteToolError] to an [ApiError].
 */
fun DeleteToolError.toApiError(): ApiError = when (this) {
    is DeleteToolError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())
}

/**
 * Converts a [SetToolEnabledError] to an [ApiError].
 */
fun SetToolEnabledError.toApiError(): ApiError = when (this) {
    is SetToolEnabledError.SessionNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to id.toString())

    is SetToolEnabledError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())
}

/**
 * Converts a [SetToolsEnabledError] to an [ApiError].
 */
fun SetToolsEnabledError.toApiError(): ApiError = when (this) {
    is SetToolsEnabledError.InvalidReference ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Session or one or more tools not found", "sessionId" to sessionId.toString(), "toolIds" to toolIds.joinToString(","))
}

/**
 * Converts a [ValidateToolError] to an [ApiError].
 */
fun ValidateToolError.toApiError(): ApiError = when (this) {
    is ValidateToolError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool name: $message", "name" to name)

    is ValidateToolError.InvalidDescription ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool description: $message", "description" to description)

    is ValidateToolError.InvalidInputSchema ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input schema: $message", "inputSchema" to schema.toString())

    is ValidateToolError.InvalidOutputSchema ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid output schema: $message", "outputSchema" to schema.toString())
}

