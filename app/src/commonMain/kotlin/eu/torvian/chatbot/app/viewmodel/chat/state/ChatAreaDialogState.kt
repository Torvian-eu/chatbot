package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.StateFlow

/**
 * Consolidated state for all dialog management in the ChatArea.
 * This moves local dialog state from the compose layer to the ViewModel layer.
 *
 * Each dialog state includes pre-bound action lambdas to eliminate UI coupling
 * and provide a consistent action pattern.
 */
sealed class ChatAreaDialogState {
    /**
     * Represents that no dialog is currently visible.
     */
    object None : ChatAreaDialogState()

    /**
     * State for the "Delete Message" confirmation dialog.
     *
     * @property message The message targeted for deletion.
     * @property onDeleteConfirm Pre-bound action to execute when the user confirms deletion.
     * @property onDismiss Pre-bound action to execute when the dialog is dismissed.
     */
    data class DeleteMessage(
        val message: ChatMessage,
        val onDeleteConfirm: () -> Unit,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()

    /**
     * State for the Tool Configuration dialog.
     *
     * @property enabledToolsFlow Reactive flow of tools currently enabled for the session.
     * @property availableToolsFlow Reactive flow of all available tools.
     * @property onToggleTool Action to toggle a tool on/off.
     * @property onDismiss Action to close the dialog.
     */
    data class ToolConfig(
        val enabledToolsFlow: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>,
        val availableToolsFlow: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>,
        val onToggleTool: (ToolDefinition, Boolean) -> Unit,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()

    /**
     * State for the Tool Call Details dialog.
     *
     * @property toolCall The tool call to display.
     * @property onDismiss Action to close the dialog.
     */
    data class ToolCallDetails(
        val toolCall: eu.torvian.chatbot.common.models.tool.ToolCall,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()
}