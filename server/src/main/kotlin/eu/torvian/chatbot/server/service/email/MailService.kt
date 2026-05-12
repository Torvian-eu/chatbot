package eu.torvian.chatbot.server.service.email

import arrow.core.Either
import eu.torvian.chatbot.server.service.email.error.MailError

/**
 * Service interface for sending email messages.
 *
 * This is a pluggable transport layer that supports different email providers
 * (e.g., logging, SMTP) through a flexible configuration system.
 */
interface MailService {
    /**
     * Sends an email message to the specified recipient.
     *
     * @param to The recipient's email address.
     * @param subject The email subject line.
     * @param body The email body content (plain text or HTML).
     * @return Either [MailError] if sending fails, or Unit on success.
     */
    suspend fun sendMail(to: String, subject: String, body: String): Either<MailError, Unit>
}
