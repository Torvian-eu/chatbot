package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.SignedBuiltInToolExecutionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload

/**
 * Maps a signed built-in tool execution request to a typed worker command-request payload.
 *
 * @receiver Signed authorization request with detached signature metadata.
 * @return Either a worker payload or a logical mapping error.
 */
fun SignedBuiltInToolExecutionRequest.toWorkerCommandRequestPayload():
        Either<BuiltInToolProtocolMappingError, WorkerCommandRequestPayload> = either {

    val data = encodeProtocolPayload(
        value = this@toWorkerCommandRequestPayload,
        targetType = "SignedBuiltInToolExecutionRequest"
    ).mapLeft { it.toToolCallMappingError() }
        .bind()

    WorkerCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.TOOL_CALL,
        data = data
    )
}

/**
 * Maps a typed worker command-request payload back to a signed built-in tool execution request.
 *
 * @receiver Command-request payload decoded from the worker protocol envelope.
 * @return Either the signed execution request DTO or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toSignedBuiltInToolExecutionRequest(): Either<BuiltInToolProtocolMappingError, SignedBuiltInToolExecutionRequest> =
    either {
        if (commandType != WorkerProtocolCommandTypes.TOOL_CALL) {
            raise(
                BuiltInToolProtocolMappingError.InvalidCommandType(
                    expected = WorkerProtocolCommandTypes.TOOL_CALL,
                    actual = commandType
                )
            )
        }

        decodeProtocolPayload<SignedBuiltInToolExecutionRequest>(
            payload = data,
            targetType = "SignedBuiltInToolExecutionRequest"
        ).mapLeft { it.toToolCallMappingError() }
            .bind()
    }

/**
 * Maps a built-in tool-call result to a typed worker command-result payload.
 *
 * @receiver Built-in result produced by the worker-side execution layer.
 * @return Either a worker payload or a logical mapping error.
 */
fun BuiltInToolExecutionResult.toWorkerCommandResultPayload():
        Either<BuiltInToolProtocolMappingError, WorkerCommandResultPayload> = either {

    val data = encodeProtocolPayload(
        value = this@toWorkerCommandResultPayload,
        targetType = "BuiltInToolExecutionResult"
    ).mapLeft { it.toToolCallMappingError() }
        .bind()

    WorkerCommandResultPayload(
        status = if (isError) WorkerCommandResultStatuses.ERROR else WorkerCommandResultStatuses.SUCCESS,
        data = data
    )
}

/**
 * Decodes a worker command result payload back into a built-in tool-call result.
 *
 * @receiver Command-result payload returned by the worker command dispatcher.
 * @return Either the built-in result DTO or a logical mapping error.
 */
fun WorkerCommandResultPayload.toBuiltInToolExecutionResult():
        Either<BuiltInToolProtocolMappingError, BuiltInToolExecutionResult> = either {

    decodeProtocolPayload<BuiltInToolExecutionResult>(
        payload = data,
        targetType = "BuiltInToolExecutionResult"
    ).mapLeft { it.toToolCallMappingError() }
        .bind()
}

/**
 * Converts codec failures into the built-in tool-call-specific mapping error hierarchy.
 *
 * @receiver Generic protocol codec error.
 * @return Built-in tool-call mapping error carrying the same diagnostic information.
 */
private fun WorkerProtocolCodecError.toToolCallMappingError(): BuiltInToolProtocolMappingError = when (this) {
    is WorkerProtocolCodecError.SerializationFailed -> {
        BuiltInToolProtocolMappingError.SerializationFailed(
            operation = operation,
            targetType = targetType,
            details = details
        )
    }
}
