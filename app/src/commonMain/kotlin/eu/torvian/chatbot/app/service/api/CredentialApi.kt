package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialDeleteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest

/**
 * Frontend API for credential profiles and secrets.
 *
 * Profile writes carry a device signature because the profile's fixed command is what the user
 * approves; secret writes carry the plaintext exactly once and never read it back.
 */
interface CredentialApi {
    /**
     * @param workerId Worker whose profiles are wanted.
     * @return Either a transport error or the profiles of that worker.
     */
    suspend fun getProfiles(workerId: Long): Either<ApiResourceError, List<CredentialProfileDto>>

    /**
     * @param profileId Profile to read.
     * @return Either a transport error or the stored profile.
     */
    suspend fun getProfile(profileId: Long): Either<ApiResourceError, CredentialProfileDto>

    /**
     * @param request Signed profile body.
     * @return Either a transport error or the stored profile with its delivery outcome.
     */
    suspend fun createProfile(
        request: CreateCredentialProfileRequest,
    ): Either<ApiResourceError, CredentialProfileWriteResultDto>

    /**
     * @param profileId Profile being replaced.
     * @param request Signed replacement body.
     * @return Either a transport error or the stored profile with its delivery outcome.
     */
    suspend fun updateProfile(
        profileId: Long,
        request: UpdateCredentialProfileRequest,
    ): Either<ApiResourceError, CredentialProfileWriteResultDto>

    /**
     * @param profileId Profile to delete.
     * @return Either a transport error or the deletion result with its delivery outcome.
     */
    suspend fun deleteProfile(profileId: Long): Either<ApiResourceError, CredentialDeleteResultDto>

    /**
     * @param workerId Worker whose secrets are wanted.
     * @return Either a transport error or metadata for that worker's secrets.
     */
    suspend fun getSecrets(workerId: Long): Either<ApiResourceError, List<CredentialSecretMetadataDto>>

    /**
     * @param request Worker, reference and the plaintext value.
     * @return Either a transport error or the stored metadata with its delivery outcome.
     */
    suspend fun createSecret(
        request: CreateCredentialSecretRequest,
    ): Either<ApiResourceError, CredentialSecretWriteResultDto>

    /**
     * @param secretId Secret being replaced.
     * @param request The unchanged reference and the new plaintext value.
     * @return Either a transport error or the stored metadata with its delivery outcome.
     */
    suspend fun updateSecret(
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<ApiResourceError, CredentialSecretWriteResultDto>

    /**
     * @param secretId Secret to delete.
     * @return Either a transport error or the deletion result with its delivery outcome.
     */
    suspend fun deleteSecret(secretId: Long): Either<ApiResourceError, CredentialDeleteResultDto>
}
