package eu.torvian.chatbot.app.viewmodel.admin

import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.UserWithDetails
import eu.torvian.chatbot.common.models.UserStatus

/**
 * Represents the overall state of the user management screen.
 *
 * @property selectedUser The currently selected user (null if none selected)
 * @property dialogState The current dialog state
 */
data class UserManagementState(
    val selectedUser: UserWithDetails? = null,
    val dialogState: UserManagementDialogState = UserManagementDialogState.None
)

/**
 * Represents the state of dialogs in the user management screen.
 */
sealed interface UserManagementDialogState {
    /** No dialog is shown */
    data object None : UserManagementDialogState

    /**
     * Edit user dialog is shown.
     *
     * @property user The user being edited
     * @property formState The form state for editing
     */
    data class EditUser(
        val user: UserWithDetails,
        val formState: UserFormState
    ) : UserManagementDialogState

    /**
     * Delete user confirmation dialog is shown.
     *
     * @property user The user to be deleted
     */
    data class DeleteUser(
        val user: UserWithDetails
    ) : UserManagementDialogState

    /**
     * Role assignment dialog is shown.
     *
     * @property user The user whose roles are being managed
     * @property availableRoles All available roles in the system
     * @property isLoading Whether role operations are in progress
     */
    data class ManageRoles(
        val user: UserWithDetails,
        val availableRoles: List<Role>,
        val isLoading: Boolean = false
    ) : UserManagementDialogState

    /**
     * Change password dialog is shown.
     *
     * @property user The user whose password is being changed
     * @property formState The form state for password change
     */
    data class ChangePassword(
        val user: UserWithDetails,
        val formState: PasswordFormState
    ) : UserManagementDialogState

    /**
     * Change user status dialog is shown.
     *
     * @property user The user whose status is being changed
     * @property isLoading Whether the status change operation is in progress
     */
    data class ChangeUserStatus(
        val user: UserWithDetails,
        val isLoading: Boolean = false
    ) : UserManagementDialogState
}

/**
 * Form state for editing user details.
 *
 * @property username The username input
 * @property email The email input (nullable)
 * @property usernameError Validation error for username
 * @property emailError Validation error for email
 * @property isLoading Whether the form is being submitted
 * @property generalError General error message
 */
data class UserFormState(
    val username: String = "",
    val email: String? = null,
    val usernameError: String? = null,
    val emailError: String? = null,
    val isLoading: Boolean = false,
    val generalError: String? = null
) {
    val isValid: Boolean
        get() = username.isNotBlank() &&
                usernameError == null &&
                emailError == null

    companion object {
        /**
         * Creates a form state from an existing user.
         */
        fun fromUser(user: UserWithDetails): UserFormState {
            return UserFormState(
                username = user.username,
                email = user.email
            )
        }
    }
}

/**
 * Form state for changing user password.
 *
 * @property newPassword The new password input
 * @property confirmPassword The password confirmation input
 * @property newPasswordError Validation error for new password
 * @property confirmPasswordError Validation error for confirmation
 * @property isLoading Whether the form is being submitted
 * @property generalError General error message
 */
data class PasswordFormState(
    val newPassword: String = "",
    val confirmPassword: String = "",
    val newPasswordError: String? = null,
    val confirmPasswordError: String? = null,
    val isLoading: Boolean = false,
    val generalError: String? = null
) {
    val isValid: Boolean
        get() = newPassword.isNotBlank() &&
                confirmPassword.isNotBlank() &&
                newPassword == confirmPassword &&
                newPasswordError == null &&
                confirmPasswordError == null
}

