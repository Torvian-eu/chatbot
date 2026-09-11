package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for user-owned model presets with reactive data streams.
 *
 * This repository is the single source of truth for the current user's model presets. It exposes
 * [presets] as a reactive [StateFlow] and keeps that stream in sync after every CRUD operation,
 * following the same pattern as [ProjectRepository].
 *
 * Preset mutations also change *derived* data of every agent role bound to the preset (the server
 * resolves each role's model/settings from its preset), so implementations refresh the role stream
 * after a successful create/update/delete. That refresh is deliberately one-directional
 * (presets → roles): a role mutation can never change a preset row, so no dependency cycle exists.
 */
interface ModelPresetRepository {

    /**
     * Reactive stream of all model presets owned by the current user.
     *
     * The list is kept in **name-ascending** order (U-24) so the settings tab, the role form's preset
     * dropdown and every lookup share one deterministic user-facing order, independent of the
     * id-ascending order the server returns.
     *
     * @return StateFlow containing the current state of the preset list wrapped in [DataState].
     */
    val presets: StateFlow<DataState<RepositoryError, List<ModelPresetDto>>>

    /**
     * Loads all model presets from the server and updates [presets].
     *
     * If a load is already in progress, the call returns immediately without starting a duplicate
     * request.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun loadPresets(): Either<RepositoryError, Unit>

    /**
     * Loads a single preset and upserts it into [presets].
     *
     * Used to refresh one preset after an edit made elsewhere (e.g. by another client or a server
     * built-in tool) without reloading the whole list.
     *
     * @param presetId The unique identifier of the preset to load.
     * @return [Either.Right] with the loaded [ModelPresetDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun loadPresetDetails(presetId: Long): Either<RepositoryError, ModelPresetDto>

    /**
     * Creates a new preset from a full configuration request.
     *
     * Validation stays server-side; this repository only maps API errors to [RepositoryError]. After
     * a successful creation the new preset is inserted into [presets] and the role stream is
     * refreshed (the preset may already be referenced by an unrefreshed role row).
     *
     * @param request The full configuration of the preset to create.
     * @return [Either.Right] with the created [ModelPresetDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun createPreset(request: CreateModelPresetRequest): Either<RepositoryError, ModelPresetDto>

    /**
     * Replaces the configuration of an existing preset.
     *
     * After a successful update the returned preset replaces the previous entry in [presets] and the
     * role stream is refreshed, because re-pointing a preset changes the derived model/settings of
     * every role bound to it.
     *
     * @param presetId The unique identifier of the preset to update.
     * @param request The replacement configuration.
     * @return [Either.Right] with the updated [ModelPresetDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun updatePreset(presetId: Long, request: UpdateModelPresetRequest): Either<RepositoryError, ModelPresetDto>

    /**
     * Deletes a preset and removes it from [presets].
     *
     * Roles bound to the preset survive (the server nulls their reference via `ON DELETE SET NULL`)
     * but lose their model/settings and become non-sendable, so the role stream is refreshed after a
     * successful deletion.
     *
     * @param presetId The unique identifier of the preset to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun deletePreset(presetId: Long): Either<RepositoryError, Unit>
}
