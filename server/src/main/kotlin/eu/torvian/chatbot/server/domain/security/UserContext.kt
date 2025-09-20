package eu.torvian.chatbot.server.domain.security

import eu.torvian.chatbot.server.data.entities.UserEntity
import kotlinx.datetime.Instant

/**
 * Represents the authenticated user context extracted from a validated JWT token.
 * 
 * This class contains all the information needed to identify and authorize
 * an authenticated user for API operations.
 * 
 * @property user The authenticated user entity
 * @property sessionId The ID of the user's current session
 * @property tokenIssuedAt When the current token was issued
 * @property tokenExpiresAt When the current token expires
 */
data class UserContext(
    val user: UserEntity,
    val sessionId: Long,
    val tokenIssuedAt: Instant,
    val tokenExpiresAt: Instant
)
