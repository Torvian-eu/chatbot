package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
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
     * State for the "Delete Thread" (recursive deletion) confirmation dialog.
     * Shows a stronger warning than single message deletion since this action
     * deletes the message and all its replies.
     *
     * @property message The message targeted for deletion along with all replies.
     * @property onDeleteConfirm Pre-bound action to execute when the user confirms deletion.
     * @property onDismiss Pre-bound action to execute when the dialog is dismissed.
     */
    data class DeleteMessageRecursively(
        val message: ChatMessage,
        val onDeleteConfirm: () -> Unit,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()

    /**
     * State for the "Insert Message" dialog.
     *
     * @property targetMessage The message relative to which the new message will be inserted.
     * @property onConfirm Pre-bound action to execute when the user confirms insertion.
     *                     Takes position, role, and content as arguments.
     * @property onDismiss Pre-bound action to execute when the dialog is dismissed.
     */
    data class InsertMessage(
        val targetMessage: ChatMessage,
        val onConfirm: (MessageInsertPosition, ChatMessage.Role, String) -> Unit,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()

    /**
     * State for the Tool Configuration dialog.
     *
     * @property enabledToolsFlow Reactive flow of tools currently enabled for the session.
     * @property availableToolsFlow Reactive flow of all available tools.
     * @property mcpServersFlow Reactive flow of MCP server configurations for grouping and enablement checks.
     * @property onToggleTool Action to toggle a tool on/off.
     * @property onToggleTools Action to toggle multiple tools on/off at once.
     * @property onDismiss Action to close the dialog.
     */
    data class ToolConfig(
        val enabledToolsFlow: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>,
        val availableToolsFlow: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>,
        val mcpServersFlow: StateFlow<DataState<RepositoryError, List<eu.torvian.chatbot.app.domain.models.LocalMCPServer>>>,
        val onToggleTool: (ToolDefinition, Boolean) -> Unit,
        val onToggleTools: (List<ToolDefinition>, Boolean) -> Unit,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()

    /**
     * State for the Tool Call Details dialog.
     * When the tool call is awaiting approval, approval callbacks will be provided.
     *
     * @property toolCall The tool call to display.
     * @property onDismiss Action to close the dialog.
     * @property onApprove Optional action to approve the tool call (shown when status is AWAITING_APPROVAL).
     * @property onDeny Optional action to deny the tool call with optional reason (shown when status is AWAITING_APPROVAL).
     */
    data class ToolCallDetails(
        val toolCall: ToolCall,
        val onDismiss: () -> Unit,
        val onApprove: (() -> Unit)? = null,
        val onDeny: ((String?) -> Unit)? = null
    ) : ChatAreaDialogState()
}