package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.preset.ModelPresetError
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity

/**
 * Data Access Object for model-preset entities.
 *
 * Preset-name uniqueness is **per owner user**, not global: different users may reuse the same name.
 * The DB cannot express that constraint (ownership lives in the separate `model_preset_owners` table),
 * so the per-user uniqueness checks live at the service layer; the DAO exposes
 * [presetNameExistsForUser] and owner-scoped reads to support them. The owner is managed through
 * [ModelPresetOwnershipDao].
 */
interface ModelPresetDao {

    /**
     * Retrieves all model presets owned by the given user, joined through the ownership table.
     *
     * Ordered by `id` ascending so the REST and tool listings are deterministic.
     *
     * @param userId ID of the owner user.
     * @return List of [ModelPresetEntity] owned by the user; empty list if the user owns no presets.
     */
    suspend fun getAllPresetsForUser(userId: Long): List<ModelPresetEntity>

    /**
     * Retrieves a model preset by its unique ID.
     *
     * The caller is responsible for ownership verification (see [getPresetsByIdsForUser]); this read
     * is a plain existence check.
     *
     * @param id The unique identifier of the preset.
     * @return Either [ModelPresetError.NotFound] if not found, or the [ModelPresetEntity].
     */
    suspend fun getPresetById(id: Long): Either<ModelPresetError.NotFound, ModelPresetEntity>

    /**
     * Loads the requested presets that are owned by [userId], preserving the order of [presetIds].
     * Missing or foreign presets are omitted so callers can use the result as an ownership check
     * (a missing/foreign id collapses to the same not-found outcome).
     *
     * This is the batch read used by the agent-role list path so resolving the derived model/settings
     * ids never becomes an N+1 query.
     *
     * @param userId User whose ownership is required.
     * @param presetIds Preset ids to resolve.
     * @return Owned preset entities in requested order.
     */
    suspend fun getPresetsByIdsForUser(userId: Long, presetIds: List<Long>): List<ModelPresetEntity>

    /**
     * Whether the user already owns a model preset with the given name.
     *
     * Used by the service layer to enforce per-owner preset-name uniqueness (the update path excludes
     * the preset being renamed by passing a different name or by comparing ids itself).
     *
     * @param userId ID of the owner user.
     * @param name The preset name to check.
     * @return `true` if the user owns a preset with that name, `false` otherwise.
     */
    suspend fun presetNameExistsForUser(userId: Long, name: String): Boolean

    /**
     * Creates a new model-preset row.
     *
     * Name uniqueness is NOT enforced here (the column is not unique); the caller is responsible for
     * checking [presetNameExistsForUser] first. `created_at` and `updated_at` are both set to the
     * current time by the DAO. Technical persistence failures propagate as exceptions. The caller must
     * insert the ownership link via [ModelPresetOwnershipDao.setOwner] (atomically, in the same
     * transaction).
     *
     * @param name Machine-readable preset name (unique per owner; checked by the caller).
     * @param displayName Optional human-friendly display name.
     * @param description Free-form description of the preset.
     * @param modelId Optional reference to the bundled LLM model.
     * @param modelSettingsId Optional reference to the bundled settings profile.
     * @return The newly created [ModelPresetEntity].
     */
    suspend fun insertPreset(
        name: String,
        displayName: String?,
        description: String,
        modelId: Long?,
        modelSettingsId: Long?
    ): ModelPresetEntity

    /**
     * Updates an existing model-preset row (a full replacement of every writable column).
     *
     * `created_at` is preserved; `updated_at` is set to the current time by the DAO.
     *
     * @param preset The [ModelPresetEntity] with updated values. The ID must match an existing preset.
     * @return Either [ModelPresetError.NotFound] if the preset does not exist, or Unit on success.
     */
    suspend fun updatePreset(preset: ModelPresetEntity): Either<ModelPresetError.NotFound, Unit>

    /**
     * Deletes a model-preset row by ID.
     *
     * The `model_preset_owners` row cascades and every bound agent role's `model_preset_id` is nulled
     * (`ON DELETE SET NULL`) on runtime connections: the roles survive and simply become preset-less
     * and therefore non-sendable.
     *
     * @param id The unique identifier of the preset to delete.
     * @return Either [ModelPresetError.NotFound] if not found, or Unit on success.
     */
    suspend fun deletePreset(id: Long): Either<ModelPresetError.NotFound, Unit>
}
