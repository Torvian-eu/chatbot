package eu.torvian.chatbot.common.models.auth

import kotlinx.serialization.Serializable

/**
 * Request model for refreshing access tokens.
 *
 * @property refreshToken The refresh token to use for obtaining new access tokens
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)
