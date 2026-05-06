package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.AuthApi
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.models.api.auth.LoginRequest
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.common.models.api.auth.RefreshTokenRequest
import eu.torvian.chatbot.common.models.api.auth.RegisterRequest
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.user.User
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of the AuthApi interface.
 *
 * This implementation handles authentication operations with the backend server.
 * It uses two different HttpClient instances:
 * - unauthenticatedClient: For login, register, and refresh operations (to prevent infinite loops)
 * - authenticatedClient: For logout operations (requires valid authentication)
 *
 * @property unauthenticatedClient HttpClient without authentication for auth operations
 * @property authenticatedClient HttpClient with authentication for logout operations
 */
class KtorAuthApiClient(
    private val unauthenticatedClient: HttpClient,
    private val authenticatedClient: HttpClient
) : BaseApiResourceClient(unauthenticatedClient), AuthApi {

    override suspend fun refreshToken(refreshToken: String): Either<ApiResourceError, LoginResponse> {
        return safeApiCall {
            authenticatedClient.authProvider<BearerAuthProvider>()?.clearToken()
            unauthenticatedClient.post(AuthResource.Refresh()) {
                setBody(RefreshTokenRequest(refreshToken = refreshToken))
            }.body<LoginResponse>()
        }
    }

    override suspend fun login(username: String, password: String): Either<ApiResourceError, LoginResponse> {
        return safeApiCall {
            authenticatedClient.authProvider<BearerAuthProvider>()?.clearToken()
            unauthenticatedClient.post(AuthResource.Login()) {
                setBody(LoginRequest(username = username, password = password))
            }.body<LoginResponse>()
        }
    }

    override suspend fun register(username: String, password: String, email: String?): Either<ApiResourceError, User> {
        return safeApiCall {
            unauthenticatedClient.post(AuthResource.Register()) {
                setBody(RegisterRequest(username = username, password = password, email = email))
            }.body<User>()
        }
    }

    override suspend fun getActiveSessions(): Either<ApiResourceError, List<UserSessionInfo>> {
        return safeApiCall {
            authenticatedClient.get(AuthResource.Sessions()).body<List<UserSessionInfo>>()
        }
    }

    override suspend fun logout(sessionId: Long?): Either<ApiResourceError, Unit> {
        return safeApiCall {
            val resource = if (sessionId == null) {
                AuthResource.Logout()
            } else {
                AuthResource.Logout(sessionId = sessionId)
            }

            authenticatedClient.post(resource).body<Unit>()

            // Only clear the cached bearer token when the current session was revoked; revoking a
            // different session must not log the user out locally.
            if (sessionId == null) {
                authenticatedClient.authProvider<BearerAuthProvider>()?.clearToken()
            }
        }
    }

    override suspend fun logoutAll(): Either<ApiResourceError, Unit> {
        return safeApiCall {
            try {
                authenticatedClient.post(AuthResource.LogoutAll()).body<Unit>()
            } finally {
                // The in-memory bearer token is cleared even if the server call fails so the client
                // does not keep using credentials that have just been invalidated.
                authenticatedClient.authProvider<BearerAuthProvider>()?.clearToken()
            }
        }
    }

    override suspend fun getCurrentUser(): Either<ApiResourceError, User> {
        return safeApiCall {
            authenticatedClient.get(AuthResource.Me()).body<User>()
        }
    }

    override suspend fun clearToken() {
        authenticatedClient.authProvider<BearerAuthProvider>()?.clearToken()
    }

    override suspend fun getSecurityAlerts(): Either<ApiResourceError, List<UserSecurityAlert>> {
        return safeApiCall {
            authenticatedClient.get(AuthResource.SecurityAlerts()).body<List<UserSecurityAlert>>()
        }
    }

    override suspend fun acknowledgeSecurityAlerts(): Either<ApiResourceError, Unit> {
        return safeApiCall {
            authenticatedClient.post(AuthResource.AcknowledgeIps()).body<Unit>()
        }
    }
}
