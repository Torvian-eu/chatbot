package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserSessionEntity

/**
 * Data Access Object for managing user authentication sessions.
 * 
 * This DAO provides operations for creating, retrieving, and managing user sessions
 * used for authentication and authorization.
 */
interface UserSessionDao {
    /**
     * Retrieves a user session by its unique ID.
     * 
     * @param id The unique identifier of the session.
     * @return Either [UserSessionError.SessionNotFound] if no session exists with the given ID,
     *         or the [UserSessionEntity] if found.
     */
    suspend fun getSessionById(id: Long): Either<UserSessionError.SessionNotFound, UserSessionEntity>

    /**
     * Retrieves all active sessions for a specific user.
     * 
     * @param userId The ID of the user whose sessions to retrieve.
     * @return List of [UserSessionEntity] objects for the user; empty list if no sessions exist.
     */
    suspend fun getSessionsByUserId(userId: Long): List<UserSessionEntity>

    /**
     * Creates a new user session.
     * 
     * @param userId The ID of the user for whom to create the session.
     * @param expiresAt Timestamp when the session should expire (epoch milliseconds).
     * @param ipAddress Optional IP address of the client creating the session.
     * @return Either [UserSessionError.ForeignKeyViolation] if the user doesn't exist,
     *         or the newly created [UserSessionEntity] on success.
     */
    suspend fun insertSession(
        userId: Long,
        expiresAt: Long,
        ipAddress: String?
    ): Either<UserSessionError.ForeignKeyViolation, UserSessionEntity>

    /**
     * Updates the last accessed timestamp for a session.
     * 
     * @param id The unique identifier of the session.
     * @param lastAccessed The timestamp when the session was last accessed (epoch milliseconds).
     * @return Either [UserSessionError.SessionNotFound] if the session doesn't exist, or Unit on success.
     */
    suspend fun updateLastAccessed(id: Long, lastAccessed: Long): Either<UserSessionError.SessionNotFound, Unit>

    /**
     * Deletes a user session by ID.
     * 
     * @param id The unique identifier of the session to delete.
     * @return Either [UserSessionError.SessionNotFound] if the session doesn't exist, or Unit on success.
     */
    suspend fun deleteSession(id: Long): Either<UserSessionError.SessionNotFound, Unit>

    /**
     * Deletes all sessions for a specific user.
     * 
     * @param userId The ID of the user whose sessions to delete.
     * @return The number of sessions deleted.
     */
    suspend fun deleteSessionsByUserId(userId: Long): Int

    /**
     * Deletes all expired sessions.
     * 
     * @param currentTime The current timestamp (epoch milliseconds) to compare against.
     * @return The number of expired sessions deleted.
     */
    suspend fun deleteExpiredSessions(currentTime: Long): Int
}
