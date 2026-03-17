package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Groups callbacks consumed by [MessageItem].
 *
 * @param onSwitchBranchToMessage Switches the currently displayed branch to a target message.
 * @param onEditMessage Starts editing for the selected message.
 * @param onCopyMessage Copies a message content.
 * @param onRegenerateMessage Regenerates an assistant response for a message.
 * @param onReplyMessage Starts reply mode for a message.
 * @param onDeleteMessage Requests deletion of one message.
 * @param onDeleteThread Requests deletion of a message and all descendants.
 * @param onRequestInsertMessage Requests insertion relative to a message.
 * @param onUpdateEditingContent Updates text while editing a message.
 * @param onSaveEditing Saves the current edit in place.
 * @param onSaveEditingAsCopy Saves the current edit as a sibling copy.
 * @param onCancelEditing Cancels message editing.
 * @param onAddEditingFileReferences Adds file references to the message being edited.
 * @param onRemoveEditingFileReference Removes one editing file reference.
 * @param onToggleEditingFileContent Toggles content inclusion for one editing file reference.
 * @param onSetEditingBasePathOverride Sets or clears the editing base path override.
 * @param onResetEditingBasePath Resets editing base path to the common path.
 * @param onBranchAndContinue Branches from a message and continues the conversation.
 * @param onToggleMessageCollapsed Toggles collapsed/expanded state of a message body.
 * @param onShowToolCallDetails Opens details for a tool call badge.
 * @param onShowFileReferenceDetails Opens details for a file reference badge.
 */
data class MessageActions(
    val onSwitchBranchToMessage: (Long) -> Unit,
    val onEditMessage: (ChatMessage) -> Unit,
    val onCopyMessage: (ChatMessage) -> Unit,
    val onRegenerateMessage: (ChatMessage) -> Unit,
    val onReplyMessage: (ChatMessage) -> Unit,
    val onDeleteMessage: (ChatMessage) -> Unit,
    val onDeleteThread: (ChatMessage) -> Unit,
    val onRequestInsertMessage: (ChatMessage) -> Unit,
    val onUpdateEditingContent: (String) -> Unit,
    val onSaveEditing: () -> Unit,
    val onSaveEditingAsCopy: () -> Unit,
    val onCancelEditing: () -> Unit,
    val onAddEditingFileReferences: () -> Unit,
    val onRemoveEditingFileReference: (FileReference) -> Unit,
    val onToggleEditingFileContent: (FileReference, Boolean) -> Unit,
    val onSetEditingBasePathOverride: (String?) -> Unit,
    val onResetEditingBasePath: () -> Unit,
    val onBranchAndContinue: (ChatMessage) -> Unit,
    val onToggleMessageCollapsed: (Long) -> Unit,
    val onShowToolCallDetails: (ToolCall) -> Unit,
    val onShowFileReferenceDetails: (FileReference) -> Unit
)

