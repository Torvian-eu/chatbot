package eu.torvian.chatbot.app.service.auth

import eu.torvian.chatbot.common.security.*
import eu.torvian.chatbot.common.security.error.CharacterType
import eu.torvian.chatbot.common.security.error.PasswordValidationError

/**
 * Default implementation of [AuthValidationService].
 *
 * Uses [PasswordValidator] and [UsernameValidator] internally, delegating
 * to the rules defined in the provided [AccountValidationPolicy].
 *
 * @param policy The validation policy containing password and username configuration
 */
class DefaultAuthValidationService(
    private var policy: AccountValidationPolicy = AccountValidationPolicy()
) : AuthValidationService {

    private val passwordValidator: PasswordValidator
        get() = PasswordValidator(policy.passwordConfig)

    private val usernameValidator: UsernameValidator
        get() = UsernameValidator(policy.usernameConfig)

    override val passwordValidationConfig: PasswordValidationConfig
        get() = policy.passwordConfig

    override val usernameValidationConfig: UsernameValidationConfig
        get() = policy.usernameConfig

    override fun validateUsername(username: String): String? {
        return usernameValidator.validate(username)
    }

    override fun validateEmail(email: String): String? {
        return when {
            email.isBlank() -> null // Email is optional
            !EMAIL_REGEX.matches(email) -> "Please enter a valid email address"
            else -> null
        }
    }

    override fun validatePassword(password: String): String? {
        return passwordValidator.validatePasswordStrength(password).fold(
            ifLeft = { error -> mapPasswordValidationError(error) },
            ifRight = { null }
        )
    }

    override fun validateConfirmPassword(password: String, confirmPassword: String): String? {
        return when {
            confirmPassword.isBlank() -> "Please confirm your password"
            password != confirmPassword -> "Passwords do not match"
            else -> null
        }
    }

    /**
     * Maps [PasswordValidationError] to user-friendly error messages.
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

    override fun updatePolicy(newPolicy: AccountValidationPolicy) {
        policy = newPolicy
    }

    companion object {
        private val EMAIL_REGEX = Regex(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )
    }
}
