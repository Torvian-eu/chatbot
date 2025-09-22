package eu.torvian.chatbot.common.security.error

/**
 * Enum representing different character types required in passwords.
 */
enum class CharacterType {
    UPPERCASE,
    LOWERCASE,
    DIGITS,
    SPECIAL_CHARACTERS
}

/**
 * Sealed class representing different password validation errors.
 *
 * This hierarchy provides type-safe error handling for password validation failures,
 * allowing clients to handle specific error cases appropriately.
 */
sealed class PasswordValidationError {

    /**
     * Password is too short according to minimum length requirements.
     *
     * @property minLength The minimum required password length
     * @property actualLength The actual length of the provided password
     */
    data class TooShort(val minLength: Int, val actualLength: Int) : PasswordValidationError()

    /**
     * Password is too long according to maximum length requirements.
     *
     * @property maxLength The maximum allowed password length
     * @property actualLength The actual length of the provided password
     */
    data class TooLong(val maxLength: Int, val actualLength: Int) : PasswordValidationError()

    /**
     * Password lacks required character types (uppercase, lowercase, digits, special characters).
     *
     * @property missingTypes List of missing character types
     */
    data class MissingCharacterTypes(val missingTypes: List<CharacterType>) : PasswordValidationError()

    /**
     * Password contains only whitespace characters.
     */
    data object OnlyWhitespace : PasswordValidationError()

    /**
     * Password is empty or blank.
     */
    data object Empty : PasswordValidationError()

    /**
     * Password is too common or appears in known weak password lists.
     *
     * @property reason Description of why the password is considered weak
     */
    data class TooCommon(val reason: String) : PasswordValidationError()
}
