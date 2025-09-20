package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.common.security.error.CharacterType

/**
 * Configuration for password validation rules.
 *
 * @param minLength Minimum required password length
 * @param maxLength Maximum allowed password length
 * @param requireLowercase Whether at least one lowercase letter is required
 * @param requireUppercase Whether at least one uppercase letter is required
 * @param requireDigit Whether at least one digit is required
 * @param requireSpecialChar Whether at least one special character is required
 * @param checkCommonPasswords Whether to check against common weak passwords
 */
data class PasswordValidationConfig(
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val requireLowercase: Boolean = true,
    val requireUppercase: Boolean = true,
    val requireDigit: Boolean = true,
    val requireSpecialChar: Boolean = true,
    val checkCommonPasswords: Boolean = true
) {
    companion object {
        const val DEFAULT_MIN_LENGTH = 8
        const val DEFAULT_MAX_LENGTH = 128
    }
}

/**
 * Configurable password validator that enforces password strength requirements.
 *
 * This validator can be customized with different validation rules and is designed
 * to be reused across different components of the application (server, UI, etc.).
 *
 * Default validation rules:
 * - Minimum length of 8 characters
 * - Maximum length of 128 characters
 * - At least one lowercase letter
 * - At least one uppercase letter
 * - At least one digit
 * - At least one special character
 * - Cannot be only whitespace
 * - Cannot be empty or blank
 * - Cannot be a common weak password
 */
class PasswordValidator(
    private val config: PasswordValidationConfig = PasswordValidationConfig()
) {

    companion object {
        // Character type regex patterns
        private val LOWERCASE_PATTERN = Regex("[a-z]")
        private val UPPERCASE_PATTERN = Regex("[A-Z]")
        private val DIGIT_PATTERN = Regex("[0-9]")
        private val SPECIAL_CHAR_PATTERN = Regex("[!@#\$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]")

        // Common weak passwords (base forms)
        private val COMMON_PASSWORDS = setOf(
            "password", "password123", "123456", "123456789", "qwerty",
            "abc123", "password1", "admin", "letmein", "welcome",
            "monkey", "dragon", "master", "shadow", "superman"
        )
    }

    /**
     * Validates password strength according to configured security policies.
     *
     * @param password The password to validate
     * @return Either [PasswordValidationError] if validation fails, or Unit if valid
     */
    fun validatePasswordStrength(password: String): Either<PasswordValidationError, Unit> {
        // Check for empty or blank password
        if (password.isEmpty()) {
            return PasswordValidationError.Empty.left()
        }

        if (password.isBlank()) {
            return PasswordValidationError.OnlyWhitespace.left()
        }

        // Check length requirements
        if (password.length < config.minLength) {
            return PasswordValidationError.TooShort(config.minLength, password.length).left()
        }

        if (password.length > config.maxLength) {
            return PasswordValidationError.TooLong(config.maxLength, password.length).left()
        }

        // Check character type requirements
        val missingTypes = mutableListOf<CharacterType>()

        if (config.requireLowercase && !LOWERCASE_PATTERN.containsMatchIn(password)) {
            missingTypes.add(CharacterType.LOWERCASE)
        }

        if (config.requireUppercase && !UPPERCASE_PATTERN.containsMatchIn(password)) {
            missingTypes.add(CharacterType.UPPERCASE)
        }

        if (config.requireDigit && !DIGIT_PATTERN.containsMatchIn(password)) {
            missingTypes.add(CharacterType.DIGITS)
        }

        if (config.requireSpecialChar && !SPECIAL_CHAR_PATTERN.containsMatchIn(password)) {
            missingTypes.add(CharacterType.SPECIAL_CHARACTERS)
        }

        if (missingTypes.isNotEmpty()) {
            return PasswordValidationError.MissingCharacterTypes(missingTypes).left()
        }

        // Check for common weak passwords
        if (config.checkCommonPasswords && isCommonWeakPassword(password)) {
            return PasswordValidationError.TooCommon("Password is too common or predictable").left()
        }

        return Unit.right()
    }

    /**
     * Checks if the password is a commonly used weak password.
     *
     * This implementation checks for common patterns and known weak passwords.
     * In a production system, this could be enhanced with a comprehensive
     * dictionary of known weak passwords.
     */
    private fun isCommonWeakPassword(password: String): Boolean {
        val lowercasePassword = password.lowercase()

        // Check if password contains any common password as a base
        for (commonPassword in COMMON_PASSWORDS) {
            if (lowercasePassword.contains(commonPassword)) {
                return true
            }
        }

        // Sequential patterns
        if (lowercasePassword.contains("123456") ||
            lowercasePassword.contains("abcdef") ||
            lowercasePassword.contains("qwerty")) {
            return true
        }

        // Repeated characters (more than 3 consecutive)
        if (Regex("(.)\\1{3,}").containsMatchIn(password)) {
            return true
        }

        return false
    }
}
