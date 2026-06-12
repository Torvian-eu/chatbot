package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload

/**
 * Maps a signed Local MCP tool execution request to a typed worker command-request payload.
 *
 * @receiver Signed authorization request with detached signature metadata.
 * @return Either a worker payload or a logical mapping error.
 */
fun SignedLocalMCPToolExecutionRequest.toWorkerCommandRequestPayload():
        Either<WorkerMcpToolCallProtocolMappingError, WorkerCommandRequestPayload> = either {

    val data = encodeProtocolPayload(
        value = this@toWorkerCommandRequestPayload,
        targetType = "SignedLocalMCPToolExecutionRequest"
    ).mapLeft { it.toToolCallMappingError() }
        .bind()

    WorkerCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_TOOL_CALL,
        data = data
    )
}

/**
 * Maps a typed worker command-request payload back to a signed Local MCP tool execution request.
 *
 * @receiver Command-request payload decoded from the worker protocol envelope.
 * @return Either the signed execution request DTO or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toSignedLocalMcpToolExecutionRequest(): Either<WorkerMcpToolCallProtocolMappingError, SignedLocalMCPToolExecutionRequest> =
    either {
        if (commandType != WorkerProtocolCommandTypes.MCP_TOOL_CALL) {
            raise(
                WorkerMcpToolCallProtocolMappingError.InvalidCommandType(
                    expected = WorkerProtocolCommandTypes.MCP_TOOL_CALL,
                    actual = commandType
                )
            )
        }

        decodeProtocolPayload<SignedLocalMCPToolExecutionRequest>(
            payload = data,
            targetType = "SignedLocalMCPToolExecutionRequest"
        ).mapLeft { it.toToolCallMappingError() }
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
    ).mapLeft { it.toToolCallMappingError() }
        .bind()

    WorkerCommandResultPayload(
        status = if (isError) WorkerCommandResultStatuses.ERROR else WorkerCommandResultStatuses.SUCCESS,
        data = data
    )
}

/**
 * Decodes a worker command result payload back into a local MCP tool-call result.
 *
 * @receiver Command-result payload returned by the worker command dispatcher.
 * @return Either the local MCP result DTO or a logical mapping error.
 */
fun WorkerCommandResultPayload.toLocalMCPToolCallResult():
        Either<WorkerMcpToolCallProtocolMappingError, LocalMCPToolCallResult> = either {

    decodeProtocolPayload<LocalMCPToolCallResult>(
        payload = data,
        targetType = "LocalMCPToolCallResult"
    ).mapLeft { it.toToolCallMappingError() }
        .bind()
}

/**
 * Converts codec failures into the tool-call-specific mapping error hierarchy.
 *
 * @receiver Generic protocol codec error.
 * @return Tool-call mapping error carrying the same diagnostic information.
 */
private fun WorkerProtocolCodecError.toToolCallMappingError(): WorkerMcpToolCallProtocolMappingError = when (this) {
    is WorkerProtocolCodecError.SerializationFailed -> {
        WorkerMcpToolCallProtocolMappingError.SerializationFailed(
            operation = operation,
            targetType = targetType,
            details = details
        )
    }
}


