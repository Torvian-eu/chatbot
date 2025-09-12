package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ProviderRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.common.models.AddProviderRequest
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.UpdateProviderCredentialRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [ProviderRepository] that manages LLM provider configurations.
 *
 * This repository maintains an internal cache of provider data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates API operations to the injected
 * [ProviderApi] and handles comprehensive error management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * @property providerApi The API client for provider-related operations
 */
class DefaultProviderRepository(
    private val providerApi: ProviderApi
) : ProviderRepository {

    private val _providers = MutableStateFlow<DataState<RepositoryError, List<LLMProvider>>>(DataState.Idle)
    override val providers: StateFlow<DataState<RepositoryError, List<LLMProvider>>> = _providers.asStateFlow()

    override suspend fun loadProviders(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_providers.value.isLoading) return Unit.right()

        _providers.update { DataState.Loading }

        return providerApi.getAllProviders()
            .map { providerList ->
                _providers.update { DataState.Success(providerList) }
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load providers")
                _providers.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun addProvider(request: AddProviderRequest): Either<RepositoryError, LLMProvider> {
        return providerApi.addProvider(request)
            .map { newProvider ->
                updateProvidersState { it + newProvider }
                newProvider
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add provider")
            }
    }

    override suspend fun getProviderById(providerId: Long): Either<RepositoryError, LLMProvider> {
        return providerApi.getProviderById(providerId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to get provider by ID")
            }
    }

    override suspend fun updateProvider(provider: LLMProvider): Either<RepositoryError, Unit> {
        return providerApi.updateProvider(provider)
            .map {
                updateProvidersState { list -> list.map { if (it.id == provider.id) provider else it } }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update provider")
            }
    }

    override suspend fun deleteProvider(providerId: Long): Either<RepositoryError, Unit> {
        return providerApi.deleteProvider(providerId)
            .map {
                updateProvidersState { list -> list.filter { it.id != providerId } }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete provider")
            }
    }

    override suspend fun updateProviderCredential(
        providerId: Long,
        request: UpdateProviderCredentialRequest
    ): Either<RepositoryError, Unit> {
        return providerApi.updateProviderCredential(providerId, request)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update provider credential")
            }
        // Note: Credential updates don't affect the provider metadata in the StateFlow
        // The provider list remains unchanged as credentials are stored separately
    }

    override suspend fun getModelsByProviderId(providerId: Long): Either<RepositoryError, List<LLMModel>> {
        return providerApi.getModelsByProviderId(providerId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to get models by provider ID")
            }
    }

    /**
     * Updates the internal StateFlow of providers using the provided transformation.
     *
     * @param transform A function that takes the current list of providers and returns an updated list.
     */
    private fun updateProvidersState(transform: (List<LLMProvider>) -> List<LLMProvider>) {
        _providers.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> currentState
            }
        }
    }
}
