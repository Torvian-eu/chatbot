package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.service.core.error.session.*

/**
 * Service interface for managing chat sessions.
 * Contains core business logic related to sessions, independent of API or data access details.
 */
interface SessionService {
    /**
     * Retrieves summaries for all chat sessions owned by the specified user, including group names.
     * @param userId The ID of the user whose sessions to retrieve.
     * @return A list of [ChatSessionSummary] objects. Returns an empty list if no sessions exist.
     */
    suspend fun getAllSessionsSummaries(userId: Long): List<ChatSessionSummary>

    /**
     * Creates a new chat session owned by the specified user.
     * @param userId The ID of the user who will own the session.
     * @param name Optional name for the session. If null or blank, a default name may be generated.
     * @return Either a [CreateSessionError] if the request is invalid or creation fails,
     *         or the newly created [ChatSession].
     */
    suspend fun createSession(userId: Long, name: String?): Either<CreateSessionError, ChatSession>

    /**
     * Retrieves full details for a specific chat session, including all messages.
     * Verifies that the user owns the session before returning details.
     * @param userId The ID of the user requesting the session details.
     * @param id The ID of the session to retrieve.
     * @return Either a [GetSessionDetailsError] if the session doesn't exist or access is denied,
     *         or the [ChatSession] object with messages.
     */
    suspend fun getSessionDetails(userId: Long, id: Long): Either<GetSessionDetailsError, ChatSession>

    /**
     * Updates the name of an existing chat session.
     * Verifies that the user owns the session before updating.
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the session to update.
     * @param name The new name for the session.
     * @return Either an [UpdateSessionNameError] or Unit if successful.
     */
    suspend fun updateSessionName(userId: Long, id: Long, name: String): Either<UpdateSessionNameError, Unit>

    /**
     * Updates the group ID of an existing chat session.
     * Verifies that the user owns the session before updating.
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the session to update.
     * @param groupId The new optional group ID for the session.
     * @return Either an [UpdateSessionGroupIdError] if the session or group is not found,
     *         or access is denied, or Unit if successful.
     */
    suspend fun updateSessionGroupId(userId: Long, id: Long, groupId: Long?): Either<UpdateSessionGroupIdError, Unit>

    /**
     * Updates the current model ID of an existing chat session.
     * Automatically resets the currentSettingsId to null since settings
     * will no longer be valid for the new model.
     * Verifies that the user owns the session before updating.
     *
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the session to update.
     * @param modelId The new optional model ID for the session.
     * @return Either an [UpdateSessionCurrentModelIdError] if the session or model is not found,
     *         or access is denied, or Unit if successful.
     */
    suspend fun updateSessionCurrentModelId(
        userId: Long,
        id: Long,
        modelId: Long?
    ): Either<UpdateSessionCurrentModelIdError, Unit>

    /**
     * Updates the current settings ID of an existing chat session.
     * Verifies that the settings are valid for the session's current model
     * and are of the ChatModelSettings type.
     * Verifies that the user owns the session before updating.
     *
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the session to update.
     * @param settingsId The new optional settings ID for the session.
     * @return Either an [UpdateSessionCurrentSettingsIdError] if the session or settings are not found,
     *         or if the settings are incompatible with the current model, or if the settings are not
     *         of the ChatModelSettings type, or access is denied, or Unit if successful.
     */
    suspend fun updateSessionCurrentSettingsId(
        userId: Long,
        id: Long,
        settingsId: Long?
    ): Either<UpdateSessionCurrentSettingsIdError, Unit>

    /**
     * Updates both the current model ID and settings ID of an existing chat session atomically.
     * This ensures consistency between model and settings, and automatically clears settings
     * if they're incompatible with the new model. Also verifies that the settings are of the
     * ChatModelSettings type.
     * Verifies that the user owns the session before updating.
     *
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the session to update.
     * @param modelId The new optional model ID for the session.
     * @param settingsId The new optional settings ID for the session.
     * @return Either an [UpdateSessionCurrentModelAndSettingsIdError] if the session, model, or settings are not found or incompatible,
     *         or if the settings are not of the ChatModelSettings type, or access is denied, or Unit if successful.
     */
    suspend fun updateSessionCurrentModelAndSettingsId(
        userId: Long,
        id: Long,
        modelId: Long?,
        settingsId: Long?
    ): Either<UpdateSessionCurrentModelAndSettingsIdError, Unit>

    /**
     * Updates the current leaf message ID of an existing chat session.
     * Verifies that the user owns the session before updating.
     * @param userId The ID of the user requesting the update.
     * @param id The ID of the session to update.
     * @param messageId The new optional leaf message ID for the session.
     * @return Either an [UpdateSessionLeafMessageIdError] if the session or message is not found,
     *         or access is denied, or Unit if successful.
     */
    suspend fun updateSessionLeafMessageId(
        userId: Long,
        id: Long,
        messageId: Long?
    ): Either<UpdateSessionLeafMessageIdError, Unit>

    /**
     * Deletes a chat session and all its messages.
     * Verifies that the user owns the session before deleting.
     * @param userId The ID of the user requesting the deletion.
     * @param id The ID of the session to delete.
     * @return Either a [DeleteSessionError] if the session doesn't exist or access is denied, or Unit if successful.
     */
    suspend fun deleteSession(userId: Long, id: Long): Either<DeleteSessionError, Unit>

}
