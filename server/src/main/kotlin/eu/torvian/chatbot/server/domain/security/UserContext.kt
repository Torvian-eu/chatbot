package eu.torvian.chatbot.server.domain.security

import eu.torvian.chatbot.common.models.user.User
import kotlin.time.Instant

/**
 * Represents the authenticated user context extracted from a validated JWT token.
 * 
 * This class contains all the information needed to identify and authorize
 * an authenticated user for API operations.
 * 
 * @property user The authenticated user
 * @property sessionId The ID of the user's current session
 * @property tokenIssuedAt When the current token was issued
 * @property tokenExpiresAt When the current token expires
 */
data class UserContext(
    val user: User,
    val sessionId: Long,
    val tokenIssuedAt: Instant,
    val tokenExpiresAt: Instant
)
