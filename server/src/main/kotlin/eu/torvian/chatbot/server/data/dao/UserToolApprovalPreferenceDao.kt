package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import eu.torvian.chatbot.server.data.dao.error.SetPreferenceError
import eu.torvian.chatbot.server.data.dao.error.UserToolApprovalPreferenceError

/**
 * Data Access Object for UserToolApprovalPreference entities.
 *
 * Provides CRUD operations for user-specific tool call approval preferences,
 * enabling automatic approval or denial of tool calls without manual intervention.
 */
interface UserToolApprovalPreferenceDao {
    /**
     * Retrieves the approval preference for a specific user and tool definition.
     *
     * @param userId The ID of the user
     * @param toolDefinitionId The ID of the tool definition
     * @return Either [UserToolApprovalPreferenceError.NotFound] if not found, or the [UserToolApprovalPreference]
     */
    suspend fun getPreference(
        userId: Long,
        toolDefinitionId: Long
    ): Either<UserToolApprovalPreferenceError.NotFound, UserToolApprovalPreference>

    /**
     * Retrieves all approval preferences for a specific user.
     *
     * @param userId The ID of the user
     * @return List of all preferences for the user (empty list if none exist)
     */
    suspend fun getAllPreferencesForUser(userId: Long): List<UserToolApprovalPreference>

    /**
     * Creates or updates an approval preference for a user and tool definition.
     * If a preference already exists for this user-tool pair, it will be updated.
     *
     * @param userId The ID of the user
     * @param toolDefinitionId The ID of the tool definition
     * @param autoApprove Whether to auto-approve (true) or auto-deny (false)
     * @param conditions Optional JSON string for conditional approval logic (reserved for future use)
     * @param denialReason Optional reason text for auto-denials (reserved for future use)
     * @return Either [SetPreferenceError] (for FK violations) or the created/updated [UserToolApprovalPreference]
     */
    suspend fun setPreference(
        userId: Long,
        toolDefinitionId: Long,
        autoApprove: Boolean,
        conditions: String? = null,
        denialReason: String? = null
    ): Either<SetPreferenceError, UserToolApprovalPreference>

    /**
     * Deletes an approval preference for a user and tool definition.
     *
     * @param userId The ID of the user
     * @param toolDefinitionId The ID of the tool definition
     * @return Either [UserToolApprovalPreferenceError.NotFound] if not found, or Unit on success
     */
    suspend fun deletePreference(
        userId: Long,
        toolDefinitionId: Long
    ): Either<UserToolApprovalPreferenceError.NotFound, Unit>

    /**
     * Deletes all approval preferences for a specific user.
     *
     * @param userId The ID of the user
     * @return The number of preferences deleted
     */
    suspend fun deleteAllPreferencesForUser(userId: Long): Int
}

