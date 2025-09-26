package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.AuthApi
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.auth.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of the AuthApi interface.
 *
 * This implementation handles authentication operations with the backend server.
 * It uses two different HttpClient instances:
 * - unauthenticatedClient: For login, register, and refresh operations (to prevent infinite loops)
 * - authenticatedClient: For logout operation (requires valid authentication)
 *
 * @property unauthenticatedClient HttpClient without authentication for auth operations
 * @property authenticatedClient HttpClient with authentication for logout operations
 */
class KtorAuthApiClient(
    private val unauthenticatedClient: HttpClient,
    private val authenticatedClient: HttpClient
) : BaseApiResourceClient(unauthenticatedClient), AuthApi {

    override suspend fun refreshToken(request: RefreshTokenRequest): Either<ApiResourceError, LoginResponse> {
        return safeApiCall {
            unauthenticatedClient.post(AuthResource.Refresh()) {
                setBody(request)
            }.body<LoginResponse>()
        }
    }

    override suspend fun login(request: LoginRequest): Either<ApiResourceError, LoginResponse> {
        return safeApiCall {
            unauthenticatedClient.post(AuthResource.Login()) {
                setBody(request)
            }.body<LoginResponse>()
        }
    }

    override suspend fun register(request: RegisterRequest): Either<ApiResourceError, User> {
        return safeApiCall {
            unauthenticatedClient.post(AuthResource.Register()) {
                setBody(request)
            }.body<User>()
        }
    }

    override suspend fun logout(): Either<ApiResourceError, Unit> {
        return safeApiCall {
            authenticatedClient.post(AuthResource.Logout()).body<Unit>()
        }
    }
}
