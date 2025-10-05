package eu.torvian.chatbot.server.domain.security.mappers

import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.server.domain.security.LoginResult

/**
 * Extension function to map a [LoginResult] to a [LoginResponse] for API responses.
 */
fun LoginResult.toLoginResponse(): LoginResponse {
    return LoginResponse(
        user = this.user,
        accessToken = this.accessToken,
        refreshToken = this.refreshToken,
        expiresAt = this.expiresAt,
        permissions = this.permissions
    )
}
