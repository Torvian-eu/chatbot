package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.core.ChatSessionSummary

/**
 * Consolidated state for all dialog management in the SessionListPanel.
 * This replaces the local DialogState management in the compose layer and moves it to the ViewModel layer,
 * following the same pattern as ProvidersDialogState and ModelsDialogState.
 *
 * Each dialog state now includes pre-bound action lambdas to eliminate UI coupling
 * and provide a consistent action pattern across all dialogs.
 */
sealed class SessionListDialogState {
    object None : SessionListDialogState()
    
    data class NewSession(
        val sessionNameInput: String = "",
        val onNameInputChange: (String) -> Unit,
        val onCreateSession: (String) -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()
    
    data class RenameSession(
        val session: ChatSessionSummary,
        val newSessionNameInput: String,
        val onNameInputChange: (String) -> Unit,
        val onRenameSession: (String) -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()
    
    data class DeleteSession(
        val sessionId: Long,
        val onDeleteConfirm: () -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()
    
    data class CloneSession(
        val sessionId: Long,
        val defaultName: String,
        val nameInput: String,
        val onNameInputChange: (String) -> Unit,
        val onCloneConfirm: (String) -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()

    data class AssignGroup(
        val sessionId: Long,
        val groupId: Long?,
        val onAssignToGroup: (Long, Long?) -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()
    
    data class DeleteGroup(
        val groupId: Long,
        val onDeleteConfirm: () -> Unit,
        val onDismiss: () -> Unit
    ) : SessionListDialogState()
}
