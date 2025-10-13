package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SettingsRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlinx.coroutines.flow.*

/**
 * Default implementation of [SettingsRepository] that follows the Single Source of Truth principle.
 *
 * This repository maintains a single StateFlow of all settings and provides derived cold Flows
 * for individual settings. It delegates state management responsibility to consumers (ViewModels),
 * keeping the repository focused on data access and manipulation.
 *
 * @property settingsApi The API client for settings-related operations
 */
class DefaultSettingsRepository(
    private val settingsApi: SettingsApi
) : SettingsRepository {

    companion object {
        private val logger = kmpLogger<DefaultSettingsRepository>()
    }

    private val _allSettingsDetails = MutableStateFlow<DataState<RepositoryError, List<ModelSettingsDetails>>>(DataState.Idle)
    override val allSettingsDetails: StateFlow<DataState<RepositoryError, List<ModelSettingsDetails>>> = _allSettingsDetails.asStateFlow()

    private val _allSettings = MutableStateFlow<DataState<RepositoryError, List<ModelSettings>>>(DataState.Idle)
    override val allSettings: StateFlow<DataState<RepositoryError, List<ModelSettings>>> = _allSettings.asStateFlow()

    override fun getSettingsFlow(settingsId: Long): Flow<DataState<RepositoryError, ModelSettingsDetails?>> {
        // Return a cold Flow that derives from the main settings StateFlow
        return allSettingsDetails.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val settings = dataState.data.find { it.settings.id == settingsId }
                    if (settings != null) {
                        DataState.Success(settings)
                    } else {
                        logger.warn("Tried to get settings $settingsId but it's not in the list")
                        DataState.Error(RepositoryError.OtherError("Settings with ID $settingsId not found"))
                    }
                }

                is DataState.Error -> DataState.Error(dataState.error)
                is DataState.Loading -> DataState.Loading
                is DataState.Idle -> DataState.Idle
            }
        }
    }

    override suspend fun loadAllSettingsDetails(): Either<RepositoryError, Unit> = either {
        // Prevent duplicate loading operations
        if (_allSettingsDetails.value.isLoading) return Unit.right()

        _allSettingsDetails.update { DataState.Loading }

        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load all settings details")
        }) {
            settingsApi.getAllSettingsDetails().bind()
        }.also { detailsList ->
            _allSettingsDetails.update { DataState.Success(detailsList) }
        }
    }

    override suspend fun loadAllSettings(): Either<RepositoryError, Unit> = either {
        // Prevent duplicate loading operations
        if (_allSettings.value.isLoading) return Unit.right()

        _allSettings.update { DataState.Loading }

        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load all settings")
        }) {
            settingsApi.getAllSettings().bind()
        }.also { settingsList ->
            _allSettings.update { DataState.Success(settingsList) }
        }
    }

    override suspend fun loadSettingsDetails(settingsId: Long): Either<RepositoryError, ModelSettingsDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load settings details")
        }) {
            settingsApi.getSettingsDetails(settingsId).bind()
        }.also { settingsDetails ->
            updateSettingsState { list ->
                if (list.any { it.settings.id == settingsDetails.settings.id }) {
                    list.map { if (it.settings.id == settingsDetails.settings.id) settingsDetails else it }
                } else {
                    list + settingsDetails
                }
            }
        }
    }

    override suspend fun addModelSettings(settings: ModelSettings): Either<RepositoryError, ModelSettingsDetails> =
        either {
            val newSettings = withError({ apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add model settings")
            }) {
                settingsApi.addModelSettings(settings).bind()
            }
            // After creation, fetch details to refresh cache
            loadSettingsDetails(newSettings.id).bind()
        }

    override suspend fun updateSettings(settings: ModelSettings): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to update settings")
        }) {
            settingsApi.updateSettings(settings).bind()
        }
        loadSettingsDetails(settings.id).bind()
    }

    override suspend fun deleteSettings(settingsId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete settings")
        }) {
            settingsApi.deleteSettings(settingsId).bind()
        }
        updateSettingsState { list -> list.filter { it.settings.id != settingsId } }
    }

    override suspend fun makeSettingsPublic(settingsId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to make settings public")
        }) {
            settingsApi.makeSettingsPublic(settingsId).bind()
        }
        loadSettingsDetails(settingsId).bind()
    }

    override suspend fun makeSettingsPrivate(settingsId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to make settings private")
        }) {
            settingsApi.makeSettingsPrivate(settingsId).bind()
        }
        loadSettingsDetails(settingsId).bind()
    }

    override suspend fun grantSettingsAccess(
        settingsId: Long,
        request: GrantAccessRequest
    ): Either<RepositoryError, ModelSettingsDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to grant settings access")
        }) {
            settingsApi.grantSettingsAccess(settingsId, request).bind()
        }
        loadSettingsDetails(settingsId).bind()
    }

    override suspend fun revokeSettingsAccess(
        settingsId: Long,
        request: RevokeAccessRequest
    ): Either<RepositoryError, ModelSettingsDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to revoke settings access")
        }) {
            settingsApi.revokeSettingsAccess(settingsId, request).bind()
        }
        loadSettingsDetails(settingsId).bind()
    }

    /**
     * Updates the internal StateFlow of settings details using the provided transformation.
     *
     * @param transform A function that takes the current list of settings details and returns an updated list.
     */
    private fun updateSettingsState(transform: (List<ModelSettingsDetails>) -> List<ModelSettingsDetails>) {
        _allSettingsDetails.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> {
                    logger.warn("Tried to update settings but they're not in Success state")
                    currentState
                }
            }
        }
    }
}
