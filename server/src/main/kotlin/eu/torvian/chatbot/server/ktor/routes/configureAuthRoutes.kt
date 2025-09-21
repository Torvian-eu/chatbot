package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.models.auth.LoginRequest
import eu.torvian.chatbot.common.models.auth.RefreshTokenRequest
import eu.torvian.chatbot.common.models.auth.RegisterRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.domain.security.mappers.toLoginResponse
import eu.torvian.chatbot.server.ktor.auth.getUserContext
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.security.AuthenticationService
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Authentication (/api/v1/auth) using Ktor Resources.
 */
fun Route.configureAuthRoutes(
    authenticationService: AuthenticationService,
    userService: UserService
) {
    // POST /api/v1/auth/register - User registration
    post<AuthResource.Register> {
        val request = call.receive<RegisterRequest>()
        call.respondEither(
            userService.registerUser(request.username, request.password, request.email),
            HttpStatusCode.Created
        ) { error ->
            when (error) {
                is RegisterUserError.UsernameAlreadyExists ->
                    apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Username already exists")

                is RegisterUserError.EmailAlreadyExists ->
                    apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Email already exists")

                is RegisterUserError.InvalidInput ->
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input", "reason" to error.reason)

                is RegisterUserError.PasswordTooWeak ->
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Password too weak", "reason" to error.reason)

                is RegisterUserError.GroupAssignmentFailed ->
                    apiError(
                        CommonApiErrorCodes.INTERNAL,
                        "Failed to assign user to default group",
                        "reason" to error.reason
                    )
            }
        }
    }

    // POST /api/v1/auth/login - User login
    post<AuthResource.Login> {
        val request = call.receive<LoginRequest>()
        call.respondEither(
            authenticationService.login(request.username, request.password)
                .map { it.toLoginResponse() }
        ) { error ->
            when (error) {
                is LoginError.InvalidCredentials ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid credentials")

                is LoginError.UserNotFound ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid credentials")

                is LoginError.AccountLocked ->
                    apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Account locked", "reason" to error.reason)

                is LoginError.SessionCreationFailed ->
                    apiError(CommonApiErrorCodes.INTERNAL, "Failed to create session", "reason" to error.reason)
            }
        }
    }

    // POST /api/v1/auth/refresh - Refresh access token
    post<AuthResource.Refresh> {
        val request = call.receive<RefreshTokenRequest>()
        call.respondEither(
            authenticationService.refreshToken(request.refreshToken)
                .map { it.toLoginResponse() }
        ) { error ->
            when (error) {
                is RefreshTokenError.InvalidRefreshToken ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid refresh token")

                is RefreshTokenError.ExpiredRefreshToken ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Refresh token expired")

                is RefreshTokenError.InvalidSession ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Session invalid", "reason" to error.reason)

                is RefreshTokenError.TokenGenerationFailed ->
                    apiError(CommonApiErrorCodes.INTERNAL, "Failed to generate new tokens", "reason" to error.reason)
            }
        }
    }

    // POST /api/v1/auth/logout - User logout from current session only
    authenticate(AuthSchemes.USER_JWT) {
        post<AuthResource.Logout> {
            val sessionId = call.getUserContext().sessionId
            call.respondEither(
                authenticationService.logout(sessionId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is LogoutError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")
                }
            }
        }
    }

    // POST /api/v1/auth/logout-all - User logout from all sessions
    authenticate(AuthSchemes.USER_JWT) {
        post<AuthResource.LogoutAll> {
            val userId = call.getUserId()
            call.respondEither(
                authenticationService.logoutAll(userId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is LogoutAllError.NoSessionsFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "No sessions found for user")
                }
            }
        }
    }

    // GET /api/v1/auth/me - Get current user profile
    authenticate(AuthSchemes.USER_JWT) {
        get<AuthResource.Me> {
            call.respond(call.getUserContext().user)
        }
    }
}
