package eu.torvian.chatbot.app.viewmodel.admin

import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Represents the overall state of the user group management screen.
 *
 * @property selectedGroup The currently selected group, or null if none selected
 * @property dialogState The current dialog state
 */
data class UserGroupManagementState(
    val selectedGroup: UserGroup? = null,
    val dialogState: UserGroupManagementDialogState = UserGroupManagementDialogState.None
)

/**
 * Represents the state of dialogs in the user group management screen.
 */
sealed interface UserGroupManagementDialogState {
    /** No dialog is shown */
    data object None : UserGroupManagementDialogState

    /**
     * Create group dialog is shown.
     *
     * @property formState The form state for creating a group
     */
    data class CreateGroup(
        val formState: GroupFormState
    ) : UserGroupManagementDialogState

    /**
     * Edit group dialog is shown.
     *
     * @property group The group being edited
     * @property formState The form state for editing
     */
    data class EditGroup(
        val group: UserGroup,
        val formState: GroupFormState
    ) : UserGroupManagementDialogState

    /**
     * Delete group confirmation dialog is shown.
     *
     * @property group The group to be deleted
     */
    data class DeleteGroup(
        val group: UserGroup
    ) : UserGroupManagementDialogState

    /**
     * Manage group members dialog is shown.
     *
     * @property group The group whose members are being managed
     * @property members The current list of members in the group
     * @property availableUsers All users in the system (for adding new members)
     * @property isLoading Whether a member operation is in progress
     */
    data class ManageMembers(
        val group: UserGroup,
        val members: List<User>,
        val availableUsers: List<User>,
        val isLoading: Boolean = false
    ) : UserGroupManagementDialogState
}

/**
 * Form state for creating or editing a user group.
 *
 * @property name The group name input
 * @property description The group description input (nullable)
 * @property nameError Validation error for name
 * @property isLoading Whether the form is being submitted
 * @property generalError General error message
 */
data class GroupFormState(
    val name: String = "",
    val description: String? = null,
    val nameError: String? = null,
    val isLoading: Boolean = false,
    val generalError: String? = null
) {
    /**
     * Indicates whether the form is valid and can be submitted.
     */
    val isValid: Boolean
        get() = name.isNotBlank() && nameError == null

    companion object {
        /**
         * Creates a GroupFormState from an existing UserGroup for editing.
         *
         * @param group The group to create the form state from
         * @return A new GroupFormState populated with the group's data
         */
        fun fromGroup(group: UserGroup): GroupFormState {
            return GroupFormState(
                name = group.name,
                description = group.description
            )
        }
    }
}

