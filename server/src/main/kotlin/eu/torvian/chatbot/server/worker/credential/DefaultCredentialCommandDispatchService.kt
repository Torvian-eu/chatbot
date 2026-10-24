package eu.torvian.chatbot.server.worker.credential

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchFailure
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.WorkerMcpRuntimeCommandProtocolMappingError
import eu.torvian.chatbot.common.models.api.worker.protocol.mapping.toWorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretUpdateCommandData
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [CredentialCommandDispatchService], built on the generic worker command dispatcher.
 *
 * Delivery is best-effort by policy: a failure here never fails the write that preceded it, because a
 * compensating rollback would discard a secret plaintext the server can never re-derive. The
 * classification exists so the operator can be told that the worker has not learned of the change yet.
 *
 * @property workerCommandDispatchService Generic dispatcher used for command lifecycle orchestration.
 * @property logger Diagnostic sink; records identifiers, command types and classifications only.
 */
class DefaultCredentialCommandDispatchService(
    private val workerCommandDispatchService: WorkerCommandDispatchService,
    private val logger: Logger = LogManager.getLogger(DefaultCredentialCommandDispatchService::class.java),
) : CredentialCommandDispatchService {

    override suspend fun createProfile(
        workerId: Long,
        signedProfile: SignedCredentialProfileDto,
    ): Either<WorkerDispatchFailure, Unit> = dispatch(
        workerId = workerId,
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_CREATE,
        targetId = signedProfile.profile.id,
        requestPayload = WorkerCredentialProfileCreateCommandData(signedProfile).toWorkerCommandRequestPayload()
    )

    override suspend fun updateProfile(
        workerId: Long,
        signedProfile: SignedCredentialProfileDto,
    ): Either<WorkerDispatchFailure, Unit> = dispatch(
        workerId = workerId,
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_UPDATE,
        targetId = signedProfile.profile.id,
        requestPayload = WorkerCredentialProfileUpdateCommandData(signedProfile).toWorkerCommandRequestPayload()
    )

    override suspend fun deleteProfile(
        workerId: Long,
        profileId: Long,
    ): Either<WorkerDispatchFailure, Unit> = dispatch(
        workerId = workerId,
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_DELETE,
        targetId = profileId,
        requestPayload = WorkerCredentialProfileDeleteCommandData(profileId).toWorkerCommandRequestPayload()
    )

    override suspend fun createSecret(
        workerId: Long,
        secret: WorkerCredentialSecretDto,
    ): Either<WorkerDispatchFailure, Unit> = dispatch(
        workerId = workerId,
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_CREATE,
        targetId = secret.id,
        requestPayload = WorkerCredentialSecretCreateCommandData(secret).toWorkerCommandRequestPayload()
    )

    override suspend fun updateSecret(
        workerId: Long,
        secret: WorkerCredentialSecretDto,
    ): Either<WorkerDispatchFailure, Unit> = dispatch(
        workerId = workerId,
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_UPDATE,
        targetId = secret.id,
        requestPayload = WorkerCredentialSecretUpdateCommandData(secret).toWorkerCommandRequestPayload()
    )

    override suspend fun deleteSecret(
        workerId: Long,
        secretId: Long,
    ): Either<WorkerDispatchFailure, Unit> = dispatch(
        workerId = workerId,
        commandType = WorkerProtocolCommandTypes.CREDENTIAL_SECRET_DELETE,
        targetId = secretId,
        requestPayload = WorkerCredentialSecretDeleteCommandData(secretId).toWorkerCommandRequestPayload()
    )

    /**
     * Encodes, dispatches and classifies one credential command.
     *
     * @param workerId Worker the command targets.
     * @param commandType Protocol command type being dispatched.
     * @param targetId Profile or secret identifier the command concerns, used for logging.
     * @param requestPayload Encoded request payload, or the encoding failure it produced.
     * @return Either the classified delivery failure or success.
     */
    private suspend fun dispatch(
        workerId: Long,
        commandType: String,
        targetId: Long,
        requestPayload: Either<WorkerMcpRuntimeCommandProtocolMappingError, WorkerCommandRequestPayload>,
    ): Either<WorkerDispatchFailure, Unit> {
        val payload = requestPayload.fold(
            ifLeft = { error ->
                logger.warn(
                    "Credential command {} for worker {} entity {} could not be encoded: {}",
                    commandType,
                    workerId,
                    targetId,
                    error.javaClass.simpleName
                )
                return WorkerDispatchFailure.PROTOCOL_ERROR.left()
            },
            ifRight = { it }
        )

        return workerCommandDispatchService.dispatch(workerId = workerId, commandRequestPayload = payload)
            .fold(
                ifLeft = { error ->
                    val failure = error.toDispatchFailure()
                    logger.warn(
                        "Credential command {} for worker {} entity {} was not delivered: {} ({})",
                        commandType,
                        workerId,
                        targetId,
                        failure,
                        error.javaClass.simpleName
                    )
                    failure.left()
                },
                ifRight = { success ->
                    if (success.result.status == WorkerCommandResultStatuses.SUCCESS) {
                        Unit.right()
                    } else {
                        // An error status carries worker-supplied text; only the status is logged so that
                        // nothing worker-controlled can reach the operator-facing DTO.
                        logger.warn(
                            "Credential command {} for worker {} entity {} was rejected by the worker with status {}",
                            commandType,
                            workerId,
                            targetId,
                            success.result.status
                        )
                        WorkerDispatchFailure.WORKER_REJECTED.left()
                    }
                }
            )
    }

    /**
     * @receiver Transport-level dispatch failure.
     * @return The operator-facing classification of that failure.
     */
    private fun WorkerCommandDispatchError.toDispatchFailure(): WorkerDispatchFailure = when (this) {
        is WorkerCommandDispatchError.WorkerNotConnected -> WorkerDispatchFailure.WORKER_OFFLINE
        is WorkerCommandDispatchError.TimedOut -> WorkerDispatchFailure.WORKER_TIMEOUT
        is WorkerCommandDispatchError.SessionDisconnected -> WorkerDispatchFailure.WORKER_DISCONNECTED
        is WorkerCommandDispatchError.Rejected -> WorkerDispatchFailure.WORKER_REJECTED
        is WorkerCommandDispatchError.SendFailed -> WorkerDispatchFailure.TRANSPORT_FAILED
        is WorkerCommandDispatchError.MalformedLifecyclePayload -> WorkerDispatchFailure.PROTOCOL_ERROR
        is WorkerCommandDispatchError.DuplicateInteractionId -> WorkerDispatchFailure.PROTOCOL_ERROR
    }
}
