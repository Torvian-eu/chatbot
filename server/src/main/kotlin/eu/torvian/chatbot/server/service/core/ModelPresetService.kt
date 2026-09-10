package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.service.core.error.preset.CreateModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.DeleteModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.ModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.UpdateModelPresetError

/**
 * Service interface for managing user-owned model presets.
 *
 * Every operation is scoped to the requesting user, and the service verifies that the user owns the
 * preset before returning or mutating it (a foreign or nonexistent preset collapses to
 * [ModelPresetError.NotFound], so no existence leak exists). Presets bundle one referenced model and
 * one referenced settings profile and are the **sole** source of truth for the LLM configuration of
 * every agent role bound to them.
 *
 * Preset references must exist **and** be `READ`-accessible to the requesting user. The preset layer
 * deliberately imposes no [eu.torvian.chatbot.common.models.llm.LLMModelType] restriction — chat
 * capability is enforced where a preset drives an agent-role turn — but when both references are
 * present they must agree (`preset.modelId == referencedSettings.modelId`), because the bundle
 * describes a single model together with a profile of it.
 *
 * This service is intentionally independent of [AgentRoleService] (no service↔service coupling): the
 * role service validates an attached preset through the preset DAO instead. Role service callers must
 * therefore pass the preset id and rely on the role service's own attach validation.
 */
interface ModelPresetService {

    /**
     * Retrieves all model presets owned by the user, ordered by `id` ascending.
     *
     * @param userId The ID of the user whose presets to retrieve.
     * @return List of [ModelPresetDto] owned by the user; empty list if the user owns no presets.
     */
    suspend fun getAllPresetsForUser(userId: Long): List<ModelPresetDto>

    /**
     * Retrieves a single model preset by ID, verifying ownership.
     *
     * @param userId The ID of the requesting user.
     * @param presetId The ID of the preset to retrieve.
     * @return Either [ModelPresetError.NotFound] if the preset does not exist or is not owned by the
     *         user, or the [ModelPresetDto].
     */
    suspend fun getPresetById(userId: Long, presetId: Long): Either<ModelPresetError.NotFound, ModelPresetDto>

    /**
     * Creates a new model preset owned by the user.
     *
     * Validates the name (trimmed, non-blank, at most
     * [eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH] characters) and enforces
     * per-owner name uniqueness, then validates the two optional references: each must exist and be
     * `READ`-accessible to [userId], and when both are present they must describe the same model. The
     * ownership link is inserted atomically with the row, so nothing is persisted when validation
     * fails.
     *
     * @param userId The ID of the user who will own the preset.
     * @param request The creation payload.
     * @return Either a [CreateModelPresetError] or the newly created [ModelPresetDto].
     */
    suspend fun createPreset(
        userId: Long,
        request: CreateModelPresetRequest
    ): Either<CreateModelPresetError, ModelPresetDto>

    /**
     * Updates an existing model preset owned by the user.
     *
     * A full replacement of `name`, `displayName`, `description` and both references (a null reference
     * clears it), applying the same name and reference validation as [createPreset]. The
     * rename-uniqueness check excludes the preset being updated, so keeping the current name is always
     * allowed. `createdAt` is preserved and `updatedAt` advances.
     *
     * @param userId The ID of the requesting user.
     * @param presetId The ID of the preset to update.
     * @param request The update payload.
     * @return Either an [UpdateModelPresetError] or the updated [ModelPresetDto].
     */
    suspend fun updatePreset(
        userId: Long,
        presetId: Long,
        request: UpdateModelPresetRequest
    ): Either<UpdateModelPresetError, ModelPresetDto>

    /**
     * Deletes a model preset owned by the user.
     *
     * The `model_preset_owners` row cascades away and every bound agent role's `model_preset_id` is
     * nulled (`ON DELETE SET NULL`): the roles survive, become preset-less and are therefore
     * non-sendable. Nothing else about a role is touched — there is no "preset in use" rejection.
     *
     * @param userId The ID of the requesting user.
     * @param presetId The ID of the preset to delete.
     * @return Either a [DeleteModelPresetError] or Unit on success.
     */
    suspend fun deletePreset(userId: Long, presetId: Long): Either<DeleteModelPresetError, Unit>
}
