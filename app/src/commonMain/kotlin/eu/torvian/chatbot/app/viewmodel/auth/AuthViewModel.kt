package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.auth.LoginRequest
import eu.torvian.chatbot.common.models.auth.RegisterRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing authentication UI state and operations.
 *
 * This ViewModel handles:
 * - Authentication state management (delegated to AuthRepository)
 * - Login and registration form state
 * - Form validation and error handling
 * - User authentication operations
 *
 * @param authRepository Repository for authentication operations
 * @param errorNotifier Service for handling and notifying about errors
 * @param normalScope Coroutine scope for normal operations
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val errorNotifier: ErrorNotifier,
    private val normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<AuthViewModel>()
    }

    // --- Authentication State (delegated to repository) ---

    /**
     * The current authentication state from the repository.
     */
    val authState: StateFlow<AuthState> = authRepository.authState

    // --- Form State Management ---

    private val _loginFormState = MutableStateFlow(LoginFormState())
    val loginFormState: StateFlow<LoginFormState> = _loginFormState.asStateFlow()

    private val _registerFormState = MutableStateFlow(RegisterFormState())
    val registerFormState: StateFlow<RegisterFormState> = _registerFormState.asStateFlow()

    // --- Authentication Operations ---

    /**
     * Attempts to log in the user using the current login form state.
     */
    fun login() {
        viewModelScope.launch {
            val currentForm = _loginFormState.value
            val username = currentForm.username
            val password = currentForm.password

            logger.info("Attempting login for user: $username")

            // Validate form before submission
            val usernameError = AuthFormValidation.validateUsername(username)
            val passwordError = if (password.isBlank()) "Password is required" else null

            if (usernameError != null || passwordError != null) {
                _loginFormState.update { currentState ->
                    currentState.copy(
                        usernameError = usernameError,
                        passwordError = passwordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _loginFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    usernameError = null,
                    passwordError = null,
                    generalError = null
                )
            }

            // Perform login
            val result = authRepository.login(LoginRequest(username, password))

            result.fold(
                ifLeft = { error ->
                    logger.warn("Login failed for user $username: ${error.message}")
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapLoginError(error)
                        )
                    }
                },
                ifRight = {
                    logger.info("Login successful for user: $username")
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null
                        )
                    }
                    // Clear form on successful login
                    clearLoginForm()
                }
            )
        }
    }

    /**
     * Attempts to register a new user using the current registration form state.
     */
    fun register() {
        viewModelScope.launch {
            val currentForm = _registerFormState.value
            val username = currentForm.username
            val email = currentForm.email
            val password = currentForm.password
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting registration for user: $username")

            // Validate all form fields
            val usernameError = AuthFormValidation.validateUsername(username)
            val emailError = AuthFormValidation.validateEmail(email)
            val passwordError = AuthFormValidation.validatePassword(password)
            val confirmPasswordError = AuthFormValidation.validateConfirmPassword(password, confirmPassword)

            if (usernameError != null || emailError != null || passwordError != null || confirmPasswordError != null) {
                _registerFormState.update { currentState ->
                    currentState.copy(
                        usernameError = usernameError,
                        emailError = emailError,
                        passwordError = passwordError,
                        confirmPasswordError = confirmPasswordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _registerFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    usernameError = null,
                    emailError = null,
                    passwordError = null,
                    confirmPasswordError = null,
                    generalError = null,
                    registrationSuccessEvent = false
                )
            }

            // Perform registration
            val emailToSend = email.ifBlank { null }
            val result = authRepository.register(RegisterRequest(username, password, emailToSend))

            result.fold(
                ifLeft = { error ->
                    logger.warn("Registration failed for user $username: ${error.message}")
                    _registerFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapRegistrationError(error)
                        )
                    }
                },
                ifRight = { user ->
                    logger.info("Registration successful for user: ${user.username}")
                    _registerFormState.value = RegisterFormState(
                        registrationSuccessEvent = true
                    )
                }
            )
        }
    }

    /**
     * Logs out the current user.
     */
    fun logout() {
        viewModelScope.launch {
            logger.info("Attempting logout")

            val result = authRepository.logout()
            result.fold(
                ifLeft = { error ->
                    logger.warn("Logout failed: ${error.message}")
                    errorNotifier.repositoryError(
                        error = error,
                        shortMessage = "Logout failed"
                    )
                },
                ifRight = {
                    logger.info("Logout successful")
                    clearAllForms()
                }
            )
        }
    }

    /**
     * Checks the initial authentication state on app startup.
     */
    suspend fun checkInitialAuthState() {
        logger.info("Checking initial authentication state")
        authRepository.checkInitialAuthState()
    }

    // --- Form State Updates ---

    /**
     * Updates the login form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updateLoginForm(username: String? = null, password: String? = null) {
        _loginFormState.update { currentState ->
            currentState.copy(
                username = username ?: currentState.username,
                password = password ?: currentState.password,
                // Clear field-specific errors when user types
                usernameError = if (username != null && username != currentState.username) null else currentState.usernameError,
                passwordError = if (password != null && password != currentState.password) null else currentState.passwordError
            )
        }
    }

    /**
     * Updates the registration form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updateRegisterForm(
        username: String? = null,
        email: String? = null,
        password: String? = null,
        confirmPassword: String? = null
    ) {
        _registerFormState.update { currentState ->
            currentState.copy(
                username = username ?: currentState.username,
                email = email ?: currentState.email,
                password = password ?: currentState.password,
                confirmPassword = confirmPassword ?: currentState.confirmPassword,
                // Clear field-specific errors when user types
                usernameError = if (username != null && username != currentState.username) null else currentState.usernameError,
                emailError = if (email != null && email != currentState.email) null else currentState.emailError,
                passwordError = if (password != null && password != currentState.password) null else currentState.passwordError,
                confirmPasswordError = if (confirmPassword != null && confirmPassword != currentState.confirmPassword) null else currentState.confirmPasswordError
            )
        }
    }

    /**
     * Resets the registration form state, typically called by the UI after
     * successful navigation away from the registration success screen.
     */
    fun acknowledgeRegistrationSuccess() {
        _registerFormState.value = RegisterFormState()
    }

    /**
     * Clears the login form state.
     */
    fun clearLoginForm() {
        _loginFormState.value = LoginFormState()
    }

    /**
     * Clears the registration form state.
     */
    fun clearRegisterForm() {
        _registerFormState.value = RegisterFormState()
    }

    /**
     * Clears all form states.
     */
    fun clearAllForms() {
        clearLoginForm()
        clearRegisterForm()
    }

    // --- Error Mapping ---

    private fun mapLoginError(error: RepositoryError): String {
        return when (error) {
            is RepositoryError.DataFetchError -> when {
                error.message.contains("Invalid credentials", ignoreCase = true) ->
                    "Invalid username or password"

                error.message.contains("User not found", ignoreCase = true) ->
                    "Invalid username or password"

                error.message.contains("Account locked", ignoreCase = true) ->
                    "Account is temporarily locked. Please try again later."

                else -> "Login failed. Please try again."
            }

            is RepositoryError.OtherError ->
                "An unexpected error occurred. Please try again."
        }
    }

    private fun mapRegistrationError(error: RepositoryError): String {
        return when (error) {
            is RepositoryError.DataFetchError -> when {
                error.message.contains("Username already exists", ignoreCase = true) ->
                    "Username is already taken. Please choose a different one."

                error.message.contains("Email already exists", ignoreCase = true) ->
                    "Email is already registered. Please use a different email or try logging in."

                else -> "Registration failed. Please try again."
            }

            is RepositoryError.OtherError ->
                "An unexpected error occurred. Please try again."
        }
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
