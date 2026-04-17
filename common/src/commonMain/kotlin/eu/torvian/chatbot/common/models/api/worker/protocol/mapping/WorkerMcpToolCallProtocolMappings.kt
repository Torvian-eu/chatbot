package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload

/**
 * Maps a local MCP tool-call request to a typed worker command-request payload.
 *
 * @receiver Local MCP request produced by the application-side tool-call flow.
 * @return Either a worker payload or a logical mapping error.
 */
fun LocalMCPToolCallRequest.toWorkerCommandRequestPayload():
        Either<WorkerMcpToolCallProtocolMappingError, WorkerCommandRequestPayload> = either {

    val data = encodeProtocolPayload(
        value = this@toWorkerCommandRequestPayload,
        targetType = "LocalMCPToolCallRequest"
    ).mapLeft { it.toMappingError() }
        .bind()

    WorkerCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL,
        data = data
    )
}

/**
 * Maps a typed worker command-request payload back to a local MCP tool-call request.
 *
 * @receiver Command-request payload decoded from the worker protocol envelope.
 * @return Either the local MCP request DTO or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toLocalMcpToolCallRequest(): Either<WorkerMcpToolCallProtocolMappingError, LocalMCPToolCallRequest> =
    either {
        if (commandType != WorkerProtocolCommandTypes.MCP_TOOL_CALL) {
            raise(
                WorkerMcpToolCallProtocolMappingError.InvalidCommandType(
                    expected = WorkerProtocolCommandTypes.MCP_TOOL_CALL,
                    actual = commandType
                )
            )
        }

        decodeProtocolPayload<LocalMCPToolCallRequest>(
            payload = data,
            targetType = "LocalMCPToolCallRequest"
        ).mapLeft { it.toMappingError() }
            .bind()
    }

/**
 * Maps a local MCP tool-call result to a typed worker command-result payload.
 *
 * @receiver Local MCP result produced by the worker-side execution layer.
 * @return Either a worker payload or a logical mapping error.
 */
fun LocalMCPToolCallResult.toWorkerCommandResultPayload():
        Either<WorkerMcpToolCallProtocolMappingError, WorkerCommandResultPayload> = either {

    val data = encodeProtocolPayload(
        value = this@toWorkerCommandResultPayload,
        targetType = "LocalMCPToolCallResult"
    ).mapLeft { it.toMappingError() }
        .bind()

    WorkerCommandResultPayload(
        status = if (isError) WorkerCommandResultStatuses.ERROR else WorkerCommandResultStatuses.SUCCESS,
        data = data
    )
}

/**
 * Converts the generic codec error into the more specific tool-call mapping error.
 *
 * @receiver Generic protocol codec error.
 * @return Tool-call mapping error carrying the same diagnostic information.
 */
private fun WorkerProtocolCodecError.toMappingError(): WorkerMcpToolCallProtocolMappingError = when (this) {
    is WorkerProtocolCodecError.SerializationFailed -> {
        WorkerMcpToolCallProtocolMappingError.SerializationFailed(
            operation = operation,
            targetType = targetType,
            details = details
        )
    }
}
