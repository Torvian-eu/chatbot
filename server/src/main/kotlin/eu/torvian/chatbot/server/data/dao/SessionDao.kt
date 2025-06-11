package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.error.SessionError

/**
 * Data Access Object for ChatSession entities.
 */
interface SessionDao {
    /**
     * Retrieves a list of all chat session summaries, ordered by update time.
     * Includes group name via a join if assigned to a group.
     * @return A list of [ChatSessionSummary] objects.
     */
    suspend fun getAllSessions(): List<ChatSessionSummary>

    /**
     * Retrieves the full details of a specific chat session, including all its messages.
     * Messages are loaded separately and attached.
     * @param id The ID of the session to retrieve.
     * @return Either a [SessionError.SessionNotFound] or the [ChatSession] object with messages.
     */
    suspend fun getSessionById(id: Long): Either<SessionError.SessionNotFound, ChatSession>

    /**
     * Inserts a new chat session record into the database.
     * @param name The name for the new session.
     * @param groupId Optional ID of the group to assign the session to.
     * @param currentModelId Optional ID of the model to set as current for the session.
     * @param currentSettingsId Optional ID of the settings to set as current for the session.
     * @return Either a [SessionError.ForeignKeyViolation] or the newly created [ChatSession] object.
     */
    suspend fun insertSession(
        name: String,
        groupId: Long? = null,
        currentModelId: Long? = null,
        currentSettingsId: Long? = null
    ): Either<SessionError.ForeignKeyViolation, ChatSession>

    /**
     * Updates the name of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param name The new name for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionName(id: Long, name: String): Either<SessionError.SessionNotFound, Unit>

    /**
     * Updates the group ID of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param groupId The new optional group ID for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionGroupId(id: Long, groupId: Long?): Either<SessionError, Unit>

    /**
     * Updates the current model ID of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param modelId The new optional model ID for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionCurrentModelId(id: Long, modelId: Long?): Either<SessionError, Unit>

    /**
     * Updates the current settings ID of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param settingsId The new optional settings ID for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionCurrentSettingsId(id: Long, settingsId: Long?): Either<SessionError, Unit>

    /**
     * Updates the current leaf message ID of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param messageId The new optional leaf message ID for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionLeafMessageId(id: Long, messageId: Long?): Either<SessionError, Unit>

    /**
     * Deletes a chat session by ID.
     * Relies on the database's foreign key CASCADE constraint to delete associated messages.
     * @param id The ID of the session to delete.
     * @return Either a [SessionError.SessionNotFound] or Unit if successful.
     */
    suspend fun deleteSession(id: Long): Either<SessionError.SessionNotFound, Unit>

    /**
     * Updates the `groupId` for all sessions currently assigned to a specific group,
     * setting their `groupId` to null (ungrouping them).
     * Used by the [GroupService] when a group is deleted.
     * @param groupId The ID of the group whose sessions should be ungrouped.
     */
    suspend fun ungroupSessions(groupId: Long)
}
