package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
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
import eu.torvian.chatbot.common.security.SignedRequest
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Applies the six credential sync commands to the worker cache.
 *
 * Profiles are re-validated against the shared rules and their device signature before they are
 * cached; secrets are accepted after the worker-id check alone, because the server produces their
 * ciphertext and no device could sign it. Both checks compare against the worker id this worker's
 * owner pinned in `worker.identity.workerId`, so a row bound to another worker is refused as long as
 * the pin is configured.
 */
interface CredentialSyncCommandExecutor {
    /**
     * @param request Relayed profile plus signature.
     * @return Either a protocol error payload or the applied identifier.
     */
    suspend fun createProfile(
        request: WorkerCredentialProfileCreateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialProfileCreateResultData>

    /**
     * @param request Relayed profile plus signature.
     * @return Either a protocol error payload or the applied identifier.
     */
    suspend fun updateProfile(
        request: WorkerCredentialProfileUpdateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialProfileUpdateResultData>

    /**
     * @param request Profile identifier to drop.
     * @return Either a protocol error payload or the removed identifier.
     */
    suspend fun deleteProfile(
        request: WorkerCredentialProfileDeleteCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialProfileDeleteResultData>

    /**
     * @param request Encrypted secret to cache.
     * @return Either a protocol error payload or the applied identifier.
     */
    suspend fun createSecret(
        request: WorkerCredentialSecretCreateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialSecretCreateResultData>

    /**
     * @param request Encrypted secret to cache.
     * @return Either a protocol error payload or the applied identifier.
     */
    suspend fun updateSecret(
        request: WorkerCredentialSecretUpdateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialSecretUpdateResultData>

    /**
     * @param request Secret identifier to drop.
     * @return Either a protocol error payload or the removed identifier.
     */
    suspend fun deleteSecret(
        request: WorkerCredentialSecretDeleteCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialSecretDeleteResultData>
}

/**
 * Default [CredentialSyncCommandExecutor].
 *
 * A rejected notification leaves the cache as it was, so a live attached target is never disturbed by
 * an operator change: only the next `attach` sees the new state.
 *
 * @property profileValidator Authorization validator applied to every relayed profile.
 * @property store Cache updated by the accepted commands.
 * @property pinnedWorkerId Worker id configured as `worker.identity.workerId`, or null when no pin is
 *   configured and the per-row check is therefore inactive.
 */
class CredentialSyncCommandExecutorImpl(
    private val profileValidator: CredentialProfileValidator,
    private val store: WorkerCredentialStore,
    private val pinnedWorkerId: Long?,
) : CredentialSyncCommandExecutor {

    override suspend fun createProfile(
        request: WorkerCredentialProfileCreateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialProfileCreateResultData> =
        authorizeProfile(
            request.signedProfile.profile.id,
            request.signedProfile.profile,
            request.signedProfile.signedRequest,
        ).map {
            store.upsertProfile(request.signedProfile.profile.toCredentialProfile())
            WorkerCredentialProfileCreateResultData(profileId = request.signedProfile.profile.id)
        }

    override suspend fun updateProfile(
        request: WorkerCredentialProfileUpdateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialProfileUpdateResultData> =
        authorizeProfile(
            request.signedProfile.profile.id,
            request.signedProfile.profile,
            request.signedProfile.signedRequest,
        ).map {
            store.upsertProfile(request.signedProfile.profile.toCredentialProfile())
            WorkerCredentialProfileUpdateResultData(profileId = request.signedProfile.profile.id)
        }

    override suspend fun deleteProfile(
        request: WorkerCredentialProfileDeleteCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialProfileDeleteResultData> {
        store.removeProfile(request.profileId)
        return WorkerCredentialProfileDeleteResultData(profileId = request.profileId).right()
    }

    override suspend fun createSecret(
        request: WorkerCredentialSecretCreateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialSecretCreateResultData> = either {
        acceptSecret(request.secret.toEncryptedWorkerSecret(), request.secret.id).bind()
        WorkerCredentialSecretCreateResultData(secretId = request.secret.id)
    }

    override suspend fun updateSecret(
        request: WorkerCredentialSecretUpdateCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialSecretUpdateResultData> = either {
        acceptSecret(request.secret.toEncryptedWorkerSecret(), request.secret.id).bind()
        WorkerCredentialSecretUpdateResultData(secretId = request.secret.id)
    }

    override suspend fun deleteSecret(
        request: WorkerCredentialSecretDeleteCommandData,
    ): Either<WorkerCredentialCommandErrorResultData, WorkerCredentialSecretDeleteResultData> {
        store.removeSecret(request.secretId)
        return WorkerCredentialSecretDeleteResultData(secretId = request.secretId).right()
    }

    /**
     * Caches a relayed secret after checking that it is bound to the pinned worker.
     *
     * Secrecy is not what this check protects - the ciphertext is useless without the worker's key -
     * but a row cached under another worker's reference would be attached by this worker's profiles on
     * a server-side join or pagination mistake. A null pin means the check is not configured, so the
     * row is cached as before.
     *
     * @param secret Encrypted secret relayed by the server.
     * @param secretId Identifier the command targets, reported with a rejection.
     * @return Either a protocol error payload or `Unit` when the row may be cached.
     */
    private fun acceptSecret(
        secret: EncryptedWorkerSecret,
        secretId: Long,
    ): Either<WorkerCredentialCommandErrorResultData, Unit> {
        val expectedWorkerId = pinnedWorkerId
        if (expectedWorkerId != null && secret.workerId != expectedWorkerId) {
            warnForeignCredentialRow(expectedWorkerId, secret.workerId)
            return WorkerCredentialCommandErrorResultData(
                entityId = secretId,
                code = FOREIGN_WORKER_CODE,
                message = FOREIGN_WORKER_MESSAGE,
                details = "expected=$expectedWorkerId actual=${secret.workerId}",
            ).left()
        }
        store.upsertSecret(secret)
        return Unit.right()
    }

    /**
     * Applies the profile validator and converts a rejection into a protocol error payload.
     *
     * @param profileId Profile identifier the command concerns.
     * @param profile Relay between the signed payload and the cache.
     * @param signedRequest Detached signature over the persisted payload.
     * @return Either a protocol error payload or `Unit` when the profile may be cached.
     */
    private suspend fun authorizeProfile(
        profileId: Long,
        profile: CredentialProfileDto,
        signedRequest: SignedRequest?,
    ): Either<WorkerCredentialCommandErrorResultData, Unit> =
        when (val result = profileValidator.validate(profile, signedRequest, workerId = pinnedWorkerId)) {
            CredentialProfileValidationResult.Authorized -> Unit.right()
            is CredentialProfileValidationResult.Rejected -> {
                if (result is CredentialProfileValidationResult.ForeignWorker) {
                    warnForeignCredentialRow(result.expected, result.actual)
                }
                WorkerCredentialCommandErrorResultData(
                    entityId = profileId,
                    code = result.code,
                    message = result.message,
                    details = result.details,
                ).left()
            }
        }

    /**
     * Logs one refusal of a row bound to another worker.
     *
     * The reply frame the interaction already emits mentions only a code and a message, so this line is
     * the only place the configured key and both ids are recorded; it carries no secret material.
     *
     * @param expected Worker id configured as `worker.identity.workerId`.
     * @param actual Worker id the relayed row is bound to.
     */
    private fun warnForeignCredentialRow(expected: Long, actual: Long) {
        logger.warn(
            "Refused a relayed credential row bound to another worker: worker.identity.workerId={}, row.workerId={}",
            expected,
            actual
        )
    }

    private companion object {
        /** Code reported when a relayed secret row is bound to another worker. */
        const val FOREIGN_WORKER_CODE = "CREDENTIAL_SECRET_FOREIGN_WORKER"

        /** Message reported with [FOREIGN_WORKER_CODE]; names no reference, value or ciphertext. */
        const val FOREIGN_WORKER_MESSAGE = "Relayed credential secret is bound to a different worker"

        /** Logger for refusal diagnostics; never receives a reference, plaintext or ciphertext. */
        val logger: Logger = LogManager.getLogger(CredentialSyncCommandExecutorImpl::class.java)
    }
}
