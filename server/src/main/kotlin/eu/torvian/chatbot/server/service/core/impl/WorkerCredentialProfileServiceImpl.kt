package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileFields
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileRules
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileDao
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileSignatureDao
import eu.torvian.chatbot.server.data.dao.WorkerCredentialSecretDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileSignatureEntity
import eu.torvian.chatbot.server.service.core.WorkerCredentialProfileService
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Default [WorkerCredentialProfileService].
 *
 * Authorization follows worker ownership: a profile is bound to exactly one worker, so the worker's
 * owner is the only principal allowed to read or mutate it. Validation runs the same rule set the
 * worker applies before caching a profile.
 *
 * @property profileDao Persistence gateway for profiles.
 * @property profileSignatureDao Persistence gateway for device signatures.
 * @property secretDao Persistence gateway used to confirm the referenced secret exists on the worker.
 * @property userDeviceDao Registry lookup that turns a signer id into a stored device row.
 * @property workerDao Persistence gateway used for worker ownership checks.
 * @property transactionScope Transaction boundary keeping profile and signature writes atomic.
 * @property logger Diagnostic sink; receives identifiers and profile names only.
 */
class WorkerCredentialProfileServiceImpl(
    private val profileDao: WorkerCredentialProfileDao,
    private val profileSignatureDao: WorkerCredentialProfileSignatureDao,
    private val secretDao: WorkerCredentialSecretDao,
    private val userDeviceDao: UserDeviceDao,
    private val workerDao: WorkerDao,
    private val transactionScope: TransactionScope,
    private val logger: Logger = LogManager.getLogger(WorkerCredentialProfileServiceImpl::class.java),
) : WorkerCredentialProfileService {

    override suspend fun createProfile(
        userId: Long,
        request: CreateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto> =
        transactionScope.transaction {
            either {
                validateWorkerOwnership(userId, request.workerId).bind()
                val signerDevice = userDeviceDao.getDeviceByClientId(userId, signedRequest.signerId)
                    ?: raise(WorkerCredentialServiceError.Unauthorized(userId, request.workerId))
                validateFields(request.toFields()).bind()

                val created = profileDao.createProfile(
                    CreateWorkerCredentialProfileEntity(
                        workerId = request.workerId,
                        name = request.name,
                        description = request.description,
                        secretRef = request.secretRef,
                        command = request.command,
                        args = request.args,
                        channel = request.channel,
                        envName = request.envName,
                        fileName = request.fileName,
                        askpassUsername = request.askpassUsername,
                        environmentVariables = request.environmentVariables,
                        askpassVariables = request.askpassVariables,
                        isEnabled = request.isEnabled,
                    )
                ).mapLeft { it.toServiceError() }.bind()

                storeSignature(created.id, signerDevice.id, signedRequest)
                logger.info(
                    "Created credential profile {} ({}) for worker {}",
                    created.name,
                    created.id,
                    created.workerId
                )
                created.toDto()
            }
        }

    override suspend fun updateProfile(
        userId: Long,
        profileId: Long,
        request: UpdateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto> =
        transactionScope.transaction {
            either {
                val existing = loadProfile(profileId).bind()
                validateWorkerOwnership(userId, existing.workerId).bind()
                validateWorkerOwnership(userId, request.workerId).bind()
                val signerDevice = userDeviceDao.getDeviceByClientId(userId, signedRequest.signerId)
                    ?: raise(WorkerCredentialServiceError.Unauthorized(userId, request.workerId))
                validateFields(request.toFields()).bind()

                val updated = profileDao.updateProfile(
                    profileId,
                    UpdateWorkerCredentialProfileEntity(
                        workerId = request.workerId,
                        name = request.name,
                        description = request.description,
                        secretRef = request.secretRef,
                        command = request.command,
                        args = request.args,
                        channel = request.channel,
                        envName = request.envName,
                        fileName = request.fileName,
                        askpassUsername = request.askpassUsername,
                        environmentVariables = request.environmentVariables,
                        askpassVariables = request.askpassVariables,
                        isEnabled = request.isEnabled,
                    )
                ).mapLeft { it.toServiceError() }.bind()

                storeSignature(updated.id, signerDevice.id, signedRequest)
                logger.info(
                    "Updated credential profile {} ({}) on worker {}",
                    updated.name,
                    updated.id,
                    updated.workerId
                )
                updated.toDto()
            }
        }

    override suspend fun deleteProfile(
        userId: Long,
        profileId: Long,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto> =
        transactionScope.transaction {
            either {
                val existing = loadProfile(profileId).bind()
                validateWorkerOwnership(userId, existing.workerId).bind()
                val deleted = profileDao.deleteProfile(profileId).mapLeft { it.toServiceError() }.bind()
                logger.info("Deleted credential profile {} ({})", deleted.name, deleted.id)
                deleted.toDto()
            }
        }

    override suspend fun getProfiles(
        userId: Long,
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<CredentialProfileDto>> =
        transactionScope.transaction {
            either {
                validateWorkerOwnership(userId, workerId).bind()
                profileDao.getProfilesByWorkerId(workerId).map { it.toDto() }
            }
        }

    override suspend fun getProfile(
        userId: Long,
        profileId: Long,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto> =
        transactionScope.transaction {
            either {
                val existing = loadProfile(profileId).bind()
                validateWorkerOwnership(userId, existing.workerId).bind()
                existing.toDto()
            }
        }

    override suspend fun getAssignedProfiles(
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<SignedCredentialProfileDto>> =
        transactionScope.transaction {
            either {
                val profiles = profileDao.getProfilesByWorkerId(workerId)
                if (profiles.isEmpty()) {
                    return@either emptyList()
                }

                val signaturesByProfile = profileSignatureDao
                    .getSignaturesByProfileIds(profiles.map { it.id })
                    .groupBy { it.profileId }

                profiles.map { profile ->
                    SignedCredentialProfileDto(
                        profile = profile.toDto(),
                        signedRequest = latestSignature(signaturesByProfile[profile.id].orEmpty())
                    )
                }
            }
        }

    override suspend fun getEnabledProfiles(
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<CredentialProfileDto>> =
        transactionScope.transaction {
            either {
                profileDao.getEnabledProfilesByWorkerId(workerId).map { it.toDto() }
            }
        }

    override suspend fun getAssignedProfile(
        profileId: Long,
    ): Either<WorkerCredentialServiceError, SignedCredentialProfileDto> =
        transactionScope.transaction {
            either {
                val profile = loadProfile(profileId).bind()
                SignedCredentialProfileDto(
                    profile = profile.toDto(),
                    signedRequest = latestSignature(profileSignatureDao.getSignaturesByProfileIds(listOf(profileId)))
                )
            }
        }

    /**
     * Reconstructs the detached signature for one profile from its most recent device row.
     *
     * @param signatures Signature rows of one profile.
     * @return The latest signature as a detached request, or null when the profile has none.
     */
    private suspend fun latestSignature(
        signatures: List<WorkerCredentialProfileSignatureEntity>,
    ): SignedRequest? {
        val latest = signatures.maxByOrNull { it.updatedAt } ?: return null
        val signerDevice = userDeviceDao.getDeviceById(latest.userDeviceId)
        if (signerDevice == null) {
            logger.warn(
                "Cannot reconstruct credential profile signature for profile {} because signer device {} no longer exists",
                latest.profileId,
                latest.userDeviceId
            )
            return null
        }

        return SignedRequest(
            payload = latest.payloadJson,
            signature = latest.signature,
            signerId = signerDevice.clientDeviceId,
            timestamp = latest.timestamp,
            nonce = latest.nonce,
        )
    }

    /**
     * Stores the signature over the exact payload the app signed.
     *
     * @param profileId Profile the signature belongs to.
     * @param userDeviceId Internal device row of the signer.
     * @param signedRequest Signature to persist.
     */
    private suspend fun storeSignature(profileId: Long, userDeviceId: Long, signedRequest: SignedRequest) {
        val now = Clock.System.now()
        profileSignatureDao.upsertSignature(
            WorkerCredentialProfileSignatureEntity(
                profileId = profileId,
                userDeviceId = userDeviceId,
                signature = signedRequest.signature,
                timestamp = signedRequest.timestamp,
                nonce = signedRequest.nonce,
                payloadJson = signedRequest.payload,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * Applies the shared profile rules and confirms the referenced secret exists on the worker.
     *
     * The deployment-wide environment keys are not known to the server, so the collision rule for them
     * is enforced where the configuration lives: the worker, at bootstrap and on every notification.
     *
     * @param fields Profile fields about to be persisted.
     * @return Either a validation failure or success.
     */
    private suspend fun validateFields(fields: CredentialProfileFields): Either<WorkerCredentialServiceError, Unit> =
        either {
            val violations = CredentialProfileRules.validate(fields)
            if (violations.isNotEmpty()) {
                raise(WorkerCredentialServiceError.ValidationFailed(violations))
            }
            ensure(
                secretDao.getSecretByWorkerIdAndReference(fields.workerId, fields.secretRef) != null
            ) {
                WorkerCredentialServiceError.SecretNotAssigned(fields.workerId, fields.secretRef)
            }
        }

    /**
     * Verifies that the authenticated user owns [workerId].
     *
     * @param userId Authenticated user.
     * @param workerId Worker the row is bound to.
     * @return Either a typed failure or success.
     */
    private suspend fun validateWorkerOwnership(
        userId: Long,
        workerId: Long,
    ): Either<WorkerCredentialServiceError, Unit> = either {
        val worker = withError({ error: WorkerError.NotFound ->
            WorkerCredentialServiceError.WorkerNotFound(error.workerId)
        }) {
            workerDao.getWorkerById(workerId).bind()
        }
        ensure(worker.ownerUserId == userId) { WorkerCredentialServiceError.Unauthorized(userId, workerId) }
    }

    /**
     * @param profileId Profile identifier.
     * @return Either a not-found failure or the stored profile.
     */
    private suspend fun loadProfile(profileId: Long): Either<WorkerCredentialServiceError, WorkerCredentialProfileEntity> =
        either {
            profileDao.getProfileById(profileId)
                ?: raise(WorkerCredentialServiceError.ProfileNotFound(profileId))
        }

    /**
     * @receiver Persistence failure raised by the profile DAO.
     * @return The equivalent credential service error.
     */
    private fun WorkerCredentialPersistenceError.toServiceError(): WorkerCredentialServiceError = when (this) {
        is WorkerCredentialPersistenceError.NotFound -> WorkerCredentialServiceError.ProfileNotFound(id)
        is WorkerCredentialPersistenceError.DuplicateProfileName ->
            WorkerCredentialServiceError.DuplicateProfileName(workerId, name)
        is WorkerCredentialPersistenceError.DuplicateSecretReference ->
            WorkerCredentialServiceError.DuplicateSecretReference(workerId, reference)
    }
}
