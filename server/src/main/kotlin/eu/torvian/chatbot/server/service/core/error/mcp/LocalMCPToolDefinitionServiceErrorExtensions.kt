package eu.torvian.chatbot.server.service.core.error.mcp

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.core.error.tool.toApiError

/**
 * Extension functions to convert Local MCP Tool Definition service errors to API errors.
 */

fun CreateMCPToolsError.toApiError(): ApiError = when (this) {
    is CreateMCPToolsError.ToolValidationError -> validationError.toApiError()
    is CreateMCPToolsError.DuplicateName -> apiError(
        apiCode = CommonApiErrorCodes.CONFLICT,
        message = "A tool with the name '$name' already exists for this server",
        "name" to name
    )

    is CreateMCPToolsError.OtherError -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = message
    )
}

fun GetMCPToolsByServerIdError.toApiError(): ApiError = when (this) {
    is GetMCPToolsByServerIdError.ServerNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "MCP Server not found",
        "serverId" to serverId.toString()
    )
}

fun GetMCPToolByIdError.toApiError(): ApiError = when (this) {
    is GetMCPToolByIdError.ToolNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "MCP Tool not found",
        "toolId" to toolId.toString()
    )
}

fun UpdateMCPToolError.toApiError(): ApiError = when (this) {
    is UpdateMCPToolError.ToolNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "MCP Tool not found",
        "toolId" to toolId.toString()
    )

    is UpdateMCPToolError.DuplicateName -> apiError(
        apiCode = CommonApiErrorCodes.CONFLICT,
        message = "A tool with the name '$name' already exists for this server",
        "name" to name
    )

    is UpdateMCPToolError.ValidationError -> error.toApiError()
}

fun DeleteMCPToolsForServerError.toApiError(): ApiError = when (this) {
    is DeleteMCPToolsForServerError.ServerNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "MCP Server not found",
        "serverId" to serverId.toString()
    )
}

fun RefreshMCPToolsError.toApiError(): ApiError = when (this) {
    is RefreshMCPToolsError.ServerNotFound -> apiError(
        apiCode = CommonApiErrorCodes.NOT_FOUND,
        message = "MCP Server not found",
        "serverId" to serverId.toString()
    )

    is RefreshMCPToolsError.DuplicateName -> apiError(
        apiCode = CommonApiErrorCodes.CONFLICT,
        message = "A tool with the name '$name' already exists for this server",
        "name" to name
    )

    is RefreshMCPToolsError.ToolValidationError -> validationError.toApiError()
    is RefreshMCPToolsError.OtherError -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = message
    )
}

