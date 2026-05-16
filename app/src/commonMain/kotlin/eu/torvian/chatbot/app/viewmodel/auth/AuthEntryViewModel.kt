package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.matches
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * ViewModel for managing authentication entry flows (login, registration).
 *
 * This ViewModel handles:
 * - Login form state and validation
 * - Registration form state and validation
 * - Form submission and error handling
 *
 * @param authRepository Repository for authentication operations.
 * @param notificationService Service for handling and notifying about errors.
 * @param normalScope Coroutine scope for normal operations.
 * @param authValidationService Service for validating authentication form fields.
 */
class AuthEntryViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    private val normalScope: CoroutineScope,
    private val authValidationService: AuthValidationService
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<AuthEntryViewModel>()
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

    /**
     * The password validation configuration from the authentication service.
     * Exposed so that UI components can dynamically render password requirement hints.
     */
    val passwordValidationConfig: PasswordValidationConfig = authValidationService.passwordValidationConfig

    /**
     * The username validation configuration from the authentication service.
     * Exposed so that UI components can dynamically render username requirement hints.
     */
    val usernameValidationConfig: UsernameValidationConfig = authValidationService.usernameValidationConfig

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
            val usernameError = authValidationService.validateUsername(username)
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
                    generalError = null,
                    showVerificationTrigger = false,
                    verificationMessage = null,
                    isVerificationSuccess = false
                )
            }

            // Perform login
            val result = authRepository.login(username, password)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Login failed for user $username: ${error.message}")
                    if (error.matches(CommonApiErrorCodes.VERIFICATION_REQUIRED)) {
                        // Show verification trigger for public device verification
                        _loginFormState.update { currentState ->
                            currentState.copy(
                                isLoading = false,
                                showVerificationTrigger = true,
                                generalError = null
                            )
                        }
                    } else {
                        _loginFormState.update { currentState ->
                            currentState.copy(
                                isLoading = false,
                                generalError = error.mapLoginError()
                            )
                        }
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
            val usernameError = authValidationService.validateUsername(username)
            val emailError = authValidationService.validateEmail(email)
            val passwordError = authValidationService.validatePassword(password)
            val confirmPasswordError = authValidationService.validateConfirmPassword(password, confirmPassword)

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
            val result = authRepository.register(username, password, emailToSend)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Registration failed for user $username: ${error.message}")
                    _registerFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = error.mapRegistrationError()
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

    // --- Device Verification Operations ---

    /**
     * Requests a public device verification email for a new device.
     *
     * This method is used when login fails with VERIFICATION_REQUIRED error.
     * It calls the public API endpoint to request a verification email.
     * On success, updates the login form state with a success message.
     * On RATE_LIMIT error, extracts retryAfterSeconds and formats a user-friendly message.
     */
    fun requestPublicVerification() {
        viewModelScope.launch {
            val username = _loginFormState.value.username

            _loginFormState.update { currentState ->
                currentState.copy(
                    isVerifying = true,
                    verificationMessage = null
                )
            }

            authRepository.requestPublicDeviceVerification(username)
                .onLeft { error ->
                    val errorMessage = error.mapVerificationError()
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isVerifying = false,
                            verificationMessage = errorMessage,
                            isVerificationSuccess = false
                        )
                    }
                    logger.warn("Failed to request public device verification: ${error.message}")
                }
                .onRight {
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isVerifying = false,
                            verificationMessage = "Verification email sent! Please check your inbox and click the link to verify your device.",
                            isVerificationSuccess = true,
                            showVerificationTrigger = false
                        )
                    }
                    logger.info("Public device verification email sent successfully")
                }
        }
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

    // --- Init Block ---

    init {
        // Reset forms when the authenticated user changes (e.g., login/logout)
        authState
            .map { (it as? AuthState.Authenticated)?.userId }
            .distinctUntilChanged()
            .onEach {
                // Clear both forms when user changes (including logout where userId becomes null)
                clearLoginForm()
                clearRegisterForm()
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
