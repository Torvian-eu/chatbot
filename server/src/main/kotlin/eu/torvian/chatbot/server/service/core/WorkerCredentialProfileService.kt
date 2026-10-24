package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError

/**
 * Manages worker-bound credential profiles.
 *
 * A profile holds no secret value. Creation and update persist the operator-authored body together
 * with the device signature over that exact body, because the worker re-verifies the signature before
 * it caches a profile whose fixed command it may later run.
 */
interface WorkerCredentialProfileService {
    /**
     * Persists a new profile and its signature.
     *
     * @param userId Authenticated user; must own [CreateCredentialProfileRequest.workerId].
     * @param request Signed profile body.
     * @param signedRequest Detached signature over the exact raw request body.
     * @return Either a typed failure or the stored profile.
     */
    suspend fun createProfile(
        userId: Long,
        request: CreateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto>

    /**
     * Replaces an existing profile and stores the new signature.
     *
     * @param userId Authenticated user; must own both the old and the new worker.
     * @param profileId Profile being replaced.
     * @param request Signed replacement body.
     * @param signedRequest Detached signature over the exact raw request body.
     * @return Either a typed failure or the stored profile.
     */
    suspend fun updateProfile(
        userId: Long,
        profileId: Long,
        request: UpdateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto>

    /**
     * Deletes a profile and its signatures.
     *
     * @param userId Authenticated user; must own the profile's worker.
     * @param profileId Profile to delete.
     * @return Either a typed failure or the profile as it was before deletion.
     */
    suspend fun deleteProfile(
        userId: Long,
        profileId: Long,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto>

    /**
     * @param userId Authenticated user; must own the worker.
     * @param workerId Worker whose profiles are wanted.
     * @return Either a typed failure or every profile bound to the worker.
     */
    suspend fun getProfiles(
        userId: Long,
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<CredentialProfileDto>>

    /**
     * @param userId Authenticated user; must own the profile's worker.
     * @param profileId Profile to read.
     * @return Either a typed failure or the stored profile.
     */
    suspend fun getProfile(
        userId: Long,
        profileId: Long,
    ): Either<WorkerCredentialServiceError, CredentialProfileDto>

    /**
     * Reads the profiles a worker is assigned, each with its persisted signature.
     *
     * Called on the worker-authenticated bootstrap path, so it is scoped by worker alone.
     *
     * @param workerId Worker whose assignments are wanted.
     * @return Either a typed failure or the assigned profiles.
     */
    suspend fun getAssignedProfiles(
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<SignedCredentialProfileDto>>

    /**
     * Reads the enabled profiles of one worker, as used by the profile advertisement.
     *
     * @param workerId Worker whose enabled profiles are wanted.
     * @return Either a typed failure or the enabled profiles.
     */
    suspend fun getEnabledProfiles(
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<CredentialProfileDto>>

    /**
     * Reads one profile together with its persisted signature, for relay to its worker.
     *
     * @param profileId Profile to read.
     * @return Either a typed failure or the profile with the signature to relay.
     */
    suspend fun getAssignedProfile(
        profileId: Long,
    ): Either<WorkerCredentialServiceError, SignedCredentialProfileDto>
}
