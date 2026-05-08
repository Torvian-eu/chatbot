package eu.torvian.chatbot.app.service.auth

import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig

/**
 * Service interface for validating authentication form fields.
 *
 * Provides username, email, password, and password confirmation validation
 * with user-friendly error messages derived from the configured policy.
 */
interface AuthValidationService {

    /**
     * Validates a username field.
     *
     * @param username The username to validate
     * @return `null` if valid, or an error message string if invalid
     */
    fun validateUsername(username: String): String?

    /**
     * Validates an email field (optional field).
     *
     * @param email The email to validate
     * @return `null` if valid or blank (optional), or an error message string if invalid
     */
    fun validateEmail(email: String): String?

    /**
     * Validates a password field for strength requirements.
     *
     * @param password The password to validate
     * @return `null` if valid, or an error message string if invalid
     */
    fun validatePassword(password: String): String?

    /**
     * Validates password confirmation against the original password.
     *
     * @param password The original password
     * @param confirmPassword The confirmation password
     * @return `null` if valid, or an error message string if invalid
     */
    fun validateConfirmPassword(password: String, confirmPassword: String): String?

    /**
     * The password validation configuration used by this service.
     * Exposed so that UI components can dynamically render requirement hints.
     */
    val passwordValidationConfig: PasswordValidationConfig

    /**
     * The username validation configuration used by this service.
     * Exposed so that UI components can dynamically render requirement hints.
     */
    val usernameValidationConfig: UsernameValidationConfig

    /**
     * Updates the validation policy from the server.
     *
     * This method is thread-safe and can be called at any time to refresh
     * the validation rules (e.g., after fetching from the server).
     *
     * @param newPolicy The new account validation policy to apply
     */
    fun updatePolicy(newPolicy: AccountValidationPolicy)
}
