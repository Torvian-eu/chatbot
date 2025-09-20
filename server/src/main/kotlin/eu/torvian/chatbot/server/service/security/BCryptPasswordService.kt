package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.PasswordValidator
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.mindrot.jbcrypt.BCrypt

/**
 * BCrypt implementation of [PasswordService].
 *
 * This implementation uses the BCrypt hashing algorithm with configurable salt rounds
 * for secure password storage and verification. BCrypt is designed to be slow and
 * computationally expensive to resist brute-force attacks.
 *
 * Password strength validation is delegated to a [PasswordValidator] instance from the common module.
 */
class BCryptPasswordService(
    private val passwordValidator: PasswordValidator = PasswordValidator()
) : PasswordService {

    companion object {
        private val logger: Logger = LogManager.getLogger(BCryptPasswordService::class.java)

        // BCrypt configuration
        private const val SALT_ROUNDS = 12
    }

    override fun hashPassword(plainPassword: String): String {
        logger.debug("Hashing password with BCrypt (salt rounds: $SALT_ROUNDS)")
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(SALT_ROUNDS))
    }

    override fun verifyPassword(plainPassword: String, hashedPassword: String): Boolean {
        logger.debug("Verifying password against BCrypt hash")
        return try {
            BCrypt.checkpw(plainPassword, hashedPassword)
        } catch (e: Exception) {
            logger.warn("Password verification failed due to exception", e)
            false
        }
    }

    override fun validatePasswordStrength(password: String): Either<PasswordValidationError, Unit> {
        return passwordValidator.validatePasswordStrength(password)
    }
}
