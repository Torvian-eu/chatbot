package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between model settings and users.
 *
 * The DAO operates on the table `model_settings_owners` (settings_id, user_id).
 * Implementation notes:
 *  - getOwner returns ResourceNotFound when the settings row or owner row does not exist.
 *  - setOwner attempts to insert a (settings_id, user_id) row. Constraint violations are mapped to SetOwnerError.
 */
interface SettingsOwnershipDao {
    /**
     * Returns the user id owning the given settings profile.
     *
     * @param settingsId ID of the settings profile.
     * @return Either [GetOwnerError.ResourceNotFound] if no such settings/owner exists, or the owner's user id.
     */
    suspend fun getOwner(settingsId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the settings profile and a user.
     *
     * This performs an insert into `model_settings_owners`. Implementations should map DB constraint
     * violations into [SetOwnerError].
     *
     * @param settingsId ID of the settings profile to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(settingsId: Long, userId: Long): Either<SetOwnerError, Unit>
}

