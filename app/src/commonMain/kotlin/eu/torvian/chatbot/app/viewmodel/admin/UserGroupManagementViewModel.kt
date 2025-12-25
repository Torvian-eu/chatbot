package eu.torvian.chatbot.app.viewmodel.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserGroupRepository
import eu.torvian.chatbot.app.repository.UserRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.admin.AddUserToGroupRequest
import eu.torvian.chatbot.common.models.api.admin.CreateUserGroupRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserGroupRequest
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing user groups in the admin interface.
 *
 * This ViewModel handles all user group management operations including creating, editing,
 * deleting groups, and managing group membership. It follows the reactive state management
 * pattern using StateFlow and delegates repository operations to [UserGroupRepository].
 *
 * @property userGroupRepository Repository for user group operations
 * @property userRepository Repository for user operations (needed for member management)
 * @property notificationService Service for displaying notifications
 */
class UserGroupManagementViewModel(
    private val userGroupRepository: UserGroupRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<UserGroupManagementViewModel>()
    }

    // --- Reactive Data from Repository ---
    val groupsDataState: StateFlow<DataState<RepositoryError, List<UserGroup>>> = userGroupRepository.groups

    // --- UI State ---
    private val _state = MutableStateFlow(UserGroupManagementState())
    val state: StateFlow<UserGroupManagementState> = _state.asStateFlow()

    // --- Derived State ---
    val selectedGroup: StateFlow<UserGroup?> = _state.map { it.selectedGroup }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val dialogState: StateFlow<UserGroupManagementDialogState> = _state.map { it.dialogState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserGroupManagementDialogState.None)

    // --- Public Action Functions ---

    /**
     * Loads all user groups from the backend.
     */
    fun loadGroups() {
        viewModelScope.launch {
            userGroupRepository.loadGroups().fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load user groups"
                    )
                },
                ifRight = { /* success - state is reactive via repository */ }
            )
        }
    }

    /**
     * Selects a group (or clears selection when null).
     *
     * @param group The group to select, or null to deselect
     */
    fun selectGroup(group: UserGroup?) {
        _state.update { it.copy(selectedGroup = group) }
    }

    /**
     * Opens the create group dialog.
     */
    fun startCreatingGroup() {
        _state.update {
            it.copy(
                dialogState = UserGroupManagementDialogState.CreateGroup(
                    formState = GroupFormState()
                )
            )
        }
    }

    /**
     * Updates the create/edit group form fields.
     *
     * @param name The new name value, or null to keep current
     * @param description The new description value, or null to keep current
     */
    fun updateGroupForm(name: String? = null, description: String? = null) {
        _state.update { currentState ->
            val updatedDialog = when (val currentDialog = currentState.dialogState) {
                is UserGroupManagementDialogState.CreateGroup -> {
                    val formState = currentDialog.formState
                    currentDialog.copy(
                        formState = formState.copy(
                            name = name ?: formState.name,
                            description = description ?: formState.description,
                            nameError = validateGroupName(name ?: formState.name)
                        )
                    )
                }

                is UserGroupManagementDialogState.EditGroup -> {
                    val formState = currentDialog.formState
                    currentDialog.copy(
                        formState = formState.copy(
                            name = name ?: formState.name,
                            description = description ?: formState.description,
                            nameError = validateGroupName(name ?: formState.name)
                        )
                    )
                }

                else -> currentDialog
            }
            currentState.copy(dialogState = updatedDialog)
        }
    }

    /**
     * Submits the create group form.
     */
    fun submitCreateGroup() {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserGroupManagementDialogState.CreateGroup) return

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

            val request = CreateUserGroupRequest(
                name = formState.name,
                description = formState.description
            )

            userGroupRepository.createGroup(request).fold(
                ifLeft = { error ->
                    logger.warn("Failed to create group: ${error.message}")
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
                ifRight = { newGroup ->
                    logger.info("Successfully created group: ${newGroup.name}")
                    _state.update {
                        it.copy(
                            selectedGroup = newGroup,
                            dialogState = UserGroupManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Initiates the editing process for a group.
     *
     * @param group The group to edit
     */
    fun startEditingGroup(group: UserGroup) {
        _state.update {
            it.copy(
                dialogState = UserGroupManagementDialogState.EditGroup(
                    group = group,
                    formState = GroupFormState.fromGroup(group)
                )
            )
        }
    }

    /**
     * Submits the edit group form.
     */
    fun submitEditGroup() {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserGroupManagementDialogState.EditGroup) return

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

            val request = UpdateUserGroupRequest(
                name = formState.name,
                description = formState.description
            )

            userGroupRepository.updateGroup(currentDialog.group.id, request).fold(
                ifLeft = { error ->
                    logger.warn("Failed to update group: ${error.message}")
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
                    logger.info("Successfully updated group: ${formState.name}")
                    // Update selected group if it's the same one
                    _state.update {
                        it.copy(
                            selectedGroup = it.selectedGroup?.let { sel ->
                                if (sel.id == currentDialog.group.id) {
                                    sel.copy(
                                        name = formState.name,
                                        description = formState.description
                                    )
                                } else sel
                            },
                            dialogState = UserGroupManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Initiates the deletion process for a group.
     *
     * @param group The group to delete
     */
    fun startDeletingGroup(group: UserGroup) {
        _state.update {
            it.copy(
                dialogState = UserGroupManagementDialogState.DeleteGroup(group = group)
            )
        }
    }

    /**
     * Confirms and executes the group deletion.
     */
    fun confirmDeleteGroup() {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserGroupManagementDialogState.DeleteGroup) return

        viewModelScope.launch {
            userGroupRepository.deleteGroup(currentDialog.group.id).fold(
                ifLeft = { error ->
                    logger.warn("Failed to delete group: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to delete group"
                    )
                    _state.update { it.copy(dialogState = UserGroupManagementDialogState.None) }
                },
                ifRight = {
                    logger.info("Successfully deleted group: ${currentDialog.group.name}")
                    _state.update {
                        it.copy(
                            selectedGroup = if (it.selectedGroup?.id == currentDialog.group.id) null else it.selectedGroup,
                            dialogState = UserGroupManagementDialogState.None
                        )
                    }
                }
            )
        }
    }

    /**
     * Validates a group name.
     *
     * @param name The name to validate
     * @return Error message if invalid, null if valid
     */
    private fun validateGroupName(name: String): String? {
        return when {
            name.isBlank() -> "Group name cannot be empty"
            name.length > 100 -> "Group name cannot exceed 100 characters"
            else -> null
        }
    }

    /**
     * Initiates the member management process for a group.
     *
     * @param group The group whose members to manage
     */
    fun startManagingMembers(group: UserGroup) {
        viewModelScope.launch {
            // Set loading state while fetching members and users
            _state.update {
                it.copy(
                    dialogState = UserGroupManagementDialogState.ManageMembers(
                        group = group,
                        members = emptyList(),
                        availableUsers = emptyList(),
                        isLoading = true
                    )
                )
            }

            // Fetch members and users
            val members = userGroupRepository.getGroupMembers(group.id).getOrElse {
                logger.warn("Failed to load group members: ${it.message}")
                notificationService.repositoryError(
                    error = it,
                    shortMessage = "Failed to load group members"
                )
                return@launch
            }
            val users = when (val usersState = userRepository.users.value) {
                is DataState.Success -> usersState.data.map { it.toUser() }.right()
                else -> {
                    // Trigger load if not already loaded
                    userRepository.loadUsers()
                    when (val updatedState = userRepository.users.value) {
                        is DataState.Success -> updatedState.data.map { it.toUser() }.right()
                        is DataState.Error -> updatedState.error.left()
                        else -> RepositoryError.OtherError("Failed to load users").left()
                    }
                }
            }.getOrElse {
                logger.warn("Failed to load users: ${it.message}")
                notificationService.repositoryError(
                    error = it,
                    shortMessage = "Failed to load users"
                )
                return@launch
            }

            // Update state with fetched data
            _state.update {
                it.copy(
                    dialogState = UserGroupManagementDialogState.ManageMembers(
                        group = group,
                        members = members,
                        availableUsers = users,
                        isLoading = false
                    )
                )
            }
        }
    }

    /**
     * Adds a user to the group being managed.
     *
     * @param user The user to add to the group
     */
    fun addMemberToGroup(user: User) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserGroupManagementDialogState.ManageMembers) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(isLoading = true)
                )
            }

            val request = AddUserToGroupRequest(userId = user.id)
            userGroupRepository.addUserToGroup(currentDialog.group.id, request).fold(
                ifLeft = { error ->
                    logger.warn("Failed to add user to group: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to add user to group"
                    )
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(isLoading = false)
                        )
                    }
                },
                ifRight = {
                    logger.info("Successfully added user ${user.username} to group ${currentDialog.group.name}")
                    // Refresh the members list
                    refreshGroupMembers(currentDialog.group)
                }
            )
        }
    }

    /**
     * Removes a user from the group being managed.
     *
     * @param user The user to remove from the group
     */
    fun removeMemberFromGroup(user: User) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserGroupManagementDialogState.ManageMembers) return

        viewModelScope.launch {
            // Set loading state
            _state.update {
                it.copy(
                    dialogState = currentDialog.copy(isLoading = true)
                )
            }

            userGroupRepository.removeUserFromGroup(currentDialog.group.id, user.id).fold(
                ifLeft = { error ->
                    logger.warn("Failed to remove user from group: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to remove user from group"
                    )
                    _state.update {
                        it.copy(
                            dialogState = currentDialog.copy(isLoading = false)
                        )
                    }
                },
                ifRight = {
                    logger.info("Successfully removed user ${user.username} from group ${currentDialog.group.name}")
                    // Refresh the members list
                    refreshGroupMembers(currentDialog.group)
                }
            )
        }
    }

    /**
     * Refreshes the members list for the currently managed group.
     *
     * @param group The group to refresh members for
     */
    private suspend fun refreshGroupMembers(group: UserGroup) {
        val currentDialog = _state.value.dialogState
        if (currentDialog !is UserGroupManagementDialogState.ManageMembers) return

        userGroupRepository.getGroupMembers(group.id).fold(
            ifLeft = { error ->
                logger.warn("Failed to refresh group members: ${error.message}")
                _state.update {
                    it.copy(
                        dialogState = currentDialog.copy(isLoading = false)
                    )
                }
            },
            ifRight = { members ->
                _state.update {
                    it.copy(
                        dialogState = currentDialog.copy(
                            members = members,
                            isLoading = false
                        )
                    )
                }
            }
        )
    }

    /**
     * Closes the current dialog.
     */
    fun closeDialog() {
        _state.update { it.copy(dialogState = UserGroupManagementDialogState.None) }
    }
}



