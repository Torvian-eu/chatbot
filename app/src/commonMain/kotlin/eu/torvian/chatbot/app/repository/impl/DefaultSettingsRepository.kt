package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SettingsRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.ModelSettings
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

    private val _settings = MutableStateFlow<DataState<RepositoryError, List<ModelSettings>>>(DataState.Idle)
    override val settings: StateFlow<DataState<RepositoryError, List<ModelSettings>>> = _settings.asStateFlow()

    override fun getSettingsFlow(settingsId: Long): Flow<DataState<RepositoryError, ModelSettings>> {
        // Return a cold Flow that derives from the main settings StateFlow
        return settings.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val settings = dataState.data.find { it.id == settingsId }
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

    override suspend fun loadSettings(): Either<RepositoryError, List<ModelSettings>> {
        _settings.update { DataState.Loading }

        return settingsApi.getAllSettings()
            .map { settingsList ->
                _settings.update { DataState.Success(settingsList) }
                settingsList
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load settings")
                _settings.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun loadSettingsDetails(settingsId: Long): Either<RepositoryError, ModelSettings> {
        return settingsApi.getSettingsById(settingsId)
            .map { settings ->
                // Update the main list with the detailed settings data
                updateSettingsState { list ->
                    if (list.any { it.id == settings.id })
                        list.map { if (it.id == settings.id) settings else it }
                    else list + settings
                }
                settings
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to load settings details")
            }
    }

    override suspend fun loadSettingsByModelId(modelId: Long): Either<RepositoryError, List<ModelSettings>> {
        return settingsApi.getSettingsByModelId(modelId)
            .map { settingsList ->
                updateSettingsState { current ->
                    val filteredSettings = current.filter { it.modelId != modelId }
                    filteredSettings + settingsList
                }
                settingsList
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load settings by model ID")
                _settings.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun addModelSettings(settings: ModelSettings): Either<RepositoryError, ModelSettings> {
        return settingsApi.addModelSettings(settings)
            .map { newSettings ->
                updateSettingsState { it + newSettings }
                newSettings
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add model settings")
            }
    }

    override suspend fun getSettingsById(settingsId: Long): Either<RepositoryError, ModelSettings> {
        return settingsApi.getSettingsById(settingsId)
            .map { settings ->
                updateSettingsState { list -> list.map { if (it.id == settings.id) settings else it } }
                settings
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to get settings by ID")
            }
    }

    override suspend fun updateSettings(settings: ModelSettings): Either<RepositoryError, Unit> {
        return settingsApi.updateSettings(settings)
            .map {
                updateSettingsState { list -> list.map { if (it.id == settings.id) settings else it } }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update settings")
            }
    }

    override suspend fun deleteSettings(settingsId: Long): Either<RepositoryError, Unit> {
        return settingsApi.deleteSettings(settingsId)
            .map {
                updateSettingsState { list -> list.filter { it.id != settingsId } }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete settings")
            }
    }

    /**
     * Updates the internal StateFlow of settings using the provided transformation.
     */
    private fun updateSettingsState(transform: (List<ModelSettings>) -> List<ModelSettings>) {
        _settings.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> {
                    logger.warn("Tried to update settings but they're not in Success state")
                    currentState
                }
            }
        }
    }
}
