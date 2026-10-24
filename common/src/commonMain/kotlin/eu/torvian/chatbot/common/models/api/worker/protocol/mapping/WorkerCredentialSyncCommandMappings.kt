package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandResultPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialCommandErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileUpdateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretCreateResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretDeleteResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretUpdateResultData

/**
 * Maps credential profile-create command data to a typed worker command-request payload.
 *
 * @receiver Create command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerCredentialProfileCreateCommandData.toWorkerCommandRequestPayload():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_CREATE,
        targetType = "WorkerCredentialProfileCreateCommandData"
    )

/**
 * Maps credential profile-update command data to a typed worker command-request payload.
 *
 * @receiver Update command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerCredentialProfileUpdateCommandData.toWorkerCommandRequestPayload():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_UPDATE,
        targetType = "WorkerCredentialProfileUpdateCommandData"
    )

/**
 * Maps credential profile-delete command data to a typed worker command-request payload.
 *
 * @receiver Delete command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerCredentialProfileDeleteCommandData.toWorkerCommandRequestPayload():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_DELETE,
        targetType = "WorkerCredentialProfileDeleteCommandData"
    )

/**
 * Maps credential secret-create command data to a typed worker command-request payload.
 *
 * @receiver Create command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerCredentialSecretCreateCommandData.toWorkerCommandRequestPayload():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_CREATE,
        targetType = "WorkerCredentialSecretCreateCommandData"
    )

/**
 * Maps credential secret-update command data to a typed worker command-request payload.
 *
 * @receiver Update command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerCredentialSecretUpdateCommandData.toWorkerCommandRequestPayload():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_UPDATE,
        targetType = "WorkerCredentialSecretUpdateCommandData"
    )

/**
 * Maps credential secret-delete command data to a typed worker command-request payload.
 *
 * @receiver Delete command request DTO.
 * @return Either a worker command-request payload or a logical mapping error.
 */
fun WorkerCredentialSecretDeleteCommandData.toWorkerCommandRequestPayload():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload> =
    toCommandRequestPayload(
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_DELETE,
        targetType = "WorkerCredentialSecretDeleteCommandData"
    )

/**
 * Decodes worker request payload data as credential profile-create command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded create command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerCredentialProfileCreateCommandData():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCredentialProfileCreateCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_CREATE,
        targetType = "WorkerCredentialProfileCreateCommandData"
    )

/**
 * Decodes worker request payload data as credential profile-update command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded update command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerCredentialProfileUpdateCommandData():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCredentialProfileUpdateCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_UPDATE,
        targetType = "WorkerCredentialProfileUpdateCommandData"
    )

/**
 * Decodes worker request payload data as credential profile-delete command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded delete command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerCredentialProfileDeleteCommandData():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCredentialProfileDeleteCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_DELETE,
        targetType = "WorkerCredentialProfileDeleteCommandData"
    )

/**
 * Decodes worker request payload data as credential secret-create command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded create command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerCredentialSecretCreateCommandData():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCredentialSecretCreateCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_CREATE,
        targetType = "WorkerCredentialSecretCreateCommandData"
    )

/**
 * Decodes worker request payload data as credential secret-update command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded update command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerCredentialSecretUpdateCommandData():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCredentialSecretUpdateCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_UPDATE,
        targetType = "WorkerCredentialSecretUpdateCommandData"
    )

/**
 * Decodes worker request payload data as credential secret-delete command data.
 *
 * @receiver Worker command-request payload.
 * @return Either decoded delete command data or a logical mapping error.
 */
fun WorkerCommandRequestPayload.toWorkerCredentialSecretDeleteCommandData():
    Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCredentialSecretDeleteCommandData> =
    decodeCommandRequestData(
        expectedCommandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_DELETE,
        targetType = "WorkerCredentialSecretDeleteCommandData"
    )

/**
 * Maps credential profile-create result data to a typed worker command-result payload.
 *
 * @receiver Create command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialProfileCreateResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialProfileCreateResultData")

/**
 * Maps credential profile-update result data to a typed worker command-result payload.
 *
 * @receiver Update command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialProfileUpdateResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialProfileUpdateResultData")

/**
 * Maps credential profile-delete result data to a typed worker command-result payload.
 *
 * @receiver Delete command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialProfileDeleteResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialProfileDeleteResultData")

/**
 * Maps credential secret-create result data to a typed worker command-result payload.
 *
 * @receiver Create command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialSecretCreateResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialSecretCreateResultData")

/**
 * Maps credential secret-update result data to a typed worker command-result payload.
 *
 * @receiver Update command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialSecretUpdateResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialSecretUpdateResultData")

/**
 * Maps credential secret-delete result data to a typed worker command-result payload.
 *
 * @receiver Delete command result DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialSecretDeleteResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.SUCCESS
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialSecretDeleteResultData")

/**
 * Maps a credential command error to a typed worker command-result payload.
 *
 * @receiver Credential command error DTO.
 * @param status Final command-result status to encode.
 * @return Either a worker command-result payload or a logical mapping error.
 */
fun WorkerCredentialCommandErrorResultData.toWorkerCommandResultPayload(
    status: String = WorkerCommandResultStatuses.ERROR
): Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandResultPayload> =
    toCommandResultPayload(status = status, targetType = "WorkerCredentialCommandErrorResultData")
