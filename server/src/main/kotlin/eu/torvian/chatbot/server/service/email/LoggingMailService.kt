package eu.torvian.chatbot.server.service.email

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.server.service.email.error.MailError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * A mail service implementation that logs email content instead of sending it.
 *
 * This is useful for development and testing environments where actual email
 * delivery is not required.
 *
 * @property fromAddress The sender address to include in log output.
 */
class LoggingMailService(
    private val fromAddress: String
) : MailService {

    companion object {
        private val logger: Logger = LogManager.getLogger(LoggingMailService::class.java)
    }

    override suspend fun sendMail(to: String, subject: String, body: String): Either<MailError, Unit> {
        logger.info("=== EMAIL (LOGGING) ===")
        logger.info("From: $fromAddress")
        logger.info("To: $to")
        logger.info("Subject: $subject")
        logger.info("Body:")
        logger.info(body)
        logger.info("========================")
        return Unit.right()
    }
}
