package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.ConversationRepository
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Use case for inserting a new message into the chat.
 * Supports both inserting relative to an existing message and creating new root messages.
 *
 * Owns the insert dialog, so the dialog and the insertion it confirms stay together. The turn is
 * checked again when the user confirms, because a generation can start while the dialog is open.
 *
 * @property state Shared chat state the dialog is published into and the turn guard is read from.
 * @property conversationRepository Repository the insertion is sent to.
 * @property scope Scope the confirmed insertion runs on.
 * @property notificationService Notification sink for repository errors.
 */
class InsertMessageUseCase(
    private val state: ChatState,
    private val conversationRepository: ConversationRepository,
    private val scope: CoroutineScope,
    private val notificationService: NotificationService
) {

    /**
     * Shows the insert message dialog with pre-bound actions.
     *
     * @param target Message the new message is inserted relative to.
     */
    fun requestInsertMessage(target: ChatMessage) {
        state.setDialogState(
            ChatAreaDialogState.InsertMessage(
                targetMessage = target,
                onConfirm = { position, role, content ->
                    confirmInsert(target.id, position, role, content)
                },
                onDismiss = { state.cancelDialog() }
            )
        )
    }

    /**
     * Executes the insert message operation.
     *
     * @param targetMessageId The ID of the target message to insert relative to. If null, inserts a new root message (position is ignored).
     * @param position The position relative to the target (ABOVE, BELOW, or APPEND). Ignored if targetMessageId is null.
     * @param role The role of the new message (USER or ASSISTANT)
     * @param content The content of the new message
     */
    private fun execute(
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String
    ) {
        val session = state.currentSession.value ?: return
        val modelId = state.currentModel.value?.id
        val settingsId = state.currentSettings.value?.id

        scope.launch {
            conversationRepository.insertMessage(
                sessionId = session.id,
                targetMessageId = targetMessageId,
                position = position,
                role = role,
                content = content,
                modelId = if (role == ChatMessage.Role.ASSISTANT) modelId else null,
                settingsId = if (role == ChatMessage.Role.ASSISTANT) settingsId else null
            )
                .onLeft { error ->
                    notificationService.repositoryError(error, "Failed to insert message")
                }
            // Success is handled by repository updating the session flow
        }
    }

    /**
     * Runs the insertion the dialog asked for.
     *
     * @param targetMessageId Message the new message is inserted relative to.
     * @param position Where the new message is placed relative to [targetMessageId].
     * @param role Role of the new message.
     * @param content Content of the new message.
     */
    private fun confirmInsert(
        targetMessageId: Long,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String
    ) {
        // Inserting while a generation is streaming could corrupt the active conversation.
        if (state.isTurnActive) return
        state.cancelDialog()
        execute(
            targetMessageId = targetMessageId,
            position = position,
            role = role,
            content = content
        )
    }
}
