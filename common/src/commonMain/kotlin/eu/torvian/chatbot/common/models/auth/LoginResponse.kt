package eu.torvian.chatbot.common.models.auth

import eu.torvian.chatbot.common.models.User
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Response body for successful user login.
 *
 * @property user The authenticated user information
 * @property accessToken JWT access token for API authentication
 * @property refreshToken The JWT refresh token for obtaining new access tokens
 * @property expiresAt When the access token expires
 */
@Serializable
data class LoginResponse(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant
)
