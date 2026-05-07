package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.models.api.auth.*
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.domain.security.mappers.toLoginResponse
import eu.torvian.chatbot.server.ktor.auth.getUserContext
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.core.error.worker.AuthenticateWorkerError
import eu.torvian.chatbot.server.service.security.AuthenticationService
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import eu.torvian.chatbot.server.service.security.error.AcknowledgeAlertsError
import eu.torvian.chatbot.server.service.security.error.RevokeTrustedDeviceError
import eu.torvian.chatbot.server.service.security.error.CompleteRequiredPasswordChangeError
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.time.Instant.Companion.fromEpochMilliseconds

/**
 * Configures routes related to Authentication (/api/v1/auth) using Ktor Resources.
 */
fun Route.configureAuthRoutes(
    authenticationService: AuthenticationService,
    userService: UserService,
    workerService: WorkerService,
    jwtConfig: eu.torvian.chatbot.server.domain.security.JwtConfig
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
        // Extract client IP address for session tracking (supports proxy via X-Forwarded-For header)
        val ipAddress = call.request.origin.remoteAddress
        call.respondEither(
            authenticationService.login(request.username, request.password, ipAddress, request.deviceId)
                .map { it.toLoginResponse() }
        ) { error ->
            when (error) {
                is LoginError.InvalidCredentials ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid credentials")

                is LoginError.UserNotFound ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid credentials")

                is LoginError.AccountLocked ->
                    apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Account locked", "reason" to error.reason)

                is LoginError.VerificationRequired ->
                    apiError(CommonApiErrorCodes.VERIFICATION_REQUIRED, "Verification required")

                is LoginError.SessionCreationFailed ->
                    apiError(CommonApiErrorCodes.INTERNAL, "Failed to create session", "reason" to error.reason)
            }
        }
    }

    // POST /api/v1/auth/refresh - Refresh access token
    post<AuthResource.Refresh> {
        val request = call.receive<RefreshTokenRequest>()
        // Extract client IP address for session tracking (supports proxy via X-Forwarded-For header)
        val ipAddress = call.request.origin.remoteAddress
        call.respondEither(
            authenticationService.refreshToken(request.refreshToken, ipAddress)
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
        post<AuthResource.Logout> { resource ->
            val userContext = call.getUserContext()
            val targetSessionId = resource.sessionId ?: userContext.sessionId

            call.respondEither(
                authenticationService.logout(
                    userId = userContext.user.id,
                    targetSessionId = targetSessionId,
                    requesterSessionId = userContext.sessionId,
                    requesterIsRestricted = userContext.isRestricted
                ),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is LogoutError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found")

                    is LogoutError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )
                }
            }
        }
    }

    // GET /api/v1/auth/sessions - List authenticated user's sessions
    authenticate(AuthSchemes.USER_JWT) {
        get<AuthResource.Sessions> {
            val userContext = call.getUserContext()

            // Restricted sessions (unacknowledged devices) cannot view other sessions
            // to prevent enumeration attacks on unverified devices.
            if (userContext.isRestricted) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    apiError(
                        CommonApiErrorCodes.PERMISSION_DENIED,
                        "Action requires a trusted session"
                    )
                )
                return@get
            }

            val result = authenticationService.getUserSessions(userContext.user.id).map { sessions ->
                // Most recently used sessions are shown first so the active device is easy to spot.
                sessions
                    .sortedByDescending { it.lastAccessed }
                    .map { session ->
                        UserSessionInfo(
                            sessionId = session.id,
                            deviceId = session.deviceId,
                            ipAddress = session.ipAddress,
                            createdAt = session.createdAt,
                            lastAccessed = session.lastAccessed,
                            expiresAt = session.expiresAt,
                            isCurrentSession = session.id == userContext.sessionId
                        )
                    }
            }
            call.respondEither(result)
        }
    }

    // POST /api/v1/auth/logout-all - User logout from all sessions
    authenticate(AuthSchemes.USER_JWT) {
        post<AuthResource.LogoutAll> {
            val userContext = call.getUserContext()
            val userId = call.getUserId()
            call.respondEither(
                authenticationService.logoutAll(userId, userContext.isRestricted),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is LogoutAllError.NoSessionsFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "No sessions found for user")

                    is LogoutAllError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )
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

    // GET /api/v1/auth/security-alerts - Get unacknowledged security alerts for the current user
    authenticate(AuthSchemes.USER_JWT) {
        get<AuthResource.SecurityAlerts> {
            val userId = call.getUserId()
            val result = authenticationService.getSecurityAlerts(userId).map { alerts ->
                alerts.map { alert ->
                    UserSecurityAlert(
                        id = alert.id,
                        deviceId = alert.deviceId,
                        ipAddress = alert.ipAddress,
                        firstSeenAt = alert.createdAt,
                        lastSeenAt = alert.createdAt
                    )
                }
            }
            call.respondEither(result)
        }
    }

    // POST /api/v1/auth/acknowledge-alerts - Clear pending security alerts for the current user
    authenticate(AuthSchemes.USER_JWT) {
        post<AuthResource.AcknowledgeAlerts> {
            val userContext = call.getUserContext()

            call.respondEither(
                authenticationService.acknowledgeSecurityAlerts(userContext.user.id, userContext.isRestricted),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is AcknowledgeAlertsError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )
                }
            }
        }
    }

    // GET /api/v1/auth/trusted-devices - List trusted devices for the current user
    authenticate(AuthSchemes.USER_JWT) {
        get<AuthResource.TrustedDevices> {
            val userContext = call.getUserContext()

            call.respondEither(
                authenticationService.getTrustedDevices(userContext.user.id, userContext.isRestricted)
            ) { error ->
                when (error) {
                    is RevokeTrustedDeviceError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )

                    is RevokeTrustedDeviceError.DeviceNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Device not found")
                }
            }
        }
    }

    // DELETE /api/v1/auth/trusted-devices/{deviceId} - Revoke a specific trusted device
    authenticate(AuthSchemes.USER_JWT) {
        delete<AuthResource.RevokeTrustedDevice> { resource ->
            val userContext = call.getUserContext()

            call.respondEither(
                authenticationService.revokeTrustedDevice(userContext.user.id, resource.deviceId, userContext.isRestricted),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is RevokeTrustedDeviceError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )

                    is RevokeTrustedDeviceError.DeviceNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Device not found")
                }
            }
        }
    }

    post<AuthResource.ServiceTokenChallenge> {
        val request = call.receive<ServiceTokenChallengeRequest>()
        call.respondEither(
            workerService.createServiceTokenChallenge(request.workerUid, request.certificateFingerprint)
                .map { ServiceTokenChallengeResponse(request.workerUid, it) }
        ) { error ->
            when (error) {
                is AuthenticateWorkerError.WorkerNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Worker not found")

                is AuthenticateWorkerError.InvalidChallenge,
                is AuthenticateWorkerError.InvalidSignature ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid worker challenge")
            }
        }
    }

    post<AuthResource.ServiceToken> {
        val request = call.receive<ServiceTokenRequest>()
        call.respondEither(
            workerService.authenticateWorker(request.workerUid, request.challengeId, request.signatureBase64)
                .map { worker ->
                    val currentTime = System.currentTimeMillis()
                    val serviceTokenTtlMs = 15 * 60 * 1000L
                    val token = jwtConfig.generateServiceAccessToken(
                        workerId = worker.id,
                        workerUid = worker.workerUid,
                        ownerUserId = worker.ownerUserId,
                        scopes = worker.allowedScopes,
                        currentTime = currentTime,
                        ttlMs = serviceTokenTtlMs
                    )
                    ServiceTokenResponse(
                        accessToken = token,
                        expiresAt = fromEpochMilliseconds(currentTime + serviceTokenTtlMs),
                        worker = worker
                    )
                }
        ) { error ->
            when (error) {
                is AuthenticateWorkerError.WorkerNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Worker not found")


                is AuthenticateWorkerError.InvalidChallenge,
                is AuthenticateWorkerError.InvalidSignature ->
                    apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Invalid worker authentication")
            }
        }
    }

    // POST /api/v1/auth/change-password - Change the authenticated user's password
    authenticate(AuthSchemes.USER_JWT) {
        post<AuthResource.ChangePassword> {
            val userContext = call.getUserContext()
            val request = call.receive<ChangePasswordRequest>()

            call.respondEither(
                authenticationService.changePassword(
                    userId = userContext.user.id,
                    currentPassword = request.currentPassword,
                    newPassword = request.newPassword,
                    requesterIsRestricted = userContext.isRestricted
                ),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is ChangePasswordError.InvalidCurrentPassword ->
                        apiError(CommonApiErrorCodes.INVALID_CREDENTIALS, "Current password is incorrect")

                    is ChangePasswordError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )

                    is ChangePasswordError.InvalidPassword ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, error.reason)

                    is ChangePasswordError.SameAsCurrentPassword ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "New password cannot be the same as the current password")

                    is ChangePasswordError.UserNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found")
                }
            }
        }
    }

    // POST /api/v1/auth/complete-required-password-change - Complete a server-required password change
    authenticate(AuthSchemes.USER_JWT) {
        post<AuthResource.CompleteRequiredPasswordChange> {
            val userContext = call.getUserContext()
            val request = call.receive<CompleteRequiredPasswordChangeRequest>()

            call.respondEither(
                authenticationService.completeRequiredPasswordChange(
                    userId = userContext.user.id,
                    newPassword = request.newPassword,
                    requesterIsRestricted = userContext.isRestricted
                ),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is CompleteRequiredPasswordChangeError.InsufficientPermissions ->
                        apiError(
                            CommonApiErrorCodes.PERMISSION_DENIED,
                            "Action requires a trusted session. Please verify via email or another device."
                        )

                    is CompleteRequiredPasswordChangeError.PasswordChangeNotRequired ->
                        apiError(
                            CommonApiErrorCodes.FAILED_PRECONDITION,
                            "Password change is not required"
                        )

                    is CompleteRequiredPasswordChangeError.WeakPassword ->
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "Weak password",
                            "reason" to error.reason
                        )

                    is CompleteRequiredPasswordChangeError.UserNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found")

                    is CompleteRequiredPasswordChangeError.UpdateFailed ->
                        apiError(
                            CommonApiErrorCodes.INTERNAL,
                            "Failed to update password",
                            "reason" to error.reason
                        )
                }
            }
        }
    }
}
