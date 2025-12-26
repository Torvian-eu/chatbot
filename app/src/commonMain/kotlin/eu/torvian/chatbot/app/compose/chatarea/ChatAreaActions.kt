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
     * Callback for when the user saves the edited message content as a new copy.
     * This creates a new sibling message with the edited content.
     */
    fun onSaveEditingAsCopy()

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
     * Callback for when the user requests to show the delete thread dialog.
     * This signals the intent to delete a message and all its replies recursively.
     * @param message The message to be deleted along with all its replies.
     */
    fun onRequestDeleteThread(message: ChatMessage)

    /**
     * Callback for when the user requests to show the insert message dialog.
     * @param message The message relative to which the new message will be inserted.
     */
    fun onRequestInsertMessage(message: ChatMessage)

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

    /**
     * Callback for when the user wants to copy a message content to the clipboard.
     * @param message The message whose content should be copied.
     */
    fun onCopyMessage(message: ChatMessage)

    /**
     * Callback for when the user wants to copy the entire message thread to the clipboard.
     */
    fun onCopyThread()

    /**
     * Callback for when the user wants to branch and continue the conversation from a specific message.
     * This creates a new assistant response as a sibling of the message's children (or as the first
     * child if the message is a leaf). The LLM context will be the thread from root to the specified message.
     * @param message The message to continue from.
     */
    fun onBranchAndContinue(message: ChatMessage)
}
