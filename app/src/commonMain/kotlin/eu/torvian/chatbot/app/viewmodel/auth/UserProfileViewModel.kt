package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.security.PasswordValidationConfig
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
 * ViewModel for managing user profile operations including password and email changes.
 *
 * This ViewModel handles:
 * - Password change form state and validation
 * - Email change form state and validation
 * - Required password change (for first login)
 * - Change password and change email dialog states
 *
 * @param authRepository Repository for authentication operations.
 * @param notificationService Service for handling and notifying about errors.
 * @param authValidationService Service for validating authentication form fields.
 * @param normalScope Coroutine scope for normal operations.
 */
class UserProfileViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    private val authValidationService: AuthValidationService,
    private val normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<UserProfileViewModel>()
    }

    // --- Form State Management ---

    private val _passwordChangeFormState = MutableStateFlow(PasswordChangeFormState())
    val passwordChangeFormState: StateFlow<PasswordChangeFormState> = _passwordChangeFormState.asStateFlow()

    private val _changeEmailFormState = MutableStateFlow(ChangeEmailFormState())
    val changeEmailFormState: StateFlow<ChangeEmailFormState> = _changeEmailFormState.asStateFlow()

    /**
     * The password validation configuration from the authentication service.
     * Exposed so that UI components can dynamically render password requirement hints.
     */
    val passwordValidationConfig: PasswordValidationConfig = authValidationService.passwordValidationConfig

    // --- Dialog State Management ---

    private val _dialogState = MutableStateFlow<UserDialogState>(UserDialogState.None)
    val dialogState: StateFlow<UserDialogState> = _dialogState.asStateFlow()

    init {
        // Reset forms when the authenticated user's identity changes
        authRepository.authState
            .map { (it as? AuthState.Authenticated)?.userId }
            .distinctUntilChanged()
            .onEach {
                // Clear both forms when user changes (including logout where userId becomes null)
                clearPasswordChangeForm()
                clearChangeEmailForm()
            }
            .launchIn(viewModelScope)
    }

    // --- Password Change Operations ---

    /**
     * Changes the password for the currently authenticated user.
     * Used when the user is forced to change their password on first login.
     */
    fun changePassword() {
        viewModelScope.launch {
            val currentForm = _passwordChangeFormState.value
            val newPassword = currentForm.newPassword
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting password change")

            // Validate form before submission - currentPassword, newPassword, and confirmPassword required
            val currentPasswordError =
                if (currentForm.currentPassword.isBlank()) "Current password is required" else null
            val newPasswordError = authValidationService.validatePassword(newPassword)
            val confirmPasswordError = authValidationService.validateConfirmPassword(newPassword, confirmPassword)

            if (currentPasswordError != null || newPasswordError != null || confirmPasswordError != null) {
                _passwordChangeFormState.update { currentState ->
                    currentState.copy(
                        currentPasswordError = currentPasswordError,
                        newPasswordError = newPasswordError,
                        confirmPasswordError = confirmPasswordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _passwordChangeFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    currentPasswordError = null,
                    newPasswordError = null,
                    confirmPasswordError = null,
                    generalError = null
                )
            }

            // Perform password change using the repository's changePassword with current password
            val result = authRepository.changePassword(currentForm.currentPassword, newPassword)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Password change failed: ${error.message}")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = error.mapPasswordChangeError()
                        )
                    }
                },
                ifRight = {
                    logger.info("Password change successful")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null,
                            passwordChangeSuccessEvent = true
                        )
                    }
                }
            )
        }
    }

    /**
     * Completes a server-required password change for the currently authenticated user.
     *
     * This method is used when the user is forced to change their password
     * (requiresPasswordChange = true). Unlike normal password change, it does not
     * require the current password.
     */
    fun completeRequiredPasswordChange() {
        viewModelScope.launch {
            val currentForm = _passwordChangeFormState.value
            val newPassword = currentForm.newPassword
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting required password change")

            // Validate form before submission - only newPassword and confirmPassword required
            val newPasswordError = authValidationService.validatePassword(newPassword)
            val confirmPasswordError = authValidationService.validateConfirmPassword(newPassword, confirmPassword)

            if (newPasswordError != null || confirmPasswordError != null) {
                _passwordChangeFormState.update { currentState ->
                    currentState.copy(
                        newPasswordError = newPasswordError,
                        confirmPasswordError = confirmPasswordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _passwordChangeFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    newPasswordError = null,
                    confirmPasswordError = null,
                    generalError = null
                )
            }

            // Perform required password change using the repository
            val result = authRepository.completeRequiredPasswordChange(newPassword)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Required password change failed: ${error.message}")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = error.mapPasswordChangeError()
                        )
                    }
                },
                ifRight = {
                    logger.info("Required password change successful")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null,
                            passwordChangeSuccessEvent = true
                        )
                    }
                }
            )
        }
    }

    // --- Email Change Operations ---

    /**
     * Changes the email address for the currently authenticated user.
     */
    fun changeEmail() {
        viewModelScope.launch {
            val currentForm = _changeEmailFormState.value
            val currentPassword = currentForm.currentPassword
            val newEmail = currentForm.newEmail

            logger.info("Attempting email change")

            // Validate form before submission
            val currentPasswordError =
                if (currentPassword.isBlank()) "Current password is required" else null
            val newEmailError = authValidationService.validateEmail(newEmail)

            if (currentPasswordError != null || newEmailError != null) {
                _changeEmailFormState.update { currentState ->
                    currentState.copy(
                        currentPasswordError = currentPasswordError,
                        newEmailError = newEmailError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _changeEmailFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    currentPasswordError = null,
                    newEmailError = null,
                    generalError = null
                )
            }

            // Perform email change using the repository
            val result = authRepository.changeEmail(currentPassword, newEmail)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Email change failed: ${error.message}")
                    _changeEmailFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = error.mapEmailChangeError()
                        )
                    }
                },
                ifRight = {
                    logger.info("Email change successful")
                    notificationService.genericSuccess("Your email address has been updated.")
                    _dialogState.value = UserDialogState.None
                    _changeEmailFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null,
                            emailChangeSuccessEvent = true
                        )
                    }
                }
            )
        }
    }

    // --- Dialog State Management ---

    /**
     * Opens the change password dialog.
     */
    fun openChangePasswordDialog() {
        _dialogState.value = UserDialogState.ChangePassword
    }

    /**
     * Opens the change email dialog.
     */
    fun openChangeEmailDialog() {
        _dialogState.value = UserDialogState.ChangeEmail
    }

    /**
     * Closes any open authentication dialog.
     */
    fun closeDialog() {
        _dialogState.value = UserDialogState.None
    }


    // --- Form State Updates ---

    /**
     * Updates the password change form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updatePasswordChangeForm(
        currentPassword: String? = null,
        newPassword: String? = null,
        confirmPassword: String? = null
    ) {
        _passwordChangeFormState.update { currentState ->
            currentState.copy(
                currentPassword = currentPassword ?: currentState.currentPassword,
                newPassword = newPassword ?: currentState.newPassword,
                confirmPassword = confirmPassword ?: currentState.confirmPassword,
                // Clear field-specific errors when user types
                currentPasswordError = if (currentPassword != null && currentPassword != currentState.currentPassword) null else currentState.currentPasswordError,
                newPasswordError = if (newPassword != null && newPassword != currentState.newPassword) null else currentState.newPasswordError,
                confirmPasswordError = if (confirmPassword != null && confirmPassword != currentState.confirmPassword) null else currentState.confirmPasswordError
            )
        }
    }

    /**
     * Updates the change email form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updateChangeEmailForm(
        currentPassword: String? = null,
        newEmail: String? = null
    ) {
        _changeEmailFormState.update { currentState ->
            currentState.copy(
                currentPassword = currentPassword ?: currentState.currentPassword,
                newEmail = newEmail ?: currentState.newEmail,
                // Clear field-specific errors when user types
                currentPasswordError = if (currentPassword != null && currentPassword != currentState.currentPassword) null else currentState.currentPasswordError,
                newEmailError = if (newEmail != null && newEmail != currentState.newEmail) null else currentState.newEmailError
            )
        }
    }

    /**
     * Clears the password change form state.
     */
    fun clearPasswordChangeForm() {
        _passwordChangeFormState.value = PasswordChangeFormState()
    }

    /**
     * Clears the change email form state.
     */
    fun clearChangeEmailForm() {
        _changeEmailFormState.value = ChangeEmailFormState()
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
