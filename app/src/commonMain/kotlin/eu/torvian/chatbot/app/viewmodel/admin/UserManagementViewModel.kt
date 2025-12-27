package eu.torvian.chatbot.app.viewmodel.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.RoleRepository
import eu.torvian.chatbot.app.repository.UserRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.user.UserWithDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for User Management (admin only).
 */
class UserManagementViewModel(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val notificationService: NotificationService,
    private val normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<UserManagementViewModel>()
    }

    // --- Reactive Data from Repository ---
    val usersDataState: StateFlow<DataState<RepositoryError, List<UserWithDetails>>> = userRepository.users

    // --- UI State ---
    private val _state = MutableStateFlow(UserManagementState())
    val state: StateFlow<UserManagementState> = _state.asStateFlow()

    // --- Derived State ---
    val selectedUser: StateFlow<UserWithDetails?> = _state.map { it.selectedUser }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val dialogState: StateFlow<UserManagementDialogState> = _state.map { it.dialogState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserManagementDialogState.None)

    // --- Initialization ---
    init {
        // Load roles on initialization
        viewModelScope.launch {
            roleRepository.loadRoles()
        }
    }

    // --- Public Action Functions ---

    /**
     * Loads all users from the backend.
     */
    fun loadUsers() {
        viewModelScope.launch {
            userRepository.loadUsers().fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load users"
                    )
                },
                ifRight = { /* success - state is reactive via repository */ }
            )
        }
    }

    /**
     * Selects a user (or clears selection when null).
     */
    fun selectUser(user: UserWithDetails?) {
        _state.update { it.copy(selectedUser = user) }
    }

    /**
     * Initiates the editing process for a user.
     */
    fun startEditingUser(user: UserWithDetails) {
        _state.update {
            it.copy(
                dialogState = UserManagementDialogState.EditUser(
                    user = user,
                    formState = UserFormState.fromUser(user)
                )
            )
        }
    }

    /**
     * Updates the edit user form state.
     */
    fun updateEditUserForm(
        username: String? = null,
        email: String? = null
    ) {
        _state.update { currentState ->
            val currentDialog = currentState.dialogState
            if (currentDialog is UserManagementDialogState.EditUser) {
                // Clear field-specific errors when user types
                val formState = currentDialog.formState
                val updatedForm = currentDialog.formState.copy(
                    username = username ?: formState.username,
                    email = email ?: formState.email,
                    usernameError = if (username != null && username != formState.username) null else formState.usernameError,
                    emailError = if (email != null && email != formState.email) null else formState.emailError
                )
                currentState.copy(
                    dialogState = currentDialog.copy(formState = updatedForm)
                )
            } else {
                currentState
            }
        }
    }

    /**
     * Submits the edit user form.
     */
    fun submitEditUser() {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.EditUser) return

        val formState = currentDialog.formState
        if (!formState.isValid) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(
                        formState = formState.copy(isLoading = true)
                    )
                )
            }

            userRepository.updateUser(
                currentDialog.user.id,
                formState.username,
                formState.email
            ).fold(
                ifLeft = { error ->
                    logger.warn("Failed to update user: ${error.message}")
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(
                                formState = formState.copy(
                                    isLoading = false,
                                    generalError = error.message
                                )
                            )
                        )
                    }
                },
                ifRight = { updatedUser ->
                    logger.info("Successfully updated user: ${updatedUser.username}")
                    // Update selected user if it's the same one
                    _state.update {
                        it.copy(
                            selectedUser = it.selectedUser?.let { sel ->
                                if (sel.id == updatedUser.id) sel.copy(
                                    username = updatedUser.username,
                                    email = updatedUser.email,
                                    status = updatedUser.status
                                ) else sel
                            },
                            dialogState = UserManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Initiates the deletion process for a user.
     */
    fun startDeletingUser(user: UserWithDetails) {
        _state.update {
            it.copy(dialogState = UserManagementDialogState.DeleteUser(user))
        }
    }

    /**
     * Confirms and executes user deletion.
     */
    fun confirmDeleteUser() {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.DeleteUser) return

        viewModelScope.launch {
            userRepository.deleteUser(currentDialog.user.id).fold(
                ifLeft = { error ->
                    logger.warn("Failed to delete user: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to delete user"
                    )
                },
                ifRight = {
                    logger.info("Successfully deleted user: ${currentDialog.user.username}")
                    // Clear selection if deleted user was selected
                    _state.update {
                        it.copy(
                            selectedUser = if (it.selectedUser?.id == currentDialog.user.id) null else it.selectedUser,
                            dialogState = UserManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Initiates the role management process for a user.
     */
    fun startManagingRoles(user: UserWithDetails) {
        viewModelScope.launch {
            // Get available roles from the repository's StateFlow
            when (val currentAvailableRoles = roleRepository.roles.value) {
                is DataState.Success -> {
                    _state.update {
                        it.copy(
                            dialogState = UserManagementDialogState.ManageRoles(
                                user = user,
                                availableRoles = currentAvailableRoles.data
                            )
                        )
                    }
                }

                is DataState.Error -> {
                    logger.warn("Failed to load available roles: ${currentAvailableRoles.error.message}")
                    notificationService.repositoryError(
                        error = currentAvailableRoles.error,
                        shortMessage = "Failed to load available roles"
                    )
                }

                is DataState.Loading -> {
                    logger.warn("Roles are still loading")
                }

                is DataState.Idle -> {
                    logger.warn("Roles are in idle state")
                }
            }
        }
    }

    /**
     * Assigns a role to a user.
     */
    fun assignRole(role: Role) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.ManageRoles) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(isLoading = true)
                )
            }

            userRepository.assignRoleToUser(currentDialog.user.id, role).fold(
                ifLeft = { error ->
                    logger.warn("Failed to assign role: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to assign role"
                    )
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(isLoading = false)
                        )
                    }
                },
                ifRight = {
                    logger.info("Successfully assigned role to user")
                    // update user's roles in dialog state
                    _state.update { userManagementState ->
                        val updatedUser = currentDialog.user.copy(roles = currentDialog.user.roles + role)
                        userManagementState.copy(
                            selectedUser = if (userManagementState.selectedUser?.id == currentDialog.user.id) {
                                updatedUser
                            } else {
                                userManagementState.selectedUser
                            },
                            dialogState = currentDialog.copy(
                                user = updatedUser,
                                isLoading = false
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Revokes a role from a user.
     */
    fun revokeRole(role: Role) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.ManageRoles) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(isLoading = true)
                )
            }

            userRepository.revokeRoleFromUser(currentDialog.user.id, role).fold(
                ifLeft = { error ->
                    logger.warn("Failed to revoke role: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to revoke role"
                    )
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(isLoading = false)
                        )
                    }
                },
                ifRight = {
                    logger.info("Successfully revoked role from user")
                    // update user's roles in dialog state
                    _state.update { userManagementState ->
                        val updatedUser =
                            currentDialog.user.copy(roles = currentDialog.user.roles.filterNot { it.id == role.id })
                        userManagementState.copy(
                            selectedUser = if (userManagementState.selectedUser?.id == currentDialog.user.id) {
                                updatedUser
                            } else {
                                userManagementState.selectedUser
                            },
                            dialogState = currentDialog.copy(
                                user = updatedUser,
                                isLoading = false
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Initiates the password change process for a user.
     */
    fun startChangingPassword(user: UserWithDetails) {
        _state.update {
            it.copy(
                dialogState = UserManagementDialogState.ChangePassword(
                    user = user,
                    formState = PasswordFormState()
                )
            )
        }
    }

    /**
     * Updates the password change form state.
     */
    fun updatePasswordForm(
        newPassword: String? = null,
        confirmPassword: String? = null
    ) {
        _state.update { currentState ->
            val currentDialog = currentState.dialogState
            if (currentDialog is UserManagementDialogState.ChangePassword) {
                // Clear field-specific errors when user types
                val formState = currentDialog.formState
                val updatedForm = currentDialog.formState.copy(
                    newPassword = newPassword ?: formState.newPassword,
                    confirmPassword = confirmPassword ?: formState.confirmPassword,
                    newPasswordError = if (newPassword != null && newPassword != formState.newPassword) null else formState.newPasswordError,
                    confirmPasswordError = if (confirmPassword != null && confirmPassword != formState.confirmPassword) null else formState.confirmPasswordError,
                )
                currentState.copy(
                    dialogState = currentDialog.copy(formState = updatedForm)
                )
            } else {
                currentState
            }
        }
    }

    /**
     * Submits the password change form.
     */
    fun submitPasswordChange() {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.ChangePassword) return

        val formState = currentDialog.formState
        if (!formState.isValid) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(
                        formState = formState.copy(isLoading = true)
                    )
                )
            }

            userRepository.changeUserPassword(currentDialog.user.id, formState.newPassword).fold(
                ifLeft = { error ->
                    logger.warn("Failed to change password: ${error.message}")
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(
                                formState = formState.copy(
                                    isLoading = false,
                                    generalError = error.message
                                )
                            )
                        )
                    }
                },
                ifRight = {
                    logger.info("Successfully changed user password")
                    _state.update {
                        it.copy(dialogState = UserManagementDialogState.None)
                    }
                }
            )
        }
    }

    /**
     * Initiates the user status change process for a user.
     */
    fun startChangingUserStatus(user: UserWithDetails) {
        _state.update {
            it.copy(
                dialogState = UserManagementDialogState.ChangeUserStatus(user = user)
            )
        }
    }

    /**
     * Submits the user status change.
     */
    fun submitUserStatusChange(newStatus: UserStatus) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.ChangeUserStatus) return

        val userId = currentDialog.user.id
        if (currentDialog.user.status == newStatus) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(isLoading = true)
                )
            }

            userRepository.updateUserStatus(userId, newStatus).fold(
                ifLeft = { error ->
                    logger.warn("Failed to change user status for ID $userId to $newStatus: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to change user status"
                    )
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(isLoading = false)
                        )
                    }
                },
                ifRight = { updatedUser ->
                    logger.info("Successfully changed user status for ID $userId to ${updatedUser.status}")
                    _state.update {
                        it.copy(
                            selectedUser = it.selectedUser?.let { sel ->
                                if (sel.id == updatedUser.id) sel.copy(status = updatedUser.status) else sel
                            },
                            dialogState = UserManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Initiates the password change required flag change process for a user.
     */
    fun startChangingPasswordChangeRequired(user: UserWithDetails) {
        _state.update {
            it.copy(
                dialogState = UserManagementDialogState.ChangePasswordChangeRequired(user = user)
            )
        }
    }

    /**
     * Submits the password change required flag change.
     */
    fun submitPasswordChangeRequiredChange(requiresPasswordChange: Boolean) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserManagementDialogState.ChangePasswordChangeRequired) return

        val userId = currentDialog.user.id

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(isLoading = true)
                )
            }

            userRepository.updatePasswordChangeRequired(userId, requiresPasswordChange).fold(
                ifLeft = { error ->
                    logger.warn("Failed to update password change required flag for ID $userId to $requiresPasswordChange: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to update password change required flag"
                    )
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(isLoading = false)
                        )
                    }
                },
                ifRight = { updatedUser ->
                    logger.info("Successfully updated password change required flag for ID $userId to ${updatedUser.requiresPasswordChange}")
                    _state.update {
                        it.copy(
                            selectedUser = it.selectedUser?.let { sel ->
                                if (sel.id == updatedUser.id) sel.copy(requiresPasswordChange = updatedUser.requiresPasswordChange) else sel
                            },
                            dialogState = UserManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Closes the current dialog.
     */
    fun closeDialog() {
        _state.update { it.copy(dialogState = UserManagementDialogState.None) }
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
