package eu.torvian.chatbot.common.models.api.auth

import kotlinx.serialization.Serializable

/**
 * Request body for user login.
 *
 * @property username The username to authenticate
 * @property password The plaintext password to verify
 * @property deviceId Client-side UUID that persists across logins for device-based trust
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String
)
