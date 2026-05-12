package eu.torvian.chatbot.server.service.email

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.email.error.MailError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * A mail service implementation that sends emails via SMTP.
 *
 * This implementation retrieves SMTP configuration from a properties map,
 * allowing for flexible configuration without hardcoding specific properties.
 *
 * @property fromAddress The default sender address.
 * @property properties A map containing SMTP configuration:
 *   - "host": The SMTP server hostname (required)
 *   - "port": The SMTP server port (required)
 *   - "user": The username for authentication (required)
 *   - "password": The password for authentication (required)
 *   - "ssl": Whether to use SSL (optional, default: false)
 */
class SmtpMailService(
    private val fromAddress: String,
    private val properties: Map<String, String>
) : MailService {

    companion object {
        private val logger: Logger = LogManager.getLogger(SmtpMailService::class.java)
    }

    override suspend fun sendMail(to: String, subject: String, body: String): Either<MailError, Unit> {
        // Validate required configuration
        val host = properties["host"] ?: return MailError.ConfigurationMissing("host").left()
        val port = properties["port"] ?: return MailError.ConfigurationMissing("port").left()
        val user = properties["user"] ?: return MailError.ConfigurationMissing("user").left()
        val password = properties["password"] ?: return MailError.ConfigurationMissing("password").left()

        // TODO: Implement actual SMTP sending logic
        // This is a placeholder that logs the intended action
        logger.info("=== EMAIL (SMTP) ===")
        logger.info("From: $fromAddress")
        logger.info("To: $to")
        logger.info("Subject: $subject")
        logger.info("Host: $host, Port: $port, User: $user")
        logger.info("Body: $body")
        logger.info("====================")

        return Unit.right()
    }
}
