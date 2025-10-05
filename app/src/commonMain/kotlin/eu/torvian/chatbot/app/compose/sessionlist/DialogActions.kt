package eu.torvian.chatbot.app.compose.sessionlist

import eu.torvian.chatbot.common.models.core.ChatSessionSummary

/**
 * Data class to encapsulate dialog actions for sessions and groups.
 */
data class DialogActions(
    val onRenameSessionRequested: (ChatSessionSummary) -> Unit,
    val onDeleteSessionRequested: (Long) -> Unit,
    val onAssignToGroupRequested: (ChatSessionSummary) -> Unit,
    val onDeleteGroupRequested: (Long) -> Unit
)