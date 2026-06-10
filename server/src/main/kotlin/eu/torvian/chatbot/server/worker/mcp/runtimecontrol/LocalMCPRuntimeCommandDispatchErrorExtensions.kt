package eu.torvian.chatbot.server.worker.mcp.runtimecontrol

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError

/**
 * Converts Local MCP runtime command dispatch errors into API errors.
 *
 * @receiver Dispatch error to convert.
 * @return Structured API error suitable for route responses.
 */
fun LocalMCPRuntimeCommandDispatchError.toApiError(): ApiError = when (this) {
    is LocalMCPRuntimeCommandDispatchError.DispatchFailed -> error.toApiError()
    is LocalMCPRuntimeCommandDispatchError.InvalidPayload -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "Failed to map Local MCP worker sync payload",
        "commandType" to commandType,
        "details" to details
    )

    is LocalMCPRuntimeCommandDispatchError.CommandFailed -> apiError(
        apiCode = CommonApiErrorCodes.FAILED_PRECONDITION,
        message = "Worker rejected Local MCP configuration sync",
        "commandType" to commandType,
        "code" to code,
        "details" to (details ?: "")
    )

    is LocalMCPRuntimeCommandDispatchError.UnexpectedResultStatus -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "Worker returned an unexpected Local MCP sync result status",
        "commandType" to commandType,
        "status" to status
    )
}

/**
 * Converts worker command dispatch failures into API errors.
 *
 * @receiver Dispatch-layer worker error to convert.
 * @return Structured API error suitable for route responses.
 */
private fun WorkerCommandDispatchError.toApiError(): ApiError = when (this) {
    is WorkerCommandDispatchError.Rejected -> apiError(
        apiCode = CommonApiErrorCodes.FAILED_PRECONDITION,
        message = "Worker rejected Local MCP sync command",
        "workerId" to workerId.toString(),
        "commandType" to commandType,
        "reasonCode" to rejection.reasonCode,
        "reason" to rejection.message
    )

    is WorkerCommandDispatchError.TimedOut -> apiError(
        apiCode = CommonApiErrorCodes.DEADLINE_EXCEEDED,
        message = "Timed out while synchronizing Local MCP configuration with worker",
        "workerId" to workerId.toString(),
        "commandType" to commandType,
        "timeout" to timeout.toString()
    )

    is WorkerCommandDispatchError.WorkerNotConnected -> apiError(
        apiCode = CommonApiErrorCodes.UNAVAILABLE,
        message = "Assigned worker is not connected",
        "workerId" to workerId.toString()
    )

    is WorkerCommandDispatchError.SessionDisconnected -> apiError(
        apiCode = CommonApiErrorCodes.UNAVAILABLE,
        message = "Worker disconnected during Local MCP synchronization",
        "workerId" to workerId.toString(),
        "commandType" to commandType,
        "reason" to (reason ?: "")
    )

    is WorkerCommandDispatchError.SendFailed -> apiError(
        apiCode = CommonApiErrorCodes.UNAVAILABLE,
        message = "Failed to send Local MCP sync command to worker",
        "workerId" to workerId.toString(),
        "commandType" to commandType,
        "reason" to reason
    )

    is WorkerCommandDispatchError.MalformedLifecyclePayload -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "Worker returned malformed Local MCP sync lifecycle data",
        "workerId" to workerId.toString(),
        "commandType" to commandType,
        "messageType" to messageType,
        "reason" to reason
    )

    is WorkerCommandDispatchError.DuplicateInteractionId -> apiError(
        apiCode = CommonApiErrorCodes.INTERNAL,
        message = "Duplicate worker interaction identifier was generated for Local MCP sync",
        "interactionId" to interactionId
    )
}