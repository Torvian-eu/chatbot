package eu.torvian.chatbot.common.models.auth

import kotlinx.serialization.Serializable

/**
 * Request body for user login.
 *
 * @property username The username to authenticate
 * @property password The plaintext password to verify
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)
