package eu.torvian.chatbot.server.service.core.error.auth

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing logical errors that can occur while an administrator creates a user account.
 */
sealed interface CreateUserError {
    /**
     * Username already exists in the system.
     *
     * @property username The username that already exists
     */
    data class UsernameAlreadyExists(val username: String) : CreateUserError

    /**
     * Email address already exists in the system.
     *
     * @property email The email that already exists
     */
    data class EmailAlreadyExists(val email: String) : CreateUserError

    /**
     * Invalid input provided for account creation.
     *
     * @property reason Description of what input was invalid
     */
    data class InvalidInput(val reason: String) : CreateUserError

    /**
     * Initial password does not meet strength requirements.
     *
     * @property reason Description of why the password is too weak
     */
    data class PasswordTooWeak(val reason: String) : CreateUserError

    /**
     * Post-creation provisioning (default group membership or per-user tool seeding) failed.
     *
     * @property reason Description of the failure
     */
    data class ProvisioningFailed(val reason: String) : CreateUserError
}

/**
 * Extension function to convert [CreateUserError] to [ApiError] for HTTP responses.
 */
fun CreateUserError.toApiError(): ApiError = when (this) {
    is CreateUserError.UsernameAlreadyExists ->
        apiError(
            CommonApiErrorCodes.ALREADY_EXISTS,
            "Username already exists",
            "field" to "username",
            "username" to username
        )

    is CreateUserError.EmailAlreadyExists ->
        apiError(
            CommonApiErrorCodes.ALREADY_EXISTS,
            "Email already exists",
            "field" to "email",
            "email" to email
        )

    is CreateUserError.InvalidInput ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input", "reason" to reason)

    is CreateUserError.PasswordTooWeak ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Password too weak", "reason" to reason)

    is CreateUserError.ProvisioningFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to initialize user account", "reason" to reason)
}
