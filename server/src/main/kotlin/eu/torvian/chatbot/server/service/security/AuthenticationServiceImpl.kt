package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.*
import arrow.core.right
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.config.AccountSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.*
import io.ktor.server.auth.jwt.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Implementation of [AuthenticationService] with JWT token generation and validation.
 *
 * This implementation provides complete authentication functionality including:
 * - User credential validation
 * - JWT token generation and validation
 * - Session lifecycle management
 * - Token refresh capabilities
 * - Device-based trust with AccountSecurityMode (DISABLED, WARNING, STRICT)
 */
class AuthenticationServiceImpl(
    private val userService: UserService,
    private val passwordService: PasswordService,
    private val jwtConfig: JwtConfig,
    private val userSessionDao: UserSessionDao,
    private val userTrustedDeviceDao: UserTrustedDeviceDao,
    private val securityAuditDao: SecurityAuditDao,
    private val userDao: UserDao,
    private val workerDao: WorkerDao,
    private val authorizationService: AuthorizationService,
    private val transactionScope: TransactionScope,
    private val accountSecurityMode: AccountSecurityMode
) : AuthenticationService {

    companion object {
        private val logger: Logger = LogManager.getLogger(AuthenticationServiceImpl::class.java)
    }

    override suspend fun login(
        username: String,
        password: String,
        ipAddress: String?,
        deviceId: String
    ): Either<LoginError, LoginResult> =
        transactionScope.transaction {
            either {
                logger.info("Attempting login for user: $username")

                // Get user by username
                val userEntity = withError({ _: UserError.UserNotFoundByUsername ->
                    LoginError.UserNotFound
                }) {
                    userDao.getUserByUsername(username).bind()
                }

                ensure(userEntity.status == UserStatus.ACTIVE) {
                    LoginError.AccountLocked("Account is disabled")
                }

                ensure(passwordService.verifyPassword(password, userEntity.passwordHash)) {
                    logger.warn("Invalid password for user: $username")
                    raise(LoginError.InvalidCredentials)
                }

                // Handle device-based security - determines if the session should be restricted
                val isRestricted = handleDeviceSecurityAndDetermineRestriction(
                    userId = userEntity.id,
                    deviceId = deviceId,
                    ipAddress = ipAddress,
                    currentTime = Clock.System.now()
                )

                // Create session.
                val currentTime = Clock.System.now()
                val currentTimeMillis = currentTime.toEpochMilliseconds()
                val sessionExpirationMs = jwtConfig.refreshExpirationMs
                val sessionExpiresAt = currentTimeMillis + sessionExpirationMs
                val accessTokenExpiresAt = currentTime.plus(jwtConfig.tokenExpirationMs.milliseconds)
                val session: UserSessionEntity = withError({ _: UserSessionError.ForeignKeyViolation ->
                    LoginError.UserNotFound
                }) {
                    userSessionDao.insertSession(userEntity.id, deviceId, sessionExpiresAt, ipAddress, isRestricted)
                        .bind()
                }

                // Generate tokens
                val accessToken = jwtConfig.generateAccessToken(
                    userId = userEntity.id,
                    sessionId = session.id,
                    isRestricted = isRestricted,
                    currentTime = currentTimeMillis
                )
                val refreshToken = jwtConfig.generateRefreshToken(
                    userId = userEntity.id,
                    sessionId = session.id,
                    currentTime = currentTimeMillis
                )

                // Update last login
                userService.updateLastLogin(userEntity.id).mapLeft {
                    logger.warn("Failed to update last login for user: $username")
                    // Don't fail the login if updating last login fails
                }

                // Retrieve user permissions
                val permissions = authorizationService.getUserPermissions(userEntity.id)

                logger.info("Successful login for user: $username")
                LoginResult(
                    user = userEntity.toUser(),
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = accessTokenExpiresAt,
                    permissions = permissions,
                    isRestricted = isRestricted,
                    deviceId = deviceId
                )
            }
        }

    /**
     * Handles device-based security and determines if the session should be restricted.
     *
     * Uses [AccountSecurityMode] for the security policy:
     * - DISABLED: Always allow unrestricted sessions.
     * - WARNING: Restrict sessions for unknown devices until acknowledged.
     * - STRICT: Block unknown devices entirely with [LoginError.VerificationRequired].
     *
     * Trust on First Use (TOFU): The first device a user ever logs in with is automatically
     * trusted, regardless of security mode (except DISABLED). Subsequent devices follow the
     * WARNING/STRICT rules.
     *
     * @return true if the session should be restricted, false otherwise
     */
    private suspend fun Raise<LoginError>.handleDeviceSecurityAndDetermineRestriction(
        userId: Long,
        deviceId: String,
        ipAddress: String?,
        currentTime: Instant
    ): Boolean {
        // DISABLED mode: always allow unrestricted sessions
        if (accountSecurityMode == AccountSecurityMode.DISABLED) {
            return false
        }

        val normalizedDeviceId = deviceId.trim()
        val currentTimeMillis = currentTime.toEpochMilliseconds()

        // Trust on First Use (TOFU): Check if this is the user's first device ever
        val trustedCount = userTrustedDeviceDao.getTrustedDevicesCount(userId)
        if (trustedCount == 0) {
            // FIRST DEVICE EVER: Auto-trust it regardless of security mode
            userTrustedDeviceDao.insertTrustedDevice(
                userId = userId,
                deviceId = normalizedDeviceId,
                ipAddress = ipAddress,
                firstSeenAt = currentTimeMillis,
                lastUsedAt = currentTimeMillis
            )
            return false
        }

        // Check if this device is already trusted for this user
        val trustedDevice = userTrustedDeviceDao.getTrustedDevice(userId, normalizedDeviceId)

        if (trustedDevice == null) {
            // Unknown device - create audit record
            securityAuditDao.insertAuditRecord(
                userId = userId,
                deviceId = normalizedDeviceId,
                ipAddress = ipAddress,
                createdAt = currentTimeMillis
            )

            // Apply security mode policy
            return when (accountSecurityMode) {
                AccountSecurityMode.STRICT -> {
                    // Block unknown devices in STRICT mode
                    raise(LoginError.VerificationRequired)
                }

                AccountSecurityMode.WARNING -> {
                    // Restrict session for unknown devices in WARNING mode
                    true
                }
            }
        }

        // Known device found - update last used info and allow unrestricted session
        userTrustedDeviceDao.updateLastUsedAt(
            id = trustedDevice.id,
            lastUsedAt = currentTimeMillis,
            lastIpAddress = ipAddress
        )
        return false
    }

    override suspend fun logout(
        userId: Long,
        targetSessionId: Long,
        requesterSessionId: Long,
        requesterIsRestricted: Boolean
    ): Either<LogoutError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Logging out session: $targetSessionId, user: $userId, requester: $requesterSessionId, restricted: $requesterIsRestricted")

                // Fetch the target session to validate ownership
                val targetSession = withError({ _: UserSessionError.SessionNotFound ->
                    LogoutError.SessionNotFound(targetSessionId)
                }) {
                    userSessionDao.getSessionById(targetSessionId).bind()
                }

                // Validate that the session belongs to the requesting user (ownership check)
                // This prevents users from logging out sessions belonging to other users
                ensure(targetSession.userId == userId) {
                    logger.warn("Session $targetSessionId does not belong to user $userId")
                    raise(LogoutError.SessionNotFound(targetSessionId))
                }

                // Restricted sessions can only log out themselves, not other sessions
                if (targetSessionId != requesterSessionId) {
                    ensure(!requesterIsRestricted) {
                        logger.warn("Restricted session attempted to revoke another session: $targetSessionId")
                        raise(LogoutError.InsufficientPermissions)
                    }
                }

                // Delete the specific session
                withError({ _: UserSessionError.SessionNotFound ->
                    LogoutError.SessionNotFound(targetSessionId)
                }) {
                    userSessionDao.deleteSession(targetSessionId).bind()
                }

                logger.info("Successfully logged out session: $targetSessionId")
            }
        }

    override suspend fun logoutAll(userId: Long, requesterIsRestricted: Boolean): Either<LogoutAllError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Logging out all sessions for user: $userId, restricted: $requesterIsRestricted")

                // Restricted sessions cannot log out from all sessions
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to log out from all sessions")
                    raise(LogoutAllError.InsufficientPermissions)
                }

                // Delete all sessions for the user
                val deletedCount = userSessionDao.deleteSessionsByUserId(userId)

                ensure(deletedCount > 0) {
                    logger.warn("No sessions found for user: $userId")
                    raise(LogoutAllError.NoSessionsFound(userId))
                }

                logger.info("Successfully logged out user: $userId (deleted $deletedCount sessions)")
            }
        }

    override suspend fun getUserSessions(userId: Long): Either<Nothing, List<UserSessionEntity>> =
        transactionScope.transaction {
            // The DAO already scopes the query by user ID, so this remains a pure read operation.
            userSessionDao.getSessionsByUserId(userId).right()
        }

    override suspend fun getSecurityAlerts(userId: Long): Either<Nothing, List<SecurityAuditEntity>> =
        transactionScope.transaction {
            securityAuditDao.getUnacknowledgedByUserId(userId).right()
        }

    override suspend fun acknowledgeSecurityAlerts(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<AcknowledgeAlertsError, Unit> =
        transactionScope.transaction {
            either {
                // Restricted sessions cannot acknowledge alerts - prevents self-acknowledgement of untrusted devices
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to acknowledge security alerts")
                    raise(AcknowledgeAlertsError.InsufficientPermissions())
                }

                // 1. Fetch all unacknowledged records from SecurityAuditDao
                val auditRecords = securityAuditDao.getUnacknowledgedByUserId(userId)

                if (auditRecords.isEmpty()) {
                    return@transaction Unit.right()
                }

                // 2. For each unique deviceId, insert into trusted devices table if not already there
                val currentTimeMillis = System.currentTimeMillis()
                val processedDeviceIds = mutableSetOf<String>()

                for (alert in auditRecords) {
                    val deviceId = alert.deviceId

                    // Skip if we've already processed this deviceId
                    if (deviceId in processedDeviceIds) {
                        continue
                    }
                    processedDeviceIds.add(deviceId)

                    // Check if it already exists in trusted devices
                    val existingDevice = userTrustedDeviceDao.getTrustedDevice(userId, deviceId)
                    if (existingDevice == null) {
                        // Insert into trusted devices table
                        userTrustedDeviceDao.insertTrustedDevice(
                            userId = userId,
                            deviceId = deviceId,
                            ipAddress = alert.ipAddress,
                            firstSeenAt = currentTimeMillis,
                            lastUsedAt = currentTimeMillis
                        )
                    }
                }

                // 3. Mark all audit records as acknowledged
                securityAuditDao.acknowledgeAllByUserId(userId)
            }
        }

    override suspend fun refreshToken(
        refreshToken: String,
        ipAddress: String?
    ): Either<RefreshTokenError, LoginResult> =
        transactionScope.transaction {
            either {
                // First, decode and verify the refresh token (non-suspend operations)
                // This block remains as is, as withError is not suitable for early return from Either.catch
                val decodedJWT = catch({
                    jwtConfig.userVerifier.verify(refreshToken)
                }) { e ->
                    when (e) {
                        is JWTDecodeException -> {
                            logger.debug("Refresh token decode failed", e)
                            raise(RefreshTokenError.InvalidRefreshToken)
                        }

                        is JWTVerificationException -> {
                            logger.debug("Refresh token verification failed", e)
                            raise(RefreshTokenError.InvalidRefreshToken)
                        }

                        else -> {
                            logger.error("Unexpected error during token refresh", e)
                            raise(RefreshTokenError.TokenGenerationFailed("Unexpected error occurred"))
                        }
                    }
                }

                // Verify this is actually a refresh token
                val tokenType = decodedJWT.getClaim("tokenType")?.asString()
                ensure(tokenType == "refresh") {
                    RefreshTokenError.InvalidRefreshToken
                }

                // Extract claims
                val userId = decodedJWT.subject?.toLongOrNull()
                    ?: raise(RefreshTokenError.InvalidRefreshToken)

                val sessionId = decodedJWT.getClaim("sessionId")?.asLong()
                    ?: raise(RefreshTokenError.InvalidRefreshToken)

                // Validate session exists
                val session = withError({ _: UserSessionError.SessionNotFound ->
                    RefreshTokenError.InvalidSession("Session not found")
                }) {
                    userSessionDao.getSessionById(sessionId).bind()
                }

                // Validate session is not expired
                val currentTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                ensure(session.expiresAt > currentTime) {
                    RefreshTokenError.InvalidSession("Session expired")
                }

                // Get user details
                val user = withError({ _: UserNotFoundError.ById ->
                    RefreshTokenError.InvalidSession("User not found")
                }) {
                    userService.getUserById(userId).bind()
                }

                // Delete old session
                withError({ _: UserSessionError.SessionNotFound ->
                    RefreshTokenError.InvalidSession("Session not found")
                }) {
                    userSessionDao.deleteSession(sessionId).bind()
                }

                // Create new session, preserving the restricted status from the old session
                val newSessionExpirationMs = jwtConfig.refreshExpirationMs
                val newSessionExpiresAt = currentTime.toEpochMilliseconds() + newSessionExpirationMs
                val newSession = withError({ _: UserSessionError.ForeignKeyViolation ->
                    RefreshTokenError.InvalidSession("User not found")
                }) {
                    userSessionDao.insertSession(
                        userId,
                        session.deviceId,
                        newSessionExpiresAt,
                        ipAddress,
                        session.isRestricted
                    ).bind()
                }

                // Generate new tokens, preserving the restricted status
                val newAccessToken = jwtConfig.generateAccessToken(
                    userId = userId,
                    sessionId = newSession.id,
                    isRestricted = session.isRestricted,
                    currentTime = currentTime.toEpochMilliseconds()
                )
                val newRefreshToken = jwtConfig.generateRefreshToken(
                    userId = userId,
                    sessionId = newSession.id,
                    currentTime = currentTime.toEpochMilliseconds()
                )
                val tokenExpiresAt = currentTime.plus(jwtConfig.tokenExpirationMs.milliseconds)

                // Retrieve user permissions
                val permissions = authorizationService.getUserPermissions(userId)

                logger.info("Successfully refreshed tokens for user: $userId")

                LoginResult(
                    user = user,
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    expiresAt = tokenExpiresAt,
                    permissions = permissions,
                    isRestricted = session.isRestricted,
                    deviceId = null // Device ID not available during token refresh; client uses its own device ID
                )
            }
        }

    override suspend fun validateCredential(credential: JWTCredential): UserContext? = transactionScope.transaction {
        either {
            logger.debug("Validating JWT credential for subject: ${credential.payload.subject}")
            // Ktor already verified the signature and expiration. We validate the business logic.

            val principalType = credential.payload.getClaim("principalType")?.asString()
            ensure(principalType == "user") {
                TokenValidationError.InvalidClaims
            }

            // Ensure only access tokens can authenticate protected routes.
            val tokenType = credential.payload.getClaim("tokenType")?.asString()
            ensure(tokenType == "access") {
                TokenValidationError.InvalidClaims
            }

            // Extract claims from the payload
            val userId = credential.payload.subject?.toLongOrNull()
                ?: raise(TokenValidationError.InvalidClaims)
            val sessionId = credential.payload.getClaim("sessionId")?.asLong()
                ?: raise(TokenValidationError.InvalidClaims)
            val issuedAt = Instant.fromEpochMilliseconds(credential.payload.issuedAt?.time ?: 0L)
            val expiresAt = Instant.fromEpochMilliseconds(credential.payload.expiresAt?.time ?: 0L)

            // Validate session exists in the database
            val session = withError({ _: UserSessionError.SessionNotFound ->
                TokenValidationError.InvalidSession("Session not found")
            }) {
                userSessionDao.getSessionById(sessionId).bind()
            }

            // Validate session is not expired
            val currentTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            ensure(session.expiresAt > currentTime) {
                TokenValidationError.InvalidSession("Session expired")
            }

            // Get user details
            val user = withError({ _: UserNotFoundError.ById ->
                TokenValidationError.InvalidSession("User not found for session")
            }) {
                userService.getUserById(userId).bind()
            }

            ensure(user.status == UserStatus.ACTIVE) {
                TokenValidationError.AccountLocked("Account is disabled")
            }

            // Update session last accessed time
            userSessionDao.updateLastAccessed(sessionId, currentTime.toEpochMilliseconds())

            val userContext = UserContext(
                user = user,
                sessionId = sessionId,
                tokenIssuedAt = issuedAt,
                tokenExpiresAt = expiresAt,
                isRestricted = session.isRestricted
            )

            logger.debug("Credential validation successful for user: ${user.username}")
            userContext
        }.getOrElse {  // Return UserContext on success, null on any validation error
            logger.debug("Credential validation failed: {}", it)
            null
        }
    }

    override suspend fun validateWorkerCredential(credential: JWTCredential): WorkerContext? =
        transactionScope.transaction {
            either {
                logger.debug("Validating worker credential (subject={})", credential.payload.subject)

                val principalType = credential.payload.getClaim("principalType")?.asString()
                if (principalType != "service") {
                    logger.debug("Worker credential rejected: invalid principal type ({})", principalType)
                    raise(TokenValidationError.InvalidClaims)
                }

                val tokenType = credential.payload.getClaim("tokenType")?.asString()
                if (tokenType != "access") {
                    logger.debug("Worker credential rejected: invalid token type ({})", tokenType)
                    raise(TokenValidationError.InvalidClaims)
                }

                val workerId = credential.payload.getClaim("workerId")?.asLong()
                    ?: raise(TokenValidationError.InvalidClaims)
                val claimedWorkerUid = credential.payload.getClaim("workerUid")?.asString()
                val ownerUserId = credential.payload.getClaim("ownerUserId")?.asLong()
                    ?: raise(TokenValidationError.InvalidClaims)
                val scopes = credential.payload.getClaim("scope")?.asList(String::class.java) ?: emptyList()
                val issuedAt = Instant.fromEpochMilliseconds(credential.payload.issuedAt?.time ?: 0L)
                val expiresAt = Instant.fromEpochMilliseconds(credential.payload.expiresAt?.time ?: 0L)

                val worker = withError({ _: WorkerError.NotFound ->
                    logger.warn("Worker credential rejected: worker not found (workerId={})", workerId)
                    TokenValidationError.InvalidSession("Worker not found")
                }) {
                    workerDao.getWorkerById(workerId).bind()
                }

                if (worker.ownerUserId != ownerUserId) {
                    logger.warn("Worker credential rejected: owner mismatch (workerId={})", workerId)
                    raise(TokenValidationError.InvalidClaims)
                }
                if (claimedWorkerUid != null && claimedWorkerUid != worker.workerUid) {
                    logger.warn("Worker credential rejected: worker UID mismatch (workerId={})", workerId)
                    raise(TokenValidationError.InvalidClaims)
                }

                val context = WorkerContext(
                    workerId = worker.id,
                    workerUid = worker.workerUid,
                    ownerUserId = ownerUserId,
                    scopes = scopes,
                    tokenIssuedAt = issuedAt,
                    tokenExpiresAt = expiresAt
                )

                logger.debug("Worker credential validation successful (workerId={})", worker.id)
                context
            }.getOrElse {
                logger.debug("Worker credential validation failed: {}", it)
                null
            }
        }

    override suspend fun getTrustedDevices(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<RevokeTrustedDeviceError, List<UserTrustedDeviceInfo>> =
        transactionScope.transaction {
            either {
                // Restricted sessions cannot list trusted devices - prevents enumeration attacks
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to list trusted devices")
                    raise(RevokeTrustedDeviceError.InsufficientPermissions())
                }

                // Fetch all trusted devices for the user
                val devices = userTrustedDeviceDao.getTrustedDevices(userId)

                // Map to API response DTO
                devices.map { device ->
                    UserTrustedDeviceInfo(
                        deviceId = device.deviceId,
                        lastIpAddress = device.lastIpAddress,
                        firstSeenAt = device.firstSeenAt,
                        lastUsedAt = device.lastUsedAt
                    )
                }
            }
        }

    override suspend fun revokeTrustedDevice(
        userId: Long,
        deviceId: String,
        requesterIsRestricted: Boolean
    ): Either<RevokeTrustedDeviceError, Unit> =
        transactionScope.transaction {
            either {
                // Restricted sessions cannot revoke devices - prevents malicious actions from unverified devices
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to revoke trusted device: $deviceId")
                    raise(RevokeTrustedDeviceError.InsufficientPermissions())
                }

                // Delete the trusted device
                val deletedCount = userTrustedDeviceDao.deleteTrustedDevice(userId, deviceId)

                ensure(deletedCount > 0) {
                    logger.warn("Device not found for revocation: $deviceId, user: $userId")
                    raise(RevokeTrustedDeviceError.DeviceNotFound(deviceId))
                }

                logger.info("Successfully revoked trusted device: $deviceId for user: $userId")
            }
        }

    override suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<ChangePasswordError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Changing password for user: $userId, restricted: $requesterIsRestricted")

                // 1. Check restriction - restricted sessions cannot change password
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to change password")
                    raise(ChangePasswordError.InsufficientPermissions)
                }

                // 2. Verify current password
                val userEntity = withError({ _: UserError.UserNotFound ->
                    ChangePasswordError.UserNotFound(userId)
                }) {
                    userDao.getUserById(userId).bind()
                }

                // Verify the current password matches
                ensure(passwordService.verifyPassword(currentPassword, userEntity.passwordHash)) {
                    logger.warn("Invalid current password for user: $userId")
                    raise(ChangePasswordError.InvalidCurrentPassword)
                }

                // 3. Validate new password strength
                withError({ passwordError ->
                    when (passwordError) {
                        is PasswordValidationError.Empty ->
                            ChangePasswordError.InvalidPassword("Password cannot be empty")

                        is PasswordValidationError.OnlyWhitespace ->
                            ChangePasswordError.InvalidPassword("Password cannot contain only whitespace")

                        is PasswordValidationError.TooShort ->
                            ChangePasswordError.InvalidPassword(
                                "Password must be at least ${passwordError.minLength} characters"
                            )

                        is PasswordValidationError.TooLong ->
                            ChangePasswordError.InvalidPassword(
                                "Password must be no more than ${passwordError.maxLength} characters"
                            )

                        is PasswordValidationError.MissingCharacterTypes ->
                            ChangePasswordError.InvalidPassword(
                                "Password must contain required character types"
                            )

                        is PasswordValidationError.TooCommon ->
                            ChangePasswordError.InvalidPassword(passwordError.reason)
                    }
                }) {
                    passwordService.validatePasswordStrength(newPassword).bind()
                }

                // Prevent reusing the current password
                ensure(!passwordService.verifyPassword(newPassword, userEntity.passwordHash)) {
                    logger.warn("User $userId attempted to reuse current password")
                    raise(ChangePasswordError.SameAsCurrentPassword)
                }

                // 4. Hash new password and update user record
                val hashedPassword = passwordService.hashPassword(newPassword)

                // Update user with new password and clear requiresPasswordChange flag
                val updatedUser = userEntity.copy(
                    passwordHash = hashedPassword,
                    requiresPasswordChange = false
                )
                // Use mapLeft to convert the error type
                userDao.updateUser(updatedUser).mapLeft { error ->
                    when (error) {
                        is UserError.UserNotFound -> ChangePasswordError.UserNotFound(userId)
                        else -> ChangePasswordError.UserNotFound(userId)
                    }
                }.bind()

                logger.info("Successfully changed password for user: $userId")
            }
        }

    override suspend fun completeRequiredPasswordChange(
        userId: Long,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<CompleteRequiredPasswordChangeError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Completing required password change for user: $userId, restricted: $requesterIsRestricted")

                // 1. Check restriction - restricted sessions cannot complete required password change
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to complete required password change")
                    raise(CompleteRequiredPasswordChangeError.InsufficientPermissions)
                }

                // 2. Fetch user entity
                val userEntity = withError({ _: UserError.UserNotFound ->
                    CompleteRequiredPasswordChangeError.UserNotFound
                }) {
                    userDao.getUserById(userId).bind()
                }

                // 3. Check that password change is required for this user
                ensure(userEntity.requiresPasswordChange) {
                    logger.warn("User $userId attempted password change but it's not required")
                    raise(CompleteRequiredPasswordChangeError.PasswordChangeNotRequired)
                }

                // 4. Validate new password strength
                withError({ passwordError ->
                    when (passwordError) {
                        is PasswordValidationError.Empty ->
                            CompleteRequiredPasswordChangeError.WeakPassword("Password cannot be empty")

                        is PasswordValidationError.OnlyWhitespace ->
                            CompleteRequiredPasswordChangeError.WeakPassword("Password cannot contain only whitespace")

                        is PasswordValidationError.TooShort ->
                            CompleteRequiredPasswordChangeError.WeakPassword(
                                "Password must be at least ${passwordError.minLength} characters"
                            )

                        is PasswordValidationError.TooLong ->
                            CompleteRequiredPasswordChangeError.WeakPassword(
                                "Password must be no more than ${passwordError.maxLength} characters"
                            )

                        is PasswordValidationError.MissingCharacterTypes ->
                            CompleteRequiredPasswordChangeError.WeakPassword(
                                "Password must contain required character types"
                            )

                        is PasswordValidationError.TooCommon ->
                            CompleteRequiredPasswordChangeError.WeakPassword(passwordError.reason)
                    }
                }) {
                    passwordService.validatePasswordStrength(newPassword).bind()
                }

                // 5. Hash new password and update user record
                val hashedPassword = passwordService.hashPassword(newPassword)

                // Update user with new password and clear requiresPasswordChange flag
                val updatedUser = userEntity.copy(
                    passwordHash = hashedPassword,
                    requiresPasswordChange = false
                )
                // Use mapLeft to convert the error type
                userDao.updateUser(updatedUser).mapLeft { error ->
                    when (error) {
                        is UserError.UserNotFound -> CompleteRequiredPasswordChangeError.UserNotFound
                        else -> CompleteRequiredPasswordChangeError.UpdateFailed("Database update failed")
                    }
                }.bind()

                logger.info("Successfully completed required password change for user: $userId")
            }
        }
}
