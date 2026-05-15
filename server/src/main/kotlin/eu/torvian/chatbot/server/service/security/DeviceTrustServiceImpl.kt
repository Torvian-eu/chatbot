package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.service.security.error.RequestDeviceVerificationError
import eu.torvian.chatbot.server.service.security.error.RevokeTrustedDeviceError
import eu.torvian.chatbot.server.service.security.error.VerifyDeviceError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [DeviceTrustService] for device trust and verification.
 *
 * Manages trusted device tracking, device verification via email tokens,
 * and device revocation for multi-device authentication.
 */
class DeviceTrustServiceImpl(
    private val userDao: UserDao,
    private val userTrustedDeviceDao: UserTrustedDeviceDao,
    private val userSessionDao: UserSessionDao,
    private val securityAuditDao: SecurityAuditDao,
    private val deviceVerificationTokenDao: DeviceVerificationTokenDao,
    private val securityNotificationService: SecurityNotificationService,
    private val transactionScope: TransactionScope
) : DeviceTrustService {

    companion object {
        private val logger: Logger = LogManager.getLogger(DeviceTrustServiceImpl::class.java)
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

    override suspend fun requestDeviceVerificationEmail(
        userId: Long,
        deviceId: String
    ): Either<RequestDeviceVerificationError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Requesting device verification email for user: $userId, device: $deviceId")

                // 1. Fetch user to check for email
                val userEntity = withError({ _: UserError.UserNotFound ->
                    RequestDeviceVerificationError.UserHasNoEmail
                }) {
                    userDao.getUserById(userId).bind()
                }

                // 2. Check user has an email address
                ensure(!userEntity.email.isNullOrBlank()) {
                    logger.warn("User $userId has no email address on file")
                    raise(RequestDeviceVerificationError.UserHasNoEmail)
                }

                // 3. Check rate limit (1 hour = 3600000 ms)
                val rateLimitMillis = 60 * 60 * 1000L
                val lastTokenCreatedAt = deviceVerificationTokenDao.getLastTokenCreatedAt(userId, deviceId)
                if (lastTokenCreatedAt != null) {
                    val timeSinceLastToken = System.currentTimeMillis() - lastTokenCreatedAt
                    if (timeSinceLastToken < rateLimitMillis) {
                        val retryAfterMillis = rateLimitMillis - timeSinceLastToken
                        logger.warn("Rate limit exceeded for user: $userId, device: $deviceId")
                        raise(RequestDeviceVerificationError.RateLimitExceeded(retryAfterMillis))
                    }
                }

                // 4. Generate secure token (UUID)
                val token = java.util.UUID.randomUUID().toString()
                val currentTimeMillis = System.currentTimeMillis()
                val expiresAtMillis = currentTimeMillis + rateLimitMillis

                // 5. Send device verification email via notification service
                // Note: We fail the request if email sending fails
                securityNotificationService.sendDeviceVerification(
                    userEmail = userEntity.email,
                    token = token
                ).mapLeft { mailError ->
                    logger.warn("Failed to send device verification email: $mailError")
                    RequestDeviceVerificationError.NotificationServiceFailed
                }.bind()

                // 6. Store the token
                deviceVerificationTokenDao.createToken(
                    userId = userId,
                    deviceId = deviceId,
                    token = token,
                    expiresAt = expiresAtMillis,
                    createdAt = currentTimeMillis
                )

                logger.info("Successfully created device verification token for user: $userId, device: $deviceId")
            }
        }

    override suspend fun requestPublicDeviceVerification(
        username: String,
        deviceId: String
    ): Either<RequestDeviceVerificationError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Requesting public device verification for username: $username, device: $deviceId")

                // 1. Find user by username
                val userEntity = userDao.getUserByUsername(username).mapLeft {
                    // User not found - return success to prevent account enumeration
                    logger.info("Public device verification: user not found (returning success for security)")
                    return@either
                }.bind()

                // 2. Check if device is already trusted for this user (silent skip)
                val existingTrustedDevice = userTrustedDeviceDao.getTrustedDevice(userEntity.id, deviceId)
                if (existingTrustedDevice != null) {
                    logger.info("Public device verification: device already trusted for user ${userEntity.id} (silent skip)")
                    return@either
                }

                // 3. Check for PENDING security audit record for this user/device
                // This ensures a valid login attempt was recently made from this device
                val pendingAudits = securityAuditDao.getUnacknowledgedByUserIdAndDeviceId(userEntity.id, deviceId)
                if (pendingAudits.isEmpty()) {
                    logger.info("Public device verification: no pending audit record for user ${userEntity.id}, device $deviceId (silent skip)")
                    return@either
                }

                // 4. Check user has an email address
                val email = userEntity.email
                if (email.isNullOrBlank()) {
                    logger.warn("User ${userEntity.id} has no email address on file")
                    raise(RequestDeviceVerificationError.UserHasNoEmail)
                }

                // 5. Check rate limit (1 hour = 3600000 ms)
                val rateLimitMillis = 60 * 60 * 1000L
                val lastTokenCreatedAt = deviceVerificationTokenDao.getLastTokenCreatedAt(userEntity.id, deviceId)
                if (lastTokenCreatedAt != null) {
                    val timeSinceLastToken = System.currentTimeMillis() - lastTokenCreatedAt
                    if (timeSinceLastToken < rateLimitMillis) {
                        val retryAfterMillis = rateLimitMillis - timeSinceLastToken
                        logger.warn("Rate limit exceeded for user: ${userEntity.id}, device: $deviceId")
                        raise(RequestDeviceVerificationError.RateLimitExceeded(retryAfterMillis))
                    }
                }

                // 6. Generate secure token (UUID)
                val token = java.util.UUID.randomUUID().toString()
                val currentTimeMillis = System.currentTimeMillis()
                val expiresAtMillis = currentTimeMillis + rateLimitMillis

                // 7. Send device verification email via notification service
                // Note: We fail the request if email sending fails
                securityNotificationService.sendDeviceVerification(
                    userEmail = email,
                    token = token
                ).mapLeft { mailError ->
                    logger.warn("Failed to send public device verification email: $mailError")
                    RequestDeviceVerificationError.NotificationServiceFailed
                }.bind()

                // 8. Store the token
                deviceVerificationTokenDao.createToken(
                    userId = userEntity.id,
                    deviceId = deviceId,
                    token = token,
                    expiresAt = expiresAtMillis,
                    createdAt = currentTimeMillis
                )

                logger.info("Successfully created public device verification token for user: ${userEntity.id}, device: $deviceId")
            }
        }

    override suspend fun verifyDeviceByToken(token: String): Either<VerifyDeviceError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Verifying device with token")

                // 1. Find the token
                val tokenEntity = deviceVerificationTokenDao.findToken(token)
                if (tokenEntity == null) {
                    logger.warn("Invalid verification token: $token")
                    raise(VerifyDeviceError.InvalidOrExpiredToken)
                }

                // 2. Check token is not expired
                val currentTimeMillis = System.currentTimeMillis()
                if (tokenEntity.expiresAt.toEpochMilliseconds() < currentTimeMillis) {
                    logger.warn("Expired verification token: $token")
                    // Clean up expired token
                    deviceVerificationTokenDao.deleteToken(token)
                    raise(VerifyDeviceError.InvalidOrExpiredToken)
                }

                val userId = tokenEntity.userId
                val deviceId = tokenEntity.deviceId

                // 3. Add device to trusted devices if not already there
                val existingDevice = userTrustedDeviceDao.getTrustedDevice(userId, deviceId)
                if (existingDevice == null) {
                    userTrustedDeviceDao.insertTrustedDevice(
                        userId = userId,
                        deviceId = deviceId,
                        ipAddress = null, // IP not available from email link
                        firstSeenAt = currentTimeMillis,
                        lastUsedAt = currentTimeMillis
                    )
                    logger.info("Added device $deviceId to trusted devices for user $userId")
                }

                // 4. Unrestrict all sessions for this user/device combination
                // This ensures existing restricted sessions become unrestricted immediately
                val unrestrictedCount = userSessionDao.unrestrictSessions(userId, deviceId)
                if (unrestrictedCount > 0) {
                    logger.info("Unrestricted $unrestrictedCount session(s) for device $deviceId")
                }

                // 5. Resolve any PENDING security alerts for this device as TRUSTED
                val pendingAlerts = securityAuditDao.getUnacknowledgedByUserIdAndDeviceId(userId, deviceId)

                for (alert in pendingAlerts) {
                    securityAuditDao.updateStatus(alert.id, SecurityAuditStatus.TRUSTED, currentTimeMillis)
                    logger.info("Resolved security alert ${alert.id} as TRUSTED for device $deviceId")
                }

                // 6. Delete the token (single-use)
                deviceVerificationTokenDao.deleteToken(token)

                logger.info("Successfully verified device $deviceId for user $userId via email link")
            }
        }
}
