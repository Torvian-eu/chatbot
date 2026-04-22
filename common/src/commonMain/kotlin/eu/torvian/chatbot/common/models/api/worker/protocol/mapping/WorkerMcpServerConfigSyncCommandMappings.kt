package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerUpdateResultData

/**
 * Maps MCP server-create command data to a typed worker command-request payload.
 *
 * @receiver Create command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerCreateCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_CREATE,
        targetType = "WorkerMcpServerCreateCommandData"
    )

/**
 * Maps MCP server-update command data to a typed worker command-request payload.
 *
 * @receiver Update command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerUpdateCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_UPDATE,
        targetType = "WorkerMcpServerUpdateCommandData"
    )

/**
 * Maps MCP server-delete command data to a typed worker command-request payload.
 *
 * @receiver Delete command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerDeleteCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_DELETE,
        targetType = "WorkerMcpServerDeleteCommandData"
    )

/**
 * Decodes worker request payload data as MCP server-create command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded create command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerCreateCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerCreateCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_CREATE,
        targetType = "WorkerMcpServerCreateCommandData"
    )

/**
 * Decodes worker request payload data as MCP server-update command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded update command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerUpdateCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerUpdateCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_UPDATE,
        targetType = "WorkerMcpServerUpdateCommandData"
    )

/**
 * Decodes worker request payload data as MCP server-delete command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded delete command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerDeleteCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerDeleteCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_DELETE,
        targetType = "WorkerMcpServerDeleteCommandData"
    )

/**
 * Maps MCP server-create result data to a typed worker command-result payload.
 *
 * @receiver Create command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerCreateResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerCreateResultData")

/**
 * Maps MCP server-update result data to a typed worker command-result payload.
 *
 * @receiver Update command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerUpdateResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerUpdateResultData")

/**
 * Maps MCP server-delete result data to a typed worker command-result payload.
 *
 * @receiver Delete command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerDeleteResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerDeleteResultData")

/**
 * Decodes worker result payload data as MCP server-create result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded create result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerCreateResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerCreateResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_CREATE,
        targetType = "WorkerMcpServerCreateResultData"
    )

/**
 * Decodes worker result payload data as MCP server-update result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded update result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerUpdateResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerUpdateResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_UPDATE,
        targetType = "WorkerMcpServerUpdateResultData"
    )

/**
 * Decodes worker result payload data as MCP server-delete result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded delete result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerDeleteResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerDeleteResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_DELETE,
        targetType = "WorkerMcpServerDeleteResultData"
    )

/**
 * Decodes worker result payload data as MCP server-create error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded create error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerCreateErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_CREATE,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server-update error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded update error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerUpdateErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_UPDATE,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server-delete error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded delete error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerDeleteErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_DELETE,
        targetType = "WorkerMcpServerControlErrorResultData"
    )



