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
 */
class InsertMessageUseCase(
    private val state: ChatState,
    private val sessionRepository: SessionRepository,
    private val notificationService: NotificationService
) {
    fun execute(
        scope: CoroutineScope,
        targetMessageId: Long,
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
