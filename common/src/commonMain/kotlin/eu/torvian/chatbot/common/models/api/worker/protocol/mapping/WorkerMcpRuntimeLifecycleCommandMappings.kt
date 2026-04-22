package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerControlErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerDiscoverToolsResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerGetRuntimeStatusResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerListRuntimeStatusesResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStartResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerStopResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestConnectionResultData

/**
 * Maps MCP server-start command data to a typed worker command-request payload.
 *
 * @receiver Start command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerStartCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
        targetType = "WorkerMcpServerStartCommandData"
    )

/**
 * Maps MCP server-stop command data to a typed worker command-request payload.
 *
 * @receiver Stop command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerStopCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
        targetType = "WorkerMcpServerStopCommandData"
    )

/**
 * Maps MCP server test-connection command data to a typed worker command-request payload.
 *
 * @receiver Test-connection command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerTestConnectionCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
        targetType = "WorkerMcpServerTestConnectionCommandData"
    )

/**
 * Maps MCP server discover-tools command data to a typed worker command-request payload.
 *
 * @receiver Discover-tools command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerDiscoverToolsCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
        targetType = "WorkerMcpServerDiscoverToolsCommandData"
    )

/**
 * Maps MCP server get-runtime-status command data to a typed worker command-request payload.
 *
 * @receiver Get-runtime-status command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerGetRuntimeStatusCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS,
        targetType = "WorkerMcpServerGetRuntimeStatusCommandData"
    )

/**
 * Maps MCP server list-runtime-statuses command data to a typed worker command-request payload.
 *
 * @receiver List-runtime-statuses command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerMcpServerListRuntimeStatusesCommandData.toWorkerCommandRequestPayload():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
        targetType = "WorkerMcpServerListRuntimeStatusesCommandData"
    )

/**
 * Decodes worker request payload data as MCP server-start command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded start command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerStartCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerStartCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
        targetType = "WorkerMcpServerStartCommandData"
    )

/**
 * Decodes worker request payload data as MCP server-stop command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded stop command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerStopCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerStopCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
        targetType = "WorkerMcpServerStopCommandData"
    )

/**
 * Decodes worker request payload data as MCP server test-connection command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded test-connection command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerTestConnectionCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerTestConnectionCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
        targetType = "WorkerMcpServerTestConnectionCommandData"
    )

/**
 * Decodes worker request payload data as MCP server discover-tools command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded discover-tools command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerDiscoverToolsCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerDiscoverToolsCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
        targetType = "WorkerMcpServerDiscoverToolsCommandData"
    )

/**
 * Decodes worker request payload data as MCP server get-runtime-status command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded get-runtime-status command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerGetRuntimeStatusCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerGetRuntimeStatusCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS,
        targetType = "WorkerMcpServerGetRuntimeStatusCommandData"
    )

/**
 * Decodes worker request payload data as MCP server list-runtime-statuses command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded list-runtime-statuses command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerMcpServerListRuntimeStatusesCommandData():
        Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerListRuntimeStatusesCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
        targetType = "WorkerMcpServerListRuntimeStatusesCommandData"
    )

/**
 * Maps MCP server-start result data to a typed worker command-result payload.
 *
 * @receiver Start command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerStartResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerStartResultData")

/**
 * Maps MCP server-stop result data to a typed worker command-result payload.
 *
 * @receiver Stop command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerStopResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerStopResultData")

/**
 * Maps MCP server test-connection result data to a typed worker command-result payload.
 *
 * @receiver Test-connection result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerTestConnectionResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerTestConnectionResultData")

/**
 * Maps MCP server discover-tools result data to a typed worker command-result payload.
 *
 * @receiver Discover-tools result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerDiscoverToolsResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerDiscoverToolsResultData")

/**
 * Maps MCP server get-runtime-status result data to a typed worker command-result payload.
 *
 * @receiver Get-runtime-status result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerGetRuntimeStatusResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerGetRuntimeStatusResultData")

/**
 * Maps MCP server list-runtime-statuses result data to a typed worker command-result payload.
 *
 * @receiver List-runtime-statuses result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerMcpServerListRuntimeStatusesResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerMcpServerListRuntimeStatusesResultData")

/**
 * Decodes worker result payload data as MCP server-start result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded start result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerStartResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerStartResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
        targetType = "WorkerMcpServerStartResultData"
    )

/**
 * Decodes worker result payload data as MCP server-stop result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded stop result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerStopResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerStopResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
        targetType = "WorkerMcpServerStopResultData"
    )

/**
 * Decodes worker result payload data as MCP server test-connection result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded test-connection result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerTestConnectionResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerTestConnectionResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
        targetType = "WorkerMcpServerTestConnectionResultData"
    )

/**
 * Decodes worker result payload data as MCP server discover-tools result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded discover-tools result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerDiscoverToolsResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerDiscoverToolsResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
        targetType = "WorkerMcpServerDiscoverToolsResultData"
    )

/**
 * Decodes worker result payload data as MCP server get-runtime-status result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded get-runtime-status result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerGetRuntimeStatusResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerGetRuntimeStatusResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS,
        targetType = "WorkerMcpServerGetRuntimeStatusResultData"
    )

/**
 * Decodes worker result payload data as MCP server list-runtime-statuses result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded list-runtime-statuses result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerListRuntimeStatusesResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerListRuntimeStatusesResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
        targetType = "WorkerMcpServerListRuntimeStatusesResultData"
    )

/**
 * Decodes worker result payload data as MCP server-start error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded start error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerStartErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_START,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server-stop error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded stop error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerStopErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_STOP,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server test-connection error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded test-connection error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerTestConnectionErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server discover-tools error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded discover-tools error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerDiscoverToolsErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server get-runtime-status error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded get-runtime-status error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerGetRuntimeStatusErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS,
        targetType = "WorkerMcpServerControlErrorResultData"
    )

/**
 * Decodes worker result payload data as MCP server list-runtime-statuses error result data.
 *
 * @receiver Worker command-result payload.
 * @param commandType Command type associated with the completed lifecycle.
 * @return Either decoded list-runtime-statuses error result data or a logical mapping error.
 */
fun WorkerCommandResultPayload.toWorkerMcpServerListRuntimeStatusesErrorResultData(
    commandType: String
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerMcpServerControlErrorResultData> =
    decodeCommandResultData(
        commandType = commandType,
        expectedCommandType = WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES,
        targetType = "WorkerMcpServerControlErrorResultData"
    )


