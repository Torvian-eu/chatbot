package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
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
     *
     * @param userId The ID of the user who will own the session.
     * @param name Required non-blank session name.
     * @return Either a [CreateSessionError] if the request is invalid or creation fails,
     *         or the newly created [ChatSession].
     */
    suspend fun createSession(userId: Long, name: String): Either<CreateSessionError, ChatSession>

    /**
     * Retrieves full details for a specific chat session, including all messages.
     * Verifies that the user owns the session before returning details.
     * @param id The ID of the session to retrieve.
     * @return Either a [GetSessionDetailsError] if the session doesn't exist or access is denied,
     *         or the [ChatSession] object with messages.
     */
    suspend fun getSessionDetails(id: Long): Either<GetSessionDetailsError, ChatSession>

    /**
     * Updates the name of an existing chat session.
     * Verifies that the user owns the session before updating.
     * @param id The ID of the session to update.
     * @param name The new name for the session.
     * @return Either an [UpdateSessionNameError] or Unit if successful.
     */
    suspend fun updateSessionName(id: Long, name: String): Either<UpdateSessionNameError, Unit>

    /**
     * Updates the group ID of an existing chat session.
     * Verifies that the user owns the session before updating.
     * @param id The ID of the session to update.
     * @param groupId The new optional group ID for the session.
     * @return Either an [UpdateSessionGroupIdError] if the session or group is not found,
     *         or access is denied, or Unit if successful.
     */
    suspend fun updateSessionGroupId(id: Long, groupId: Long?): Either<UpdateSessionGroupIdError, Unit>

    /**
     * Updates the agent role selected for an existing chat session.
     * Model/settings/tools are resolved from the role at turn time; selecting or deselecting a role
     * only updates the session's `agent_role_id`.
     * Verifies that the user owns the session before updating.
     *
     * @param id The ID of the session to update.
     * @param agentRoleId The new optional agent role ID for the session. Null deselects the role.
     * @return Either an [UpdateSessionAgentRoleIdError] if the session is not found, access is denied,
     *         or the referenced role is invalid, or Unit if successful.
     */
    suspend fun updateSessionAgentRoleId(
        id: Long,
        agentRoleId: Long?
    ): Either<UpdateSessionAgentRoleIdError, Unit>

    /**
     * Updates the current leaf message ID of an existing chat session.
     * Verifies that the user owns the session before updating.
     * @param id The ID of the session to update.
     * @param messageId The new optional leaf message ID for the session.
     * @return Either an [UpdateSessionLeafMessageIdError] if the session or message is not found,
     *         or access is denied, or Unit if successful.
     */
    suspend fun updateSessionLeafMessageId(
        id: Long,
        messageId: Long?
    ): Either<UpdateSessionLeafMessageIdError, Unit>

    /**
     * Deletes a chat session and all its messages.
     * Verifies that the user owns the session before deleting.
     * @param id The ID of the session to delete.
     * @return Either a [DeleteSessionError] if the session doesn't exist or access is denied, or Unit if successful.
     */
    suspend fun deleteSession(id: Long): Either<DeleteSessionError, Unit>

    /**
     * Clones an existing chat session with all its messages, tool calls, and configuration.
     * @param id The ID of the session to clone.
     * @param name The name for the cloned session.
     * @return Either a [CloneSessionError] if the session doesn't exist, name is invalid, or access is denied,
     *         or the newly created [ChatSession] with all cloned data.
     */
    suspend fun cloneSession(id: Long, name: String): Either<CloneSessionError, ChatSession>
}
