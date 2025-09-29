package eu.torvian.chatbot.app.viewmodel.auth

import eu.torvian.chatbot.common.security.PasswordValidator
import eu.torvian.chatbot.common.security.error.CharacterType
import eu.torvian.chatbot.common.security.error.PasswordValidationError

/**
 * Validation utilities for authentication forms.
 */
object AuthFormValidation {

    private const val MIN_USERNAME_LENGTH = 3
    private const val MAX_USERNAME_LENGTH = 50

    private val EMAIL_REGEX = Regex(
        "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    )

    private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_-]+$")

    private val passwordValidator = PasswordValidator()

    /**
     * Validates a username field.
     */
    fun validateUsername(username: String): String? {
        return when {
            username.isBlank() -> "Username is required"
            username.length < MIN_USERNAME_LENGTH ->
                "Username must be at least $MIN_USERNAME_LENGTH characters"
            username.length > MAX_USERNAME_LENGTH ->
                "Username must be no more than $MAX_USERNAME_LENGTH characters"
            !USERNAME_REGEX.matches(username) ->
                "Username can only contain letters, numbers, hyphens, and underscores"
            else -> null
        }
    }

    /**
     * Validates an email field (optional field).
     */
    fun validateEmail(email: String): String? {
        return when {
            email.isBlank() -> null // Email is optional
            !EMAIL_REGEX.matches(email) -> "Please enter a valid email address"
            else -> null
        }
    }

    /**
     * Validates a password field using the shared PasswordValidator.
     */
    fun validatePassword(password: String): String? {
        return passwordValidator.validatePasswordStrength(password).fold(
            ifLeft = { error -> mapPasswordValidationError(error) },
            ifRight = { null }
        )
    }

    /**
     * Validates password confirmation.
     */
    fun validateConfirmPassword(password: String, confirmPassword: String): String? {
        return when {
            confirmPassword.isBlank() -> "Please confirm your password"
            password != confirmPassword -> "Passwords do not match"
            else -> null
        }
    }

    /**
     * Maps PasswordValidationError to user-friendly error messages.
     */
    private fun mapPasswordValidationError(error: PasswordValidationError): String {
        return when (error) {
            is PasswordValidationError.Empty -> "Password is required"
            is PasswordValidationError.OnlyWhitespace -> "Password cannot contain only spaces"
            is PasswordValidationError.TooShort ->
                "Password must be at least ${error.minLength} characters"
            is PasswordValidationError.TooLong ->
                "Password must be no more than ${error.maxLength} characters"
            is PasswordValidationError.MissingCharacterTypes ->
                buildMissingCharacterTypesMessage(error.missingTypes)
            is PasswordValidationError.TooCommon ->
                "Password is too common. Please choose a more unique password"
        }
    }

    /**
     * Builds a user-friendly message for missing character types.
     */
    private fun buildMissingCharacterTypesMessage(missingTypes: List<CharacterType>): String {
        val requirements = missingTypes.map { type ->
            when (type) {
                CharacterType.LOWERCASE -> "lowercase letters"
                CharacterType.UPPERCASE -> "uppercase letters"
                CharacterType.DIGITS -> "numbers"
                CharacterType.SPECIAL_CHARACTERS -> "special characters"
            }
        }

        return when (requirements.size) {
            1 -> "Password must contain ${requirements[0]}"
            2 -> "Password must contain ${requirements[0]} and ${requirements[1]}"
            else -> "Password must contain ${requirements.dropLast(1).joinToString(", ")} and ${requirements.last()}"
        }
    }
}
