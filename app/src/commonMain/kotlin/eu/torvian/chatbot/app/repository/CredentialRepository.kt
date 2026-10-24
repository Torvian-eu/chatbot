package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchFailure
import kotlinx.coroutines.flow.StateFlow

/**
 * A persisted credential change that the worker has not accepted yet.
 *
 * The write succeeded; only the push did not. The UI reports this instead of failing the save, because
 * the row cannot be recreated from anything the operator still holds and the worker reconciles at its
 * next bootstrap.
 *
 * @property workerId Worker that has not learned about the change.
 * @property failure Classified reason the push did not land.
 * @property label Name of the affected profile or reference of the affected secret.
 */
data class CredentialDispatchWarning(
    val workerId: Long,
    val failure: WorkerDispatchFailure,
    val label: String,
)

/**
 * Repository for credential profiles and secrets.
 *
 * Profiles and secrets are read per worker, because both are bound to exactly one worker. No read model
 * carries a secret value: the server cannot decrypt one, so there is nothing to return and nothing to
 * display.
 */
interface CredentialRepository {
    /** Profiles currently loaded for the selected worker. */
    val profiles: StateFlow<DataState<RepositoryError, List<CredentialProfileDto>>>

    /** Secret metadata currently loaded for the selected worker. */
    val secrets: StateFlow<DataState<RepositoryError, List<CredentialSecretMetadataDto>>>

    /** Warning raised by the last write that was persisted but not delivered, if any. */
    val dispatchWarning: StateFlow<CredentialDispatchWarning?>

    /**
     * Loads the profiles of [workerId].
     *
     * @param workerId Worker whose profiles are wanted.
     * @return Either a repository error or success.
     */
    suspend fun loadProfiles(workerId: Long): Either<RepositoryError, Unit>

    /**
     * Loads the secret metadata of [workerId].
     *
     * @param workerId Worker whose secrets are wanted.
     * @return Either a repository error or success.
     */
    suspend fun loadSecrets(workerId: Long): Either<RepositoryError, Unit>

    /**
     * Persists a new profile and reports a degraded delivery through [dispatchWarning].
     *
     * @param request Signed profile body.
     * @return Either a repository error or the stored profile.
     */
    suspend fun createProfile(
        request: CreateCredentialProfileRequest,
    ): Either<RepositoryError, CredentialProfileDto>

    /**
     * Persists a profile replacement and reports a degraded delivery through [dispatchWarning].
     *
     * @param profileId Profile being replaced.
     * @param request Signed replacement body.
     * @return Either a repository error or the stored profile.
     */
    suspend fun updateProfile(
        profileId: Long,
        request: UpdateCredentialProfileRequest,
    ): Either<RepositoryError, CredentialProfileDto>

    /**
     * Deletes a profile and reports a degraded delivery through [dispatchWarning].
     *
     * @param profileId Profile to delete.
     * @return Either a repository error or success.
     */
    suspend fun deleteProfile(profileId: Long): Either<RepositoryError, Unit>

    /**
     * Stores a new secret value.
     *
     * @param request Worker, reference and plaintext value.
     * @return Either a repository error or the stored metadata.
     */
    suspend fun createSecret(
        request: CreateCredentialSecretRequest,
    ): Either<RepositoryError, CredentialSecretMetadataDto>

    /**
     * Replaces a stored secret value.
     *
     * @param secretId Secret being replaced.
     * @param request The unchanged reference and the new plaintext value.
     * @return Either a repository error or the stored metadata.
     */
    suspend fun updateSecret(
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<RepositoryError, CredentialSecretMetadataDto>

    /**
     * Deletes a secret.
     *
     * @param secretId Secret to delete.
     * @return Either a repository error or success.
     */
    suspend fun deleteSecret(secretId: Long): Either<RepositoryError, Unit>

    /**
     * Clears the current dispatch warning, typically after it has been shown.
     */
    fun dismissDispatchWarning()
}
