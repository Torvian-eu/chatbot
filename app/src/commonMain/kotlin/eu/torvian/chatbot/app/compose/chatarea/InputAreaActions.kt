package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.common.models.core.FileReference

/**
 * Groups callbacks used by [InputArea] to reduce parameter count.
 *
 * @param onUpdateInput Updates the input text.
 * @param onSendMessage Sends the current input as a message.
 * @param onCancelSendMessage Cancels the current send/stream operation.
 * @param onCancelReply Clears the current reply target.
 * @param onToggleExpansion Toggles compact/expanded input mode.
 * @param onAddFileReferences Opens the file picker to add attached files.
 * @param onRemoveFileReference Removes one attached file reference.
 * @param onShowFileReferenceDetails Opens details for one attached file reference.
 * @param onManageFileReferences Opens the attached-file management UI.
 */
data class InputAreaActions(
    val onUpdateInput: (String) -> Unit,
    val onSendMessage: () -> Unit,
    val onCancelSendMessage: () -> Unit,
    val onCancelReply: () -> Unit,
    val onToggleExpansion: (() -> Unit)? = null,
    val onAddFileReferences: () -> Unit,
    val onRemoveFileReference: (FileReference) -> Unit,
    val onShowFileReferenceDetails: (FileReference) -> Unit,
    val onManageFileReferences: () -> Unit
)

