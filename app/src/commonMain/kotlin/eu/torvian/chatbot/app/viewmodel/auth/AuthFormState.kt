package eu.torvian.chatbot.app.viewmodel.auth

import kotlinx.serialization.Serializable

/**
 * Represents the state of the login form.
 */
@Serializable
data class LoginFormState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val generalError: String? = null
) {
    val isValid: Boolean
        get() = username.isNotBlank() &&
                password.isNotBlank() &&
                usernameError == null &&
                passwordError == null

    val hasErrors: Boolean
        get() = usernameError != null || passwordError != null || generalError != null
}

/**
 * Represents the state of the registration form.
 */
@Serializable
data class RegisterFormState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val usernameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val generalError: String? = null
) {
    val isValid: Boolean
        get() = username.isNotBlank() &&
                password.isNotBlank() &&
                password == confirmPassword &&
                usernameError == null &&
                emailError == null &&
                passwordError == null &&
                confirmPasswordError == null

    val hasErrors: Boolean
        get() = usernameError != null ||
                emailError != null ||
                passwordError != null ||
                confirmPasswordError != null ||
                generalError != null
}
