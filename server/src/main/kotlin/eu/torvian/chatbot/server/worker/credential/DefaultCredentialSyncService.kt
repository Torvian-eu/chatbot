package eu.torvian.chatbot.server.worker.credential

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialDeleteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchFailure
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchOutcomeDto
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.WorkerCredentialProfileService
import eu.torvian.chatbot.server.service.core.WorkerCredentialSecretService
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [CredentialSyncService].
 *
 * Each operation persists through the credential services, which own their transactions, and only then
 * pushes the stored row. Nothing here compensates a failed push: the row is the only copy of the
 * operator's input, and the worker's next bootstrap replays it.
 *
 * @property profileService Persistence and reading of credential profiles.
 * @property secretService Persistence and reading of encrypted credential secrets.
 * @property dispatchService Best-effort push of one stored change to a worker.
 * @property logger Diagnostic sink; receives identifiers and classifications only.
 */
class DefaultCredentialSyncService(
    private val profileService: WorkerCredentialProfileService,
    private val secretService: WorkerCredentialSecretService,
    private val dispatchService: CredentialCommandDispatchService,
    private val logger: Logger = LogManager.getLogger(DefaultCredentialSyncService::class.java),
) : CredentialSyncService {

    override suspend fun createProfile(
        userId: Long,
        request: CreateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileWriteResultDto> = either {
        val profile = profileService.createProfile(userId, request, signedRequest).bind()
        val signedProfile = profileService.getAssignedProfile(profile.id).bind()
        val failure = dispatchService.createProfile(profile.workerId, signedProfile).logFailure(profile.workerId)
        CredentialProfileWriteResultDto(
            profile = profile,
            dispatch = WorkerDispatchOutcomeDto(workerId = profile.workerId, failure = failure)
        )
    }

    override suspend fun updateProfile(
        userId: Long,
        profileId: Long,
        request: UpdateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileWriteResultDto> = either {
        val previous = profileService.getProfile(userId, profileId).bind()
        val profile = profileService.updateProfile(userId, profileId, request, signedRequest).bind()
        val signedProfile = profileService.getAssignedProfile(profile.id).bind()

        // A profile may move workers because its body carries no secret; the previous worker only learns
        // about the move through a delete, so that command is issued first. The move has two workers, so
        // both outcomes are reported: `dispatch` covers the new owner and `previousWorkerDispatch` covers
        // the withdrawal. A failed withdrawal leaves the profile attachable on the old worker with the
        // secret it already holds, which the operator must be able to see and retry.
        val moved = previous.workerId != profile.workerId
        val previousWorkerDispatch = if (moved) {
            val failure = dispatchService.deleteProfile(previous.workerId, profileId).logFailure(previous.workerId)
            WorkerDispatchOutcomeDto(workerId = previous.workerId, failure = failure)
        } else {
            null
        }
        val failure = if (moved) {
            dispatchService.createProfile(profile.workerId, signedProfile).logFailure(profile.workerId)
        } else {
            dispatchService.updateProfile(profile.workerId, signedProfile).logFailure(profile.workerId)
        }

        CredentialProfileWriteResultDto(
            profile = profile,
            dispatch = WorkerDispatchOutcomeDto(workerId = profile.workerId, failure = failure),
            previousWorkerDispatch = previousWorkerDispatch,
        )
    }

    override suspend fun deleteProfile(
        userId: Long,
        profileId: Long,
    ): Either<WorkerCredentialServiceError, CredentialDeleteResultDto> = either {
        val profile = profileService.deleteProfile(userId, profileId).bind()
        val failure = dispatchService.deleteProfile(profile.workerId, profile.id).logFailure(profile.workerId)
        CredentialDeleteResultDto(
            deletedId = profile.id,
            dispatch = WorkerDispatchOutcomeDto(workerId = profile.workerId, failure = failure)
        )
    }

    override suspend fun createSecret(
        userId: Long,
        request: CreateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretWriteResultDto> = either {
        val secret = secretService.createSecret(userId, request).bind()
        val workerSecret = secretService.getAssignedSecret(secret.id).bind()
        val failure = dispatchService.createSecret(secret.workerId, workerSecret).logFailure(secret.workerId)
        CredentialSecretWriteResultDto(
            secret = secret,
            dispatch = WorkerDispatchOutcomeDto(workerId = secret.workerId, failure = failure)
        )
    }

    override suspend fun updateSecret(
        userId: Long,
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretWriteResultDto> = either {
        val secret = secretService.updateSecret(userId, secretId, request).bind()
        val workerSecret = secretService.getAssignedSecret(secret.id).bind()
        val failure = dispatchService.updateSecret(secret.workerId, workerSecret).logFailure(secret.workerId)
        CredentialSecretWriteResultDto(
            secret = secret,
            dispatch = WorkerDispatchOutcomeDto(workerId = secret.workerId, failure = failure)
        )
    }

    override suspend fun deleteSecret(
        userId: Long,
        secretId: Long,
    ): Either<WorkerCredentialServiceError, CredentialDeleteResultDto> = either {
        val secret = secretService.deleteSecret(userId, secretId).bind()
        val failure = dispatchService.deleteSecret(secret.workerId, secret.id).logFailure(secret.workerId)
        CredentialDeleteResultDto(
            deletedId = secret.id,
            dispatch = WorkerDispatchOutcomeDto(workerId = secret.workerId, failure = failure)
        )
    }

    /**
     * Extracts the classified failure of one push and records it when delivery did not happen.
     *
     * @param workerId Worker the change was pushed to.
     * @return The failure, or null when the change was delivered.
     */
    private fun Either<WorkerDispatchFailure, Unit>.logFailure(workerId: Long): WorkerDispatchFailure? =
        fold(
            ifLeft = { failure ->
                logger.warn(
                    "Credential change for worker {} was persisted but not delivered: {}",
                    workerId,
                    failure
                )
                failure
            },
            ifRight = { null }
        )
}
