package eu.torvian.chatbot.app.compose.admin.users

import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.models.UserWithDetails

/**
 * Actions interface for User Management UI.
 * 
 * Defines all user interactions that trigger ViewModel operations.
 * This interface is implemented in the route component to connect
 * UI events to ViewModel methods.
 */
interface UserManagementActions {
    /**
     * Loads all users from the repository.
     */
    fun onLoadUsers()
    
    /**
     * Selects a user to display in the detail panel.
     * 
     * @param user The user to select, or null to deselect
     */
    fun onSelectUser(user: UserWithDetails?)
    
    /**
     * Starts editing a user by opening the edit dialog.
     * 
     * @param user The user to edit
     */
    fun onStartEditingUser(user: UserWithDetails)
    
    /**
     * Updates the edit user form fields.
     * 
     * @param username The new username value, or null to keep current
     * @param email The new email value, or null to keep current
     */
    fun onUpdateEditUserForm(username: String? = null, email: String? = null)
    
    /**
     * Submits the user edit form to save changes.
     */
    fun onSubmitEditUser()
    
    /**
     * Starts deleting a user by opening the delete confirmation dialog.
     * 
     * @param user The user to delete
     */
    fun onStartDeletingUser(user: UserWithDetails)
    
    /**
     * Confirms the user deletion after the warning dialog.
     */
    fun onConfirmDeleteUser()
    
    /**
     * Starts managing roles for a user by opening the roles dialog.
     * 
     * @param user The user whose roles to manage
     */
    fun onStartManagingRoles(user: UserWithDetails)
    
    /**
     * Assigns a role to the currently selected user.
     * 
     * @param role The role to assign
     */
    fun onAssignRole(role: Role)
    
    /**
     * Revokes a role from the currently selected user.
     * 
     * @param role The role to revoke
     */
    fun onRevokeRole(role: Role)
    
    /**
     * Starts changing a user's password by opening the password dialog.
     * 
     * @param user The user whose password to change
     */
    fun onStartChangingPassword(user: UserWithDetails)
    
    /**
     * Updates the password change form fields.
     * 
     * @param newPassword The new password value, or null to keep current
     * @param confirmPassword The confirm password value, or null to keep current
     */
    fun onUpdatePasswordForm(newPassword: String? = null, confirmPassword: String? = null)
    
    /**
     * Submits the password change form to save the new password.
     */
    fun onSubmitPasswordChange()
    
    /**
     * Starts changing a user's account status by opening the status dialog.
     * 
     * @param user The user whose status to change
     */
    fun onStartChangingUserStatus(user: UserWithDetails)
    
    /**
     * Submits the status change to update the user's account status.
     * 
     * @param status The new status to set
     */
    fun onSubmitUserStatusChange(status: UserStatus)
    
    /**
     * Cancels the current dialog and closes it.
     */
    fun onCancelDialog()
}

