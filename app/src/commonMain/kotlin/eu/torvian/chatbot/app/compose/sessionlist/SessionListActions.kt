package eu.torvian.chatbot.app.compose.sessionlist

import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatSessionSummary

/**
 * Defines all UI actions that can be triggered from the Session List Panel.
 * After implementing Option 1 (pre-bound dialog actions), this interface now only contains
 * actions that are still needed for direct UI interactions.
 */
interface SessionListActions {
    /**
     * Callback for when the user selects a session.
     * @param sessionId The ID of the selected session, or null to clear selection.
     */
    fun onSessionSelected(sessionId: Long?)

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
     * Callback for when the user requests to retry loading sessions and groups after a failure.
     */
    fun onRetryLoadingSessions()

    // --- Dialog Request Actions (trigger dialog display) ---

    /**
     * Callback for when the user requests to show the new session dialog.
     */
    fun onShowNewSessionDialog()

    /**
     * Callback for when the user requests to show the rename session dialog.
     * @param session The session to rename.
     */
    fun onShowRenameSessionDialog(session: ChatSessionSummary)

    /**
     * Callback for when the user requests to show the delete session dialog.
     * @param sessionId The ID of the session to delete.
     */
    fun onShowDeleteSessionDialog(sessionId: Long)

    /**
     * Callback for when the user requests to show the clone session dialog.
     * @param session The session to clone.
     */
    fun onShowCloneSessionDialog(session: ChatSessionSummary)

    /**
     * Callback for when the user requests to show the assign group dialog.
     * @param session The session to assign to a group.
     */
    fun onShowAssignGroupDialog(session: ChatSessionSummary)

    /**
     * Callback for when the user requests to show the delete group dialog.
     * @param groupId The ID of the group to delete.
     */
    fun onShowDeleteGroupDialog(groupId: Long)
}