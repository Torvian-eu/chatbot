package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.service.email.MailService
import eu.torvian.chatbot.server.service.email.error.MailError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [SecurityNotificationService] that sends security notifications via email.
 *
 * This implementation constructs verification links using the provided server URL
 * and delegates the actual email delivery to the configured [MailService].
 *
 * @property mailService The mail service to use for sending emails.
 * @property serverUrl The server URL used to construct absolute verification links.
 */
class SecurityNotificationServiceImpl(
    private val mailService: MailService,
    private val serverUrl: String
) : SecurityNotificationService {

    companion object {
        private val logger: Logger = LogManager.getLogger(SecurityNotificationServiceImpl::class.java)
    }

    override suspend fun sendDeviceVerification(userEmail: String, token: String): Either<MailError, Unit> {
        // Build the absolute verification link
        val verificationLink = "$serverUrl/api/v1/public/auth/verify-device?token=$token"

        // Format the email
        val subject = "Verify Your Device"
        val body = buildString {
            appendLine("Hello,")
            appendLine()
            appendLine("A new device is trying to access your account. If this was you, please verify your device by clicking the link below:")
            appendLine()
            appendLine(verificationLink)
            appendLine()
            appendLine("This link will expire in 1 hour.")
            appendLine()
            appendLine("If you did not attempt to log in, please ignore this email.")
        }

        logger.info("Sending device verification email to: $userEmail")

        return mailService.sendMail(
            to = userEmail,
            subject = subject,
            body = body
        )
    }
}
