package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatSessionSummary

/**
 * Defines all UI actions that can be triggered from the Session List Panel.
 */
interface SessionListActions {
    /**
     * Callback for when the user selects a session.
     * @param sessionId The ID of the selected session, or null to clear selection.
     */
    fun onSessionSelected(sessionId: Long?)

    /**
     * Callback for when the user requests a new session.
     * @param name Optional name for the new session. Null if no name provided.
     */
    fun onCreateNewSession(name: String?)

    /**
     * Callback for when the user requests a session rename.
     * @param session The session summary to rename.
     * @param newName The new name for the session.
     */
    fun onRenameSession(session: ChatSessionSummary, newName: String)

    /**
     * Callback for when the user requests a session deletion.
     * @param sessionId The ID of the session to delete.
     */
    fun onDeleteSession(sessionId: Long)

    /**
     * Callback for when the user requests a session be assigned to a group or ungrouped.
     * @param sessionId The ID of the session to move.
     * @param groupId The ID of the target group, or null to ungroup.
     */
    fun onAssignSessionToGroup(sessionId: Long, groupId: Long?)

    /**
     * Callback for when the user starts the process of creating a new group.
     */
    fun onStartCreatingNewGroup()

    /**
     * Callback for when the user updates the content of the new group name input field.
     * @param newText The new text in the input field.
     */
    fun onUpdateNewGroupNameInput(newText: String)

    /**
     * Callback for when the user requests a new group be created.
     */
    fun onCreateNewGroup()

    /**
     * Callback for when the user cancels the new group creation process.
     */
    fun onCancelCreatingNewGroup()

    /**
     * Callback for when the user starts the process of renaming a group.
     * @param group The group to rename.
     */
    fun onStartRenamingGroup(group: ChatGroup)

    /**
     * Callback for when the user updates the content of the editing group name input field.
     * @param newText The new text in the input field.
     */
    fun onUpdateEditingGroupNameInput(newText: String)

    /**
     * Callback for when the user requests a group be renamed.
     */
    fun onSaveRenamedGroup()

    /**
     * Callback for when the user cancels the group renaming process.
     */
    fun onCancelRenamingGroup()

    /**
     * Callback for when the user requests a group be deleted.
     * @param groupId The ID of the group to delete.
     */
    fun onDeleteGroup(groupId: Long)

    /**
     * Callback for when the user requests to retry loading sessions and groups after a failure.
     */
    fun onRetryLoadingSessions()
}