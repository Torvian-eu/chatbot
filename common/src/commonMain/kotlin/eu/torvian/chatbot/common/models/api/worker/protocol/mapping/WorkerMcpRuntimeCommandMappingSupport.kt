package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolCodecError
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.encodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData

/**
 * Encodes typed command-request DTOs into worker protocol payloads.
 *
 * @receiver Command request DTO that will be serialized into the payload body.
 * @param commandType Command type written to the outgoing worker payload.
 * @param targetType Human-readable type label used in codec diagnostics.
 * @return Either the encoded worker request payload or a logical mapping error.
 */
internal inline fun <reified T> T.toCommandRequestPayload(
    commandType: String,
    targetType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> = either {
    val data = encodeProtocolPayload(value = this@toCommandRequestPayload, targetType = targetType)
        .mapLeft { it.toMappingError() }
        .bind()

    WorkerCommandRequestPayload(
        commandType = commandType,
        data = data
    )
}

/**
 * Decodes a worker request payload into a typed command-request DTO.
 *
 * @receiver Worker request payload carrying encoded command data.
 * @param expectedCommandType Command type required for this decoding path.
 * @param targetType Human-readable type label used in codec diagnostics.
 * @return Either the decoded request DTO or a logical mapping error.
 */
internal inline fun <reified T> WorkerCommandRequestPayload.decodeCommandRequestData(
    expectedCommandType: String,
    targetType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, T> = either {
    validateCommandType(actual = commandType, expected = expectedCommandType).bind()

    decodeProtocolPayload<T>(payload = data, targetType = targetType)
        .mapLeft { it.toMappingError() }
        .bind()
}

/**
 * Encodes typed command-result DTOs into worker protocol result payloads.
 *
 * @receiver Command result DTO that will be serialized into the payload body.
 * @param status Final command-result status written to the outgoing worker payload.
 * @param targetType Human-readable type label used in codec diagnostics.
 * @return Either the encoded worker result payload or a logical mapping error.
 */
internal inline fun <reified T> T.toCommandResultPayload(
    status: String,
    targetType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> = either {
    val data = encodeProtocolPayload(value = this@toCommandResultPayload, targetType = targetType)
        .mapLeft { it.toMappingError() }
        .bind()

    WorkerCommandResultPayload(
        status = status,
        data = data
    )
}

/**
 * Decodes a worker result payload into a typed command-result DTO.
 *
 * @receiver Worker result payload carrying encoded command data.
 * @param commandType Command type associated with the completed command lifecycle.
 * @param expectedCommandType Command type required for this decoding path.
 * @param targetType Human-readable type label used in codec diagnostics.
 * @return Either the decoded result DTO or a logical mapping error.
 */
internal inline fun <reified T> WorkerCommandResultPayload.decodeCommandResultData(
    commandType: String,
    expectedCommandType: String,
    targetType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, T> = either {
    validateCommandType(actual = commandType, expected = expectedCommandType).bind()

    decodeProtocolPayload<T>(payload = data, targetType = targetType)
        .mapLeft { it.toMappingError() }
        .bind()
}

/**
 * Validates that an incoming command type matches the command type required by a mapping.
 *
 * @param actual Command type received in the payload.
 * @param expected Command type required for the mapping path.
 * @return Either success or a logical mapping error describing the mismatch.
 */
internal fun validateCommandType(
    actual: String,
    expected: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, Unit> = either {
    if (actual != expected) {
        raise(
            WorkerMcpRuntimeCommandProtocolMappingError.InvalidCommandType(
                expected = expected,
                actual = actual
            )
        )
    }
}

/**
 * Converts codec failures into the runtime command mapping error hierarchy.
 *
 * @receiver Generic worker protocol codec error.
 * @return Worker runtime mapping error carrying the original diagnostic context.
 */
internal fun WorkerProtocolCodecError.toMappingError(): WorkerMcpRuntimeCommandProtocolMappingError = when (this) {
    is WorkerProtocolCodecError.SerializationFailed -> {
        WorkerMcpRuntimeCommandProtocolMappingError.SerializationFailed(
            operation = operation,
            targetType = targetType,
            details = details
        )
    }
}

/**
 * Maps MCP server-control error result data to a typed worker command-result payload.
 *
 * This mapping is shared by the lifecycle and config-sync command families because they both
 * encode the same control-error payload type.
 *
 * @receiver MCP server-control error result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerControlErrorResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.ERROR
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerControlErrorResultData")


