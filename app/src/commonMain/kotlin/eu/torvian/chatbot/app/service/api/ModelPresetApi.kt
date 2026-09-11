package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto

/**
 * API client interface for user-owned model-preset management.
 *
 * This interface defines CRUD against the `/api/v1/model-presets` endpoints. Presets are owned
 * per-user (the creating user becomes the sole owner) and expose only `READ`-accessible models and
 * settings profiles as references; every endpoint is reachable with a `USER_JWT`. All methods return
 * [Either<ApiResourceError, T>] so callers handle failures explicitly.
 *
 * The returned list order is the server's (id ascending). Callers that need the user-facing
 * name-ascending order (U-24) rely on the repository layer, which re-sorts the cache.
 */
interface ModelPresetApi {

    /**
     * Retrieves all model presets accessible to the current user.
     *
     * Corresponds to `GET /api/v1/model-presets`, which responds with a bare JSON array.
     *
     * @return [Either.Right] containing the list of [ModelPresetDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllPresets(): Either<ApiResourceError, List<ModelPresetDto>>

    /**
     * Retrieves a single model preset owned by the current user.
     *
     * Corresponds to `GET /api/v1/model-presets/{presetId}`. A missing id and a preset owned by
     * somebody else both surface as a 404.
     *
     * @param presetId The unique identifier of the preset to fetch.
     * @return [Either.Right] containing the requested [ModelPresetDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getPresetById(presetId: Long): Either<ApiResourceError, ModelPresetDto>

    /**
     * Creates a new model preset; the requesting user becomes its sole owner.
     *
     * Corresponds to `POST /api/v1/model-presets` (201 on success). Both references are optional, so
     * a name-only preset can be created and completed later.
     *
     * @param request The full configuration of the preset to create.
     * @return [Either.Right] containing the newly created [ModelPresetDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun createPreset(request: CreateModelPresetRequest): Either<ApiResourceError, ModelPresetDto>

    /**
     * Replaces the configuration (name, display name, description and both references) of an
     * existing preset.
     *
     * Corresponds to `PUT /api/v1/model-presets/{presetId}`. The update is a full replacement, not a
     * patch: omitted fields revert to their defaults and a null reference clears it.
     *
     * @param presetId The unique identifier of the preset to update.
     * @param request The replacement configuration.
     * @return [Either.Right] containing the updated [ModelPresetDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updatePreset(presetId: Long, request: UpdateModelPresetRequest): Either<ApiResourceError, ModelPresetDto>

    /**
     * Deletes a preset. Roles bound to it are not deleted: their preset reference is nulled
     * server-side (`ON DELETE SET NULL`) and they become non-sendable.
     *
     * Corresponds to `DELETE /api/v1/model-presets/{presetId}` (204 with an empty body).
     *
     * @param presetId The unique identifier of the preset to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deletePreset(presetId: Long): Either<ApiResourceError, Unit>
}
