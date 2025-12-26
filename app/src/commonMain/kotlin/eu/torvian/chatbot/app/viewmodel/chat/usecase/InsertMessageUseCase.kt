package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Use case for inserting a new message into the chat.
 * Supports both inserting relative to an existing message and creating new root messages.
 */
class InsertMessageUseCase(
    private val state: ChatState,
    private val sessionRepository: SessionRepository,
    private val notificationService: NotificationService
) {
    /**
     * Executes the insert message operation.
     *
     * @param scope Coroutine scope to launch the operation
     * @param targetMessageId The ID of the target message to insert relative to. If null, inserts a new root message (position is ignored).
     * @param position The position relative to the target (ABOVE, BELOW, or APPEND). Ignored if targetMessageId is null.
     * @param role The role of the new message (USER or ASSISTANT)
     * @param content The content of the new message
     */
    fun execute(
        scope: CoroutineScope,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String
    ) {
        val session = state.currentSession.value ?: return
        val modelId = session.currentModelId
        val settingsId = session.currentSettingsId

        scope.launch {
            sessionRepository.insertMessage(
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
}
