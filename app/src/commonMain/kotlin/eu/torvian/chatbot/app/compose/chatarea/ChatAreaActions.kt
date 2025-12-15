package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Defines all UI actions that can be triggered from the main Chat Area.
 * (To be fully implemented in future PRs like 20, 21, etc.)
 */
interface ChatAreaActions {
    /**
     * Callback for when the user types in the message input field.
     * @param newText The new text content of the input field.
     */
    fun onUpdateInput(newText: String)

    /**
     * Callback for when the user sends a message.
     */
    fun onSendMessage()

    /**
     * Callback for when the user cancels the current message sending operation.
     */
    fun onCancelSendMessage()

    /**
     * Callback for when the user starts replying to a specific message.
     * @param message The message the user is replying to.
     */
    fun onStartReplyTo(message: ChatMessage)

    /**
     * Callback for when the user cancels the reply to a specific message.
     */
    fun onCancelReply()

    /**
     * Callback for when the user starts editing a specific message.
     * @param message The message the user is editing.
     */
    fun onStartEditing(message: ChatMessage)

    /**
     * Callback for when the user updates the content of the message being edited.
     * @param newText The new text content of the editing field.
     */
    fun onUpdateEditingContent(newText: String)

    /**
     * Callback for when the user saves the edited message content.
     */
    fun onSaveEditing()

    /**
     * Callback for when the user cancels the editing of a message.
     */
    fun onCancelEditing()

    /**
     * Callback for when the user requests to show the delete message dialog.
     * This signals the intent to delete, which the ViewModel will handle by showing a dialog.
     * @param message The message to be deleted.
     */
    fun onRequestDeleteMessage(message: ChatMessage)

    /**
     * Callback to dismiss any active dialog.
     */
    fun onCancelDialog()

    /**
     * Callback for when the user switches the displayed thread branch to a specific message.
     * @param messageId The ID of the message to make the new leaf of the displayed branch.
     */
    fun onSwitchBranchToMessage(messageId: Long)

    /**
     * Callback for when the user selects a specific LLM model for the session.
     * @param modelId The ID of the model to select, or null to clear selection.
     */
    fun onSelectModel(modelId: Long?)

    /**
     * Callback for when the user selects a specific settings profile for the session.
     * @param settingsId The ID of the settings profile to select, or null to clear selection.
     */
    fun onSelectSettings(settingsId: Long?)

    /**
     * Callback for when the user requests to retry loading the current chat session after a failure.
     */
    fun onRetryLoadingSession()

    /**
     * Callback for when the user requests to show the tool configuration dialog.
     */
    fun onShowToolConfig()

    /**
     * Callback for when the user requests to show the tool call details dialog.
     * @param toolCall The tool call to display details for.
     */
    fun onShowToolCallDetails(toolCall: ToolCall)

    // Will include copy actions (E2.S7, E3.S5) in future PRs
}