package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.raise.*
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.dao.SecurityAuditDao
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.UserTrustedDeviceDao
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity
import eu.torvian.chatbot.server.service.security.error.GetSecurityAlertsError
import eu.torvian.chatbot.server.service.security.error.ResolveAlertError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [SecurityAuditService] for security audit management.
 *
 * Handles security alerts related to device-based login events and
 * alert resolution with device trust decisions.
 */
class SecurityAuditServiceImpl(
    private val securityAuditDao: SecurityAuditDao,
    private val userTrustedDeviceDao: UserTrustedDeviceDao,
    private val userSessionDao: UserSessionDao,
    private val transactionScope: TransactionScope
) : SecurityAuditService {

    companion object {
        private val logger: Logger = LogManager.getLogger(SecurityAuditServiceImpl::class.java)
    }

    override suspend fun getSecurityAlerts(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<GetSecurityAlertsError, List<SecurityAuditEntity>> =
        transactionScope.transaction {
            either {
                // Restricted sessions cannot list security alerts
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to list security alerts for user: $userId")
                    raise(GetSecurityAlertsError.InsufficientPermissions())
                }
                securityAuditDao.getUnacknowledgedByUserId(userId)
            }
        }

    override suspend fun resolveSingleAlert(
        userId: Long,
        alertId: Long,
        outcome: SecurityAuditStatus,
        requesterIsRestricted: Boolean
    ): Either<ResolveAlertError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Resolving security alert: $alertId for user: $userId with outcome: $outcome")

                // 1. Check restriction - restricted sessions cannot resolve alerts
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to resolve security alert: $alertId")
                    ResolveAlertError.InsufficientPermissions()
                }

                // 2. Fetch the alert record
                val auditRecord = securityAuditDao.getAuditRecordById(alertId)
                    ?: raise(ResolveAlertError.AlertNotFound(alertId))

                // 3. Ensure the alert belongs to the user
                ensure(auditRecord.userId == userId) {
                    logger.warn("Alert $alertId does not belong to user $userId")
                    ResolveAlertError.AlertNotFound(alertId)
                }

                // 4. Ensure the alert is still pending
                ensure(auditRecord.status == SecurityAuditStatus.PENDING) {
                    logger.warn("Alert $alertId is not pending (status: ${auditRecord.status})")
                    // Not an error - just return success since the alert is already resolved
                    return@transaction Unit
                }

                val currentTimeMillis = System.currentTimeMillis()

                // 5. Handle the outcome
                when (outcome) {
                    SecurityAuditStatus.TRUSTED -> {
                        // Add device to trusted devices if not already there
                        val existingDevice = userTrustedDeviceDao.getTrustedDevice(userId, auditRecord.deviceId)
                        if (existingDevice == null) {
                            userTrustedDeviceDao.insertTrustedDevice(
                                userId = userId,
                                deviceId = auditRecord.deviceId,
                                ipAddress = auditRecord.ipAddress,
                                firstSeenAt = currentTimeMillis,
                                lastUsedAt = currentTimeMillis
                            )
                        }
                        // Unrestrict all sessions for this device - this unlocks any restricted sessions
                        val unrestrictedCount = userSessionDao.unrestrictSessions(userId, auditRecord.deviceId)
                        if (unrestrictedCount > 0) {
                            logger.info("Unrestricted $unrestrictedCount session(s) for device ${auditRecord.deviceId}")
                        }
                        // Mark the alert as trusted
                        securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, currentTimeMillis)
                    }

                    SecurityAuditStatus.DISMISSED -> {
                        // Mark the alert as dismissed - do NOT add to trusted devices
                        securityAuditDao.updateStatus(alertId, SecurityAuditStatus.DISMISSED, currentTimeMillis)
                    }

                    SecurityAuditStatus.PENDING -> {
                        // No-op - should not happen as we check for PENDING above
                    }
                }

                logger.info("Successfully resolved security alert: $alertId for user: $userId with outcome: $outcome")
            }
        }
}


