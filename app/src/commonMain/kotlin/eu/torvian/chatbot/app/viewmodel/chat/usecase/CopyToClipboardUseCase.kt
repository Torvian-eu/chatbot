package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Use case for copying message content to the system clipboard.
 * Handles both single message and entire thread copying operations.
 */
class CopyToClipboardUseCase(
    private val state: ChatState,
    private val clipboardService: ClipboardService,
    private val notificationService: NotificationService
) {
    private val logger = kmpLogger<CopyToClipboardUseCase>()

    /**
     * Copies the content of a single message to the system clipboard.
     *
     * @param message The message whose content should be copied.
     */
    suspend fun copyMessage(message: ChatMessage) {
        try {
            clipboardService.copyToClipboard(message.content)
            notificationService.genericSuccess("Message copied to clipboard")
        } catch (e: Exception) {
            logger.error("Failed to copy message to clipboard", e)
            notificationService.genericError("Failed to copy to clipboard")
        }
    }

    /**
     * Copies the entire currently displayed message thread to the system clipboard.
     * Messages are formatted with role labels and separated by double newlines.
     */
    suspend fun copyThread() {
        try {
            val messages = state.displayedMessages.value

            if (messages.isEmpty()) {
                notificationService.genericWarning("No messages to copy")
                return
            }

            val formattedThread = messages.joinToString("\n\n") { message ->
                val roleLabel = when (message.role) {
                    ChatMessage.Role.USER -> "User"
                    ChatMessage.Role.ASSISTANT -> "Assistant"
                }
                "$roleLabel: ${message.content}"
            }

            clipboardService.copyToClipboard(formattedThread)
            notificationService.genericSuccess("Thread copied to clipboard")
        } catch (e: Exception) {
            logger.error("Failed to copy thread to clipboard", e)
            notificationService.genericError("Failed to copy thread to clipboard")
        }
    }
}

