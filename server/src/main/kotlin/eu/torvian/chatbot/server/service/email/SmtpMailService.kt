package eu.torvian.chatbot.server.service.email

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.email.error.MailError
import jakarta.mail.Authenticator
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * A mail service implementation that sends emails via SMTP using Jakarta Mail (Angus Mail).
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
 *   - "starttls": Boolean ("true"/"false") to enable STARTTLS (optional, default: true)
 *   - "auth": Boolean ("true"/"false") to enable authentication (optional, default: true)
 *   - "debug": Boolean ("true"/"false") to enable protocol debug output (optional, default: false)
 *   - Any key starting with "mail." is passed through as a Jakarta Mail property (e.g., "mail.smtp.ssl.trust")
 */
class SmtpMailService(
    private val fromAddress: String,
    private val properties: Map<String, String>
) : MailService {

    companion object {
        private val logger: Logger = LogManager.getLogger(SmtpMailService::class.java)
    }

    override suspend fun sendMail(to: String, subject: String, body: String): Either<MailError, Unit> = withContext(Dispatchers.IO) {
        // Validate required configuration
        val host = properties["host"] ?: return@withContext MailError.ConfigurationMissing("host").left()
        val port = properties["port"]?.toIntOrNull() ?: return@withContext MailError.ConfigurationMissing("port").left()
        val user = properties["user"] ?: return@withContext MailError.ConfigurationMissing("user").left()
        val password = properties["password"] ?: return@withContext MailError.ConfigurationMissing("password").left()

        // Build JavaMail properties
        val mailProperties = java.util.Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", properties["auth"]?.toBooleanStrictOrNull()?.toString() ?: "true")
            put("mail.smtp.starttls.enable", properties["starttls"]?.toBooleanStrictOrNull()?.toString() ?: "true")
            // Set modern TLS protocols by default for handshake compatibility
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            // Pass-through any property starting with "mail."
            properties.filterKeys { it.startsWith("mail.", true) }.forEach { (key, value) ->
                put(key, value)
            }
        }

        // Create session with authenticator
        val session = Session.getInstance(mailProperties, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(user, password)
            }
        })

        // Enable debug mode if configured
        if (properties["debug"]?.toBooleanStrictOrNull() == true) {
            session.setDebug(true)
        }

        try {
            // Create and configure the message
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromAddress))
                setRecipient(Message.RecipientType.TO, InternetAddress(to))
                this.subject = subject
                setText(body)
            }

            // Send the message
            Transport.send(message)

            logger.info("Successfully sent email to $to via SMTP $host:$port")
            Unit.right()
        } catch (e: MessagingException) {
            // Map MessagingException to domain errors using structured type checks
            val error = when {
                // Authentication failures are indicated by AuthenticationFailedException
                e is AuthenticationFailedException -> MailError.AuthenticationFailed

                // Server rejections (invalid addresses, policy violations) use SendFailedException
                e is SendFailedException -> {
                    // Check for invalid or unsent addresses to provide specific reason
                    val reason = when {
                        e.invalidAddresses != null && e.invalidAddresses.isNotEmpty() ->
                            "Invalid addresses: ${e.invalidAddresses.joinToString { it.toString() }}"
                        e.validUnsentAddresses != null && e.validUnsentAddresses.isNotEmpty() ->
                            "Valid but unsent addresses: ${e.validUnsentAddresses.joinToString { it.toString() }}"
                        else -> e.message ?: "Message rejected by server"
                    }
                    MailError.Rejected(reason)
                }

                // Network issues are indicated by specific exception types in the cause chain
                e.cause is IOException ||
                e.cause is SocketException ||
                e.cause is UnknownHostException ->
                    MailError.NetworkError(e.message ?: "Network error")

                // Fallback: check for authentication codes in message
                e.message?.contains("535", ignoreCase = true) == true ->
                    MailError.AuthenticationFailed

                // Default to network error for other cases
                else ->
                    MailError.NetworkError(e.message ?: "Unknown SMTP error")
            }
            logger.warn("Failed to send email to $to: ${e.message}")
            error.left()
        }
    }
}
