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
 * Converts a [CreateToolError] to an [ApiError].
 */
fun CreateToolError.toApiError(): ApiError = when (this) {
    is CreateToolError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool name: $message", "name" to message)

    is CreateToolError.InvalidDescription ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool description: $message", "description" to message)

    is CreateToolError.InvalidConfig ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool configuration: $message", "config" to message)

    is CreateToolError.InvalidInputSchema ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input schema: $message", "inputSchema" to message)

    is CreateToolError.DuplicateName ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "A tool with this name already exists", "name" to name)

    is CreateToolError.PersistenceError ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to create tool: $message")
}

/**
 * Converts an [UpdateToolError] to an [ApiError].
 */
fun UpdateToolError.toApiError(): ApiError = when (this) {
    is UpdateToolError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())

    is UpdateToolError.InvalidName ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool name: $message", "name" to message)

    is UpdateToolError.InvalidDescription ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool description: $message", "description" to message)

    is UpdateToolError.InvalidConfig ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid tool configuration: $message", "config" to message)

    is UpdateToolError.InvalidInputSchema ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input schema: $message", "inputSchema" to message)

    is UpdateToolError.DuplicateName ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "A tool with this name already exists", "name" to name)

    is UpdateToolError.PersistenceError ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to update tool: $message")
}

/**
 * Converts a [DeleteToolError] to an [ApiError].
 */
fun DeleteToolError.toApiError(): ApiError = when (this) {
    is DeleteToolError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())

    is DeleteToolError.ToolInUse ->
        apiError(CommonApiErrorCodes.FAILED_PRECONDITION, "Cannot delete tool: $message")
}

/**
 * Converts a [SetToolEnabledError] to an [ApiError].
 */
fun SetToolEnabledError.toApiError(): ApiError = when (this) {
    is SetToolEnabledError.SessionNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to id.toString())

    is SetToolEnabledError.ToolNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found", "toolId" to id.toString())

    is SetToolEnabledError.PersistenceError ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to update tool configuration: $message")
}

