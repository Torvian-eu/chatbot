package eu.torvian.chatbot.app.compose.admin.usergroups

import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Actions interface for User Group Management UI.
 *
 * Defines all user interactions that trigger ViewModel operations.
 * This interface is implemented in the route component to connect
 * UI events to ViewModel methods.
 */
interface GroupManagementActions {
    /**
     * Loads all user groups from the repository.
     */
    fun onLoadGroups()

    /**
     * Selects a group to display in the detail panel.
     *
     * @param group The group to select, or null to deselect
     */
    fun onSelectGroup(group: UserGroup?)

    /**
     * Starts creating a new group by opening the create dialog.
     */
    fun onStartCreatingGroup()

    /**
     * Updates the create/edit group form fields.
     *
     * @param name The new name value, or null to keep current
     * @param description The new description value, or null to keep current
     */
    fun onUpdateGroupForm(name: String? = null, description: String? = null)

    /**
     * Submits the create group form to save the new group.
     */
    fun onSubmitCreateGroup()

    /**
     * Starts editing a group by opening the edit dialog.
     *
     * @param group The group to edit
     */
    fun onStartEditingGroup(group: UserGroup)

    /**
     * Submits the edit group form to save changes.
     */
    fun onSubmitEditGroup()

    /**
     * Starts deleting a group by opening the delete confirmation dialog.
     *
     * @param group The group to delete
     */
    fun onStartDeletingGroup(group: UserGroup)

    /**
     * Confirms the group deletion after the warning dialog.
     */
    fun onConfirmDeleteGroup()

    /**
     * Starts managing members for a group by opening the members dialog.
     *
     * @param group The group whose members to manage
     */
    fun onStartManagingMembers(group: UserGroup)

    /**
     * Adds a user to the group being managed.
     *
     * @param user The user to add to the group
     */
    fun onAddMemberToGroup(user: User)

    /**
     * Removes a user from the group being managed.
     *
     * @param user The user to remove from the group
     */
    fun onRemoveMemberFromGroup(user: User)

    /**
     * Cancels and closes the current dialog.
     */
    fun onCancelDialog()
}

