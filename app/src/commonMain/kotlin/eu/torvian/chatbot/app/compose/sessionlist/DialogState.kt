package eu.torvian.chatbot.app.compose.sessionlist

import eu.torvian.chatbot.common.models.ChatSessionSummary

/**
 * Consolidated state for all dialog management in the session list panel.
 */
sealed class DialogState {
    object None : DialogState()
    data class NewSession(val sessionNameInput: String = "") : DialogState()
    data class RenameSession(val session: ChatSessionSummary, val newSessionNameInput: String) : DialogState()
    data class DeleteSession(val sessionId: Long) : DialogState()
    data class AssignGroup(val sessionId: Long, val groupId: Long?) : DialogState()
    data class DeleteGroup(val groupId: Long) : DialogState()
}
