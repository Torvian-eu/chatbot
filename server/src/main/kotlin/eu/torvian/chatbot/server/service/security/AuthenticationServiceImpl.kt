package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.*
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.config.AccountSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.security.error.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Implementation of [AuthenticationService] for core authentication and session management.
 *
 * Handles user login/logout operations and session lifecycle management, including:
 * - User credential validation
 * - JWT token generation
 * - Session creation and deletion
 * - Device-based trust checking during login
 * - Sliding-window lockout for failed login attempts
 *
 * For JWT token operations, see [TokenService].
 * For device trust management, see [DeviceTrustService].
 * For security alerts, see [SecurityAuditService].
 * For account management, see [AccountManagementService].
 *
 * @property userService Service for user-related operations.
 * @property passwordService Service for password hashing and validation.
 * @property jwtConfig JWT configuration for token generation.
 * @property userSessionDao Data access object for user sessions.
 * @property userTrustedDeviceDao Data access object for trusted devices.
 * @property userDeviceDao Data access object for the per-user device registry.
 * @property securityAuditDao Data access object for security audit logs.
 * @property userDao Data access object for user data.
 * @property authorizationService Service for authorization checks.
 * @property transactionScope Scope for database transactions.
 * @property accountSecurityMode Mode for device-based security (DISABLED, WARNING, STRICT).
 * @property failedLoginAttemptDao Data access object for failed login attempts.
 * @property authPolicy Policy for account validation rules.
 */
class AuthenticationServiceImpl(
    private val userService: UserService,
    private val passwordService: PasswordService,
    private val jwtConfig: JwtConfig,
    private val userSessionDao: UserSessionDao,
    private val userTrustedDeviceDao: UserTrustedDeviceDao,
    private val userDeviceDao: UserDeviceDao,
    private val securityAuditDao: SecurityAuditDao,
    private val userDao: UserDao,
    private val authorizationService: AuthorizationService,
    private val transactionScope: TransactionScope,
    private val accountSecurityMode: AccountSecurityMode,
    private val failedLoginAttemptDao: FailedLoginAttemptDao,
    private val authPolicy: AccountValidationPolicy
) : AuthenticationService {

    companion object {
        private val logger: Logger = LogManager.getLogger(AuthenticationServiceImpl::class.java)
    }

    override suspend fun login(
        username: String,
        password: String,
        ipAddress: String?,
        deviceId: String
    ): Either<LoginError, LoginResult> {
        // Define effective IP address early for lockout checks and failure recording
        val effectiveIpAddress = ipAddress ?: "unknown"

        // Calculate sliding window start time for lockout checks
        val currentTime = Clock.System.now()
        val currentTimeMillis = currentTime.toEpochMilliseconds()
        val windowStartMillis = currentTimeMillis - (authPolicy.lockoutWindowMinutes * 60 * 1000L)

        // Check if username has too many failed attempts in the window (before main transaction)
        val usernameFailures = failedLoginAttemptDao.countFailuresByUsername(username, windowStartMillis)
        if (usernameFailures >= authPolicy.maxFailedAttempts) {
            logger.warn("Login blocked for user $username: too many failed attempts ($usernameFailures/${authPolicy.maxFailedAttempts})")
            return LoginError.TooManyAttempts.left()
        }

        // Check if IP address has too many failed attempts in the window (before main transaction)
        val ipFailures = failedLoginAttemptDao.countFailuresByIp(effectiveIpAddress, windowStartMillis)
        if (ipFailures >= authPolicy.maxFailedAttempts) {
            logger.warn("Login blocked for IP $effectiveIpAddress: too many failed attempts ($ipFailures/${authPolicy.maxFailedAttempts})")
            return LoginError.TooManyAttempts.left()
        }

        // Execute core login logic inside transaction and store result
        val result = transactionScope.transaction {
            either {
                logger.info("Attempting login for user: $username")

                // Get user by username
                val userEntity = withError({ _: UserError.UserNotFoundByUsername ->
                    logger.warn("User not found: $username")
                    LoginError.UserNotFound
                }) {
                    userDao.getUserByUsername(username).bind()
                }

                ensure(userEntity.status == UserStatus.ACTIVE) {
                    logger.warn("Account is not active for user: $username (status: ${userEntity.status})")
                    LoginError.AccountLocked("Account is disabled")
                }

                ensure(passwordService.verifyPassword(password, userEntity.passwordHash)) {
                    logger.warn("Invalid password for user: $username")
                    LoginError.InvalidCredentials
                }

                // Keep the device registry in sync with successful logins so preference scopes can resolve.
                val existingRegistryDevice = userDeviceDao.getDeviceByClientId(userEntity.id, deviceId)
                if (existingRegistryDevice == null) {
                    userDeviceDao.insertDevice(userEntity.id, deviceId, null)
                } else {
                    userDeviceDao.updateDeviceUsage(existingRegistryDevice.id, currentTimeMillis)
                }

                // Handle device-based security - determines if the session should be restricted
                val isRestricted = handleDeviceSecurityAndDetermineRestriction(
                    userId = userEntity.id,
                    deviceId = deviceId,
                    ipAddress = ipAddress,
                    currentTime = Clock.System.now()
                )

                // Create session.
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

        // Handle failed login attempts outside the main transaction to ensure persistence
        // even when the main transaction rolls back
        result.fold(
            ifLeft = { error ->
                if (error is LoginError.UserNotFound || error is LoginError.InvalidCredentials) {
                    // Record the failed attempt
                    failedLoginAttemptDao.recordFailure(username, effectiveIpAddress, deviceId)
                    // Cleanup old records to prevent database bloat
                    failedLoginAttemptDao.cleanupOldRecords(windowStartMillis)
                } else if (error is LoginError.VerificationRequired) {
                    // Insert audit record for STRICT mode login attempt (outside transaction so it persists)
                    securityAuditDao.insertAuditRecord(
                        userId = error.userId,
                        deviceId = deviceId,
                        ipAddress = effectiveIpAddress,
                        createdAt = currentTimeMillis
                    )
                }
            },
            ifRight = { loginResult ->
                // Clear failed login attempts on successful login
                // Only clears username-based failures, IP-based failures remain to prevent reset attacks
                failedLoginAttemptDao.clearFailures(username)
                // Insert audit record for WARNING mode login (restricted session)
                if (loginResult.isRestricted) {
                    securityAuditDao.insertAuditRecord(
                        userId = loginResult.user.id,
                        deviceId = deviceId,
                        ipAddress = effectiveIpAddress,
                        createdAt = currentTimeMillis
                    )
                }
            }
        )
        return result
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

        val currentTimeMillis = currentTime.toEpochMilliseconds()

        // Trust on First Use (TOFU): Check if this is the user's first device ever
        val trustedCount = userTrustedDeviceDao.getTrustedDevicesCount(userId)
        if (trustedCount == 0) {
            // FIRST DEVICE EVER: Auto-trust it regardless of security mode
            userTrustedDeviceDao.insertTrustedDevice(
                userId = userId,
                deviceId = deviceId,
                ipAddress = ipAddress,
                firstSeenAt = currentTimeMillis,
                lastUsedAt = currentTimeMillis
            )
            return false
        }

        // Check if this device is already trusted for this user
        val trustedDevice = userTrustedDeviceDao.getTrustedDevice(userId, deviceId)
            ?: // Unknown device - apply security mode policy
            when (accountSecurityMode) {
                AccountSecurityMode.STRICT -> {
                    // Block unknown devices in STRICT mode
                    raise(LoginError.VerificationRequired(userId))
                }

                AccountSecurityMode.WARNING -> {
                    // Restrict session for unknown devices in WARNING mode
                    return true
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
}
