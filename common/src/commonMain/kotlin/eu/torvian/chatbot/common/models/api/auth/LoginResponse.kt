package eu.torvian.chatbot.common.models.api.auth

import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Response body for successful user login.
 *
 * @property user The authenticated user information
 * @property accessToken JWT access token for API authentication
 * @property refreshToken The JWT refresh token for obtaining new access tokens
 * @property expiresAt When the access token expires
 * @property permissions The list of permissions granted to the user (aggregated from all their roles)
 * @property isRestricted Whether the session is restricted (created from an unacknowledged IP)
 */
@Serializable
data class LoginResponse(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val permissions: List<Permission> = emptyList(),
    val isRestricted: Boolean = false
)
