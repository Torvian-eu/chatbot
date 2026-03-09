package eu.torvian.chatbot.server.domain.security

import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import kotlin.time.Instant

/**
 * Represents the result of a successful user login operation.
 * 
 * This class contains all the information returned to the client after
 * successful authentication, including the user details, authentication tokens,
 * and user permissions.
 *
 * @property user The authenticated user
 * @property accessToken The JWT access token for API authentication
 * @property refreshToken The JWT refresh token for obtaining new access tokens
 * @property expiresAt When the access token expires
 * @property permissions The list of permissions granted to the user (aggregated from all their roles)
 */
data class LoginResult(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val permissions: List<Permission>
)
