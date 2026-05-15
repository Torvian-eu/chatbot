package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity
import eu.torvian.chatbot.server.service.security.error.GetSecurityAlertsError
import eu.torvian.chatbot.server.service.security.error.ResolveAlertError

/**
 * Service for security audit and alert management.
 *
 * Handles security alerts related to device-based login events,
 * alert acknowledgment, and device trust resolution.
 */
interface SecurityAuditService {
    /**
     * Retrieves unacknowledged security alerts for a user.
     *
     * Returns detailed information about unrecognized device logins that have not been
     * acknowledged by the user yet. These are sourced from the SecurityAuditDao.
     *
     * Restricted sessions cannot list security alerts - this prevents access to
     * security-sensitive information from untrusted devices.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return A right-biased [Either] containing the list of unacknowledged security alerts,
     *         or [GetSecurityAlertsError.InsufficientPermissions] if the requester is restricted.
     */
    suspend fun getSecurityAlerts(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<GetSecurityAlertsError, List<SecurityAuditEntity>>

    /**
     * Resolves a single security alert with the specified outcome.
     *
     * This method allows the user to either trust or dismiss a specific security alert.
     * - TRUSTED: The device is added to the trusted devices list and the alert is marked as trusted.
     * - DISMISSED: The alert is marked as dismissed without adding the device to trusted devices.
     *
     * Restricted sessions cannot resolve alerts - this prevents self-resolution of untrusted devices.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param alertId The unique identifier of the security alert to resolve.
     * @param outcome The outcome to apply (TRUSTED or DISMISSED).
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [ResolveAlertError] if resolution fails, or Unit on success
     */
    suspend fun resolveSingleAlert(
        userId: Long,
        alertId: Long,
        outcome: SecurityAuditStatus,
        requesterIsRestricted: Boolean
    ): Either<ResolveAlertError, Unit>
}

