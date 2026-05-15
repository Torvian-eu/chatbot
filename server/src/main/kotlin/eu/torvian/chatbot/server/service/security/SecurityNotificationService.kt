package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.service.email.error.MailError

/**
 * Service interface for sending security-related notifications.
 *
 * This service handles the construction and delivery of security notifications,
 * such as device verification emails, using the configured mail service.
 */
interface SecurityNotificationService {
    /**
     * Sends a device verification email to the specified user.
     *
     * This method constructs the verification link using the server URL
     * and sends an email with the verification instructions.
     *
     * @param userEmail The recipient's email address.
     * @param token The verification token to include in the link.
     * @return Either [MailError] if sending fails, or Unit on success.
     */
    suspend fun sendDeviceVerification(userEmail: String, token: String): Either<MailError, Unit>
}
