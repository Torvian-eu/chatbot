package eu.torvian.chatbot.server.domain.security

import eu.torvian.chatbot.common.models.User
import kotlinx.datetime.Instant

/**
 * Represents the result of a successful user login operation.
 * 
 * This class contains all the information returned to the client after
 * successful authentication, including the user details and authentication tokens.
 * 
 * @property user The authenticated user
 * @property accessToken The JWT access token for API authentication
 * @property refreshToken The JWT refresh token for obtaining new access tokens
 * @property expiresAt When the access token expires
 */
data class LoginResult(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant
)
