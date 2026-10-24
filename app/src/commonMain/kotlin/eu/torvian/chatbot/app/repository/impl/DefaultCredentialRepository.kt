package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.CredentialDispatchWarning
import eu.torvian.chatbot.app.repository.CredentialRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.CredentialApi
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchOutcomeDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default [CredentialRepository] backed by the credential API.
 *
 * Every write keeps its result and only records a warning when the worker did not accept the push;
 * nothing here rolls a save back, because the row is already durable and the plaintext of a secret
 * cannot be recreated.
 *
 * @property api Credential API client.
 */
class DefaultCredentialRepository(
    private val api: CredentialApi,
) : CredentialRepository {

    private val _profiles = MutableStateFlow<DataState<RepositoryError, List<CredentialProfileDto>>>(DataState.Idle)
    override val profiles: StateFlow<DataState<RepositoryError, List<CredentialProfileDto>>> =
        _profiles.asStateFlow()

    private val _secrets =
        MutableStateFlow<DataState<RepositoryError, List<CredentialSecretMetadataDto>>>(DataState.Idle)
    override val secrets: StateFlow<DataState<RepositoryError, List<CredentialSecretMetadataDto>>> =
        _secrets.asStateFlow()

    private val _dispatchWarning = MutableStateFlow<CredentialDispatchWarning?>(null)
    override val dispatchWarning: StateFlow<CredentialDispatchWarning?> = _dispatchWarning.asStateFlow()

    override suspend fun loadProfiles(workerId: Long): Either<RepositoryError, Unit> {
        _profiles.update { DataState.Loading }
        return api.getProfiles(workerId).fold(
            ifLeft = { error ->
                val mapped = error.toRepositoryError("Failed to load credential profiles")
                _profiles.update { DataState.Error(mapped) }
                Either.Left(mapped)
            },
            ifRight = { loaded ->
                _profiles.update { DataState.Success(loaded) }
                Either.Right(Unit)
            }
        )
    }

    override suspend fun loadSecrets(workerId: Long): Either<RepositoryError, Unit> {
        _secrets.update { DataState.Loading }
        return api.getSecrets(workerId).fold(
            ifLeft = { error ->
                val mapped = error.toRepositoryError("Failed to load credential secrets")
                _secrets.update { DataState.Error(mapped) }
                Either.Left(mapped)
            },
            ifRight = { loaded ->
                _secrets.update { DataState.Success(loaded) }
                Either.Right(Unit)
            }
        )
    }

    override suspend fun createProfile(
        request: CreateCredentialProfileRequest,
    ): Either<RepositoryError, CredentialProfileDto> = either {
        val result = withError({ error: ApiResourceError ->
            error.toRepositoryError("Failed to create credential profile")
        }) {
            api.createProfile(request).bind()
        }
        recordDispatch(request.name, result.dispatch)
        appendProfile(result.profile)
        result.profile
    }

    override suspend fun updateProfile(
        profileId: Long,
        request: UpdateCredentialProfileRequest,
    ): Either<RepositoryError, CredentialProfileDto> = either {
        val result = withError({ error: ApiResourceError ->
            error.toRepositoryError("Failed to update credential profile")
        }) {
            api.updateProfile(profileId, request).bind()
        }
        recordDispatch(request.name, result.dispatch, result.previousWorkerDispatch)
        appendProfile(result.profile)
        result.profile
    }

    override suspend fun deleteProfile(profileId: Long): Either<RepositoryError, Unit> = either {
        val result = withError({ error: ApiResourceError ->
            error.toRepositoryError("Failed to delete credential profile")
        }) {
            api.deleteProfile(profileId).bind()
        }
        recordDispatch(profileId.toString(), result.dispatch)
        _profiles.update { current ->
            when (current) {
                is DataState.Success -> DataState.Success(current.data.filterNot { it.id == profileId })
                else -> current
            }
        }
    }

    override suspend fun createSecret(
        request: CreateCredentialSecretRequest,
    ): Either<RepositoryError, CredentialSecretMetadataDto> = either {
        val result = withError({ error: ApiResourceError ->
            error.toRepositoryError("Failed to store credential secret")
        }) {
            api.createSecret(request).bind()
        }
        recordDispatch(result.secret.reference, result.dispatch)
        appendSecret(result.secret)
        result.secret
    }

    override suspend fun updateSecret(
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<RepositoryError, CredentialSecretMetadataDto> = either {
        val result = withError({ error: ApiResourceError ->
            error.toRepositoryError("Failed to replace credential secret")
        }) {
            api.updateSecret(secretId, request).bind()
        }
        recordDispatch(result.secret.reference, result.dispatch)
        appendSecret(result.secret)
        result.secret
    }

    override suspend fun deleteSecret(secretId: Long): Either<RepositoryError, Unit> = either {
        val result = withError({ error: ApiResourceError ->
            error.toRepositoryError("Failed to delete credential secret")
        }) {
            api.deleteSecret(secretId).bind()
        }
        recordDispatch(secretId.toString(), result.dispatch)
        _secrets.update { current ->
            when (current) {
                is DataState.Success -> DataState.Success(current.data.filterNot { it.id == secretId })
                else -> current
            }
        }
    }

    override fun dismissDispatchWarning() {
        _dispatchWarning.value = null
    }

    /**
     * Records a warning when the persisted change was not accepted by every worker involved.
     *
     * A profile that moved workers has two outcomes. The new owner is reported first because it is the
     * worker the operator just chose; the withdrawal from the previous worker is reported when the new
     * owner accepted the change, since that worker still offers the profile with the secret it holds.
     *
     * @param label Name or reference of the affected entity.
     * @param dispatch Delivery outcome of the profile's current worker.
     * @param previousWorkerDispatch Outcome of withdrawing the profile from the worker it moved away
     *   from, or null when the profile did not move.
     */
    private fun recordDispatch(
        label: String,
        dispatch: WorkerDispatchOutcomeDto,
        previousWorkerDispatch: WorkerDispatchOutcomeDto? = null,
    ) {
        val failed = listOfNotNull(dispatch, previousWorkerDispatch).firstOrNull { it.failure != null }
        _dispatchWarning.value = failed?.failure?.let { failure ->
            CredentialDispatchWarning(workerId = failed.workerId, failure = failure, label = label)
        }
    }

    /**
     * Adds or replaces a profile in the loaded list.
     *
     * @param profile Stored profile returned by the server.
     */
    private fun appendProfile(profile: CredentialProfileDto) {
        _profiles.update { current ->
            when (current) {
                is DataState.Success -> DataState.Success(
                    current.data.filterNot { it.id == profile.id } + profile
                )
                else -> current
            }
        }
    }

    /**
     * Adds or replaces a secret metadata row in the loaded list.
     *
     * @param secret Stored metadata returned by the server.
     */
    private fun appendSecret(secret: CredentialSecretMetadataDto) {
        _secrets.update { current ->
            when (current) {
                is DataState.Success -> DataState.Success(
                    current.data.filterNot { it.id == secret.id } + secret
                )
                else -> current
            }
        }
    }
}
