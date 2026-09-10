package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between model presets and users.
 *
 * Operates on `model_preset_owners` (preset_id PK, user_id), mirroring the `project_owners` /
 * `agent_role_owners` family: a preset has exactly one owner (preset_id is the primary key).
 */
interface ModelPresetOwnershipDao {

    /**
     * Returns the user id owning the given model preset.
     *
     * @param presetId ID of the preset.
     * @return Either [GetOwnerError.ResourceNotFound] if no such preset/owner exists, or the owner's
     *         user id.
     */
    suspend fun getOwner(presetId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the model preset and a user.
     *
     * @param presetId ID of the preset to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(presetId: Long, userId: Long): Either<SetOwnerError, Unit>
}
