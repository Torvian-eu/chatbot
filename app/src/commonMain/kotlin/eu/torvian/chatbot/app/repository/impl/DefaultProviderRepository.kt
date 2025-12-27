package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ProviderRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
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

    companion object {
        private val logger = kmpLogger<DefaultModelRepository>()
    }

    private val _providersDetails =
        MutableStateFlow<DataState<RepositoryError, List<LLMProviderDetails>>>(DataState.Idle)
    override val providersDetails: StateFlow<DataState<RepositoryError, List<LLMProviderDetails>>> =
        _providersDetails.asStateFlow()

    private val _providers = MutableStateFlow<DataState<RepositoryError, List<LLMProvider>>>(DataState.Idle)
    override val providers: StateFlow<DataState<RepositoryError, List<LLMProvider>>> = _providers.asStateFlow()

    override suspend fun loadProvidersDetails(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_providersDetails.value.isLoading) return Unit.right()

        _providersDetails.update { DataState.Loading }

        return providerApi.getAllProviderDetails()
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load all provider details")
                _providersDetails.update { DataState.Error(repositoryError) }
                repositoryError
            }
            .map { detailsList ->
                _providersDetails.update { DataState.Success(detailsList) }
            }
    }

    override suspend fun loadProviders(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_providers.value.isLoading) return Unit.right()

        _providers.update { DataState.Loading }

        return providerApi.getAllProviders()
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load all providers")
                _providers.update { DataState.Error(repositoryError) }
                repositoryError
            }
            .map { providerList ->
                _providers.update { DataState.Success(providerList) }
            }
    }

    override suspend fun loadProviderDetails(providerId: Long): Either<RepositoryError, LLMProviderDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load provider details")
        }) {
            providerApi.getProviderDetails(providerId).bind()
        }.also { providerDetails ->
            updateProviderDetailsState { list ->
                if (list.any { it.provider.id == providerDetails.provider.id }) {
                    list.map { if (it.provider.id == providerDetails.provider.id) providerDetails else it }
                } else {
                    list + providerDetails
                }
            }
        }
    }

    override suspend fun addProvider(
        name: String,
        description: String,
        baseUrl: String,
        type: LLMProviderType,
        credential: String?
    ): Either<RepositoryError, LLMProviderDetails> =
        either {
            val newProvider = withError({ apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add provider")
            }) {
                providerApi.addProvider(
                    name = name,
                    description = description,
                    baseUrl = baseUrl,
                    type = type,
                    credential = credential
                ).bind()
            }
            loadProviderDetails(newProvider.id).bind()
        }

    override suspend fun updateProvider(provider: LLMProvider): Either<RepositoryError, Unit> =
        either {
            withError({ apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update provider")
            }) {
                providerApi.updateProvider(provider).bind()
            }
            // After update, fetch updated details to refresh cache
            loadProviderDetails(provider.id).bind()
        }

    override suspend fun deleteProvider(providerId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete provider")
        }) {
            providerApi.deleteProvider(providerId).bind()
        }
        updateProviderDetailsState { list -> list.filter { it.provider.id != providerId } }
    }

    override suspend fun updateProviderCredential(
        providerId: Long,
        credential: String?
    ): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to update provider credential")
        }) {
            providerApi.updateProviderCredential(providerId, credential).bind()
        }
        // Note: Credential updates don't affect the provider metadata in the StateFlow
        // The provider list remains unchanged as credentials are stored separately
    }

    override suspend fun getModelsByProviderId(providerId: Long): Either<RepositoryError, List<LLMModel>> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to get models by provider ID")
        }) {
            providerApi.getModelsByProviderId(providerId).bind()
        }
    }

    override suspend fun makeProviderPublic(providerId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to make provider public")
        }) {
            providerApi.makeProviderPublic(providerId).bind()
        }
        loadProviderDetails(providerId).bind()
    }

    override suspend fun makeProviderPrivate(providerId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to make provider private")
        }) {
            providerApi.makeProviderPrivate(providerId).bind()
        }
        loadProviderDetails(providerId).bind()
    }

    override suspend fun grantProviderAccess(
        providerId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RepositoryError, LLMProviderDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to grant provider access")
        }) {
            providerApi.grantProviderAccess(providerId, groupId, accessMode).bind()
        }
        loadProviderDetails(providerId).bind()
    }

    override suspend fun revokeProviderAccess(
        providerId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RepositoryError, LLMProviderDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to revoke provider access")
        }) {
            providerApi.revokeProviderAccess(providerId, groupId, accessMode).bind()
        }
        loadProviderDetails(providerId).bind()
    }

    /**
     * Updates the internal StateFlow of provider details using the provided transformation.
     *
     * @param transform A function that takes the current list of provider details and returns an updated list.
     */
    private fun updateProviderDetailsState(transform: (List<LLMProviderDetails>) -> List<LLMProviderDetails>) {
        _providersDetails.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> {
                    logger.warn("Tried to update provider details but they're not in Success state")
                    currentState
                }
            }
        }
    }
}
