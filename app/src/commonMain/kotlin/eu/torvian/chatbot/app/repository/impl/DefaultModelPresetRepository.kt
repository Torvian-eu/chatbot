package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.ModelPresetRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ModelPresetApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [ModelPresetRepository] backed by [ModelPresetApi].
 *
 * Maintains an in-memory [StateFlow] cache of the user's presets, always stored in name-ascending
 * order (U-24 — the server returns id-ascending rows and explicitly leaves the display order to the
 * client), and refreshes it after every successful CRUD operation.
 *
 * Every successful mutation additionally reloads the role stream through [agentRoleRepository],
 * because an agent role's `modelId`/`modelSettingsId` are *derived* from its preset: re-pointing a
 * preset changes those values for all bound roles, and deleting one nulls their preset reference.
 * The dependency is one-directional (`presets → roles`, mirroring the existing `roles → projects`
 * edge in [DefaultAgentRoleRepository]) so the injection graph stays acyclic.
 *
 * @property presetApi The API client used for all preset requests.
 * @property agentRoleRepository The role repository whose stream is refreshed after a mutation, so
 *            role rows never render stale derived model/settings values.
 */
class DefaultModelPresetRepository(
    private val presetApi: ModelPresetApi,
    private val agentRoleRepository: AgentRoleRepository
) : ModelPresetRepository {

    companion object {
        private val logger = kmpLogger<DefaultModelPresetRepository>()

        /**
         * Sorts presets by their user-facing name ascending (U-24).
         *
         * @param presets The presets to order.
         * @return A new list ordered by [ModelPresetDto.name].
         */
        private fun sortByName(presets: List<ModelPresetDto>): List<ModelPresetDto> =
            presets.sortedBy { it.name }
    }

    private val _presets = MutableStateFlow<DataState<RepositoryError, List<ModelPresetDto>>>(DataState.Idle)
    override val presets: StateFlow<DataState<RepositoryError, List<ModelPresetDto>>> = _presets.asStateFlow()

    override suspend fun loadPresets(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations (mirrors every other repository).
        if (_presets.value.isLoading) return Unit.right()

        _presets.update { DataState.Loading }

        return presetApi.getAllPresets().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load model presets")
                logger.warn("Failed to load model presets: ${repoError.message}")
                _presets.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { presetList ->
                // The load is the one write that happens while the state is Loading, so it sets the
                // sorted success state directly instead of going through [updatePresetsState].
                val sorted = sortByName(presetList)
                _presets.update { DataState.Success(sorted) }
                logger.debug("Successfully loaded ${presetList.size} model presets")
                Unit.right()
            }
        )
    }

    override suspend fun loadPresetDetails(presetId: Long): Either<RepositoryError, ModelPresetDto> {
        logger.info("Loading details for model preset ID: $presetId")
        return presetApi.getPresetById(presetId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load details for model preset ID: $presetId")
                logger.warn("Failed to load details for model preset ID: $presetId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { preset ->
                logger.info("Successfully loaded details for model preset ID: $presetId")
                updatePresetsState { list ->
                    if (list.any { it.id == preset.id }) {
                        list.map { if (it.id == preset.id) preset else it }
                    } else {
                        list + preset
                    }
                }
                preset.right()
            }
        )
    }

    override suspend fun createPreset(request: CreateModelPresetRequest): Either<RepositoryError, ModelPresetDto> {
        logger.info("Creating new model preset: ${request.name}")

        return presetApi.createPreset(request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to create model preset '${request.name}'")
                logger.warn("Failed to create model preset '${request.name}': ${repoError.message}")
                repoError.left()
            },
            ifRight = { newPreset ->
                logger.info("Successfully created model preset: ${newPreset.name} with ID: ${newPreset.id}")
                updatePresetsState { list -> list + newPreset }
                // The new preset could already be referenced by an unrefreshed role row, so the
                // derived role data is reloaded (one-directional presets → roles edge).
                agentRoleRepository.loadRoles()
                newPreset.right()
            }
        )
    }

    override suspend fun updatePreset(
        presetId: Long,
        request: UpdateModelPresetRequest
    ): Either<RepositoryError, ModelPresetDto> {
        logger.info("Updating model preset ID: $presetId")

        return presetApi.updatePreset(presetId, request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update model preset ID: $presetId")
                logger.warn("Failed to update model preset ID: $presetId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedPreset ->
                logger.info("Successfully updated model preset ID: $presetId")
                updatePresetsState { list ->
                    list.map { if (it.id == updatedPreset.id) updatedPreset else it }
                }
                // Re-pointing a preset changes the derived model/settings of every bound role.
                agentRoleRepository.loadRoles()
                updatedPreset.right()
            }
        )
    }

    override suspend fun deletePreset(presetId: Long): Either<RepositoryError, Unit> {
        logger.info("Deleting model preset ID: $presetId")

        return presetApi.deletePreset(presetId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to delete model preset ID: $presetId")
                logger.warn("Failed to delete model preset ID: $presetId: ${repoError.message}")
                repoError.left()
            },
            ifRight = {
                logger.info("Successfully deleted model preset ID: $presetId")
                updatePresetsState { list -> list.filterNot { it.id == presetId } }
                // Bound roles survive with a nulled preset reference (and nulled derived ids), so the
                // role stream must be refreshed to stop rendering the deleted configuration.
                agentRoleRepository.loadRoles()
                Unit.right()
            }
        )
    }

    /**
     * Applies [transform] to the cached list, keeping the name-ascending invariant (U-24).
     *
     * A mutation is only applied when the cache already holds data (success or idle); a `Loading`
     * state is left alone so [loadPresets] stays the single owner of the success transition, and an
     * error state is left untouched so a failed stream is not silently turned into a partial list.
     *
     * @param transform Rewrites the current list (append/replace/filter) without ordering concerns.
     */
    private fun updatePresetsState(transform: (List<ModelPresetDto>) -> List<ModelPresetDto>) {
        _presets.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(sortByName(transform(currentState.data)))
                is DataState.Idle -> DataState.Success(sortByName(transform(emptyList())))
                else -> currentState
            }
        }
    }
}
