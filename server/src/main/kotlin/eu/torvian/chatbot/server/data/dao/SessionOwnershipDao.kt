package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between chat sessions and users.
 *
 * The DAO operates on the table `chat_session_owners` (session_id, user_id).
 * Implementation notes:
 *  - getAllSessionsForUser returns an empty list when the user has no sessions (or the user id does not exist).
 *  - getOwner returns ResourceNotFound when the session row or owner row does not exist.
 *  - setOwner attempts to insert a (session_id, user_id) row. Constraint violations are mapped to SetOwnerError.
 */
interface SessionOwnershipDao {
    /**
     * Retrieves all chat session summaries owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [ChatSessionSummary] owned by the user; empty list if none.
     */
    suspend fun getAllSessionsForUser(userId: Long): List<ChatSessionSummary>

    /**
     * Returns the user id owning the given session.
     *
     * @param sessionId ID of the session.
     * @return Either [GetOwnerError.ResourceNotFound] if no such session/owner exists, or the owner's user id.
     */
    suspend fun getOwner(sessionId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the session and a user.
     *
     * This performs an insert into `chat_session_owners`. Implementations should map DB constraint
     * violations into [SetOwnerError].
     *
     * @param sessionId ID of the session to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(sessionId: Long, userId: Long): Either<SetOwnerError, Unit>
}
