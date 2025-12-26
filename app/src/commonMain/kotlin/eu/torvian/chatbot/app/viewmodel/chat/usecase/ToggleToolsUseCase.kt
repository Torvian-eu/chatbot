package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Use case for toggling tools on/off for the current chat session.
 * Handles both single and batch tool toggle operations.
 */
class ToggleToolsUseCase(
    private val state: ChatState,
    private val toolRepository: ToolRepository,
    private val notificationService: NotificationService
) {

    /**
     * Toggles a single tool for the current session.
     *
     * @param toolDefinition The tool to toggle
     * @param enabled Whether to enable or disable the tool
     */
    suspend fun toggleTool(toolDefinition: ToolDefinition, enabled: Boolean) {
        val sessionId = state.activeSessionId.value ?: return

        toolRepository.setToolEnabledForSession(sessionId, toolDefinition, enabled).mapLeft { error ->
            notificationService.repositoryError(
                error = error,
                shortMessage = "Failed to toggle tool"
            )
        }
    }

    /**
     * Toggles multiple tools for the current session in a batch operation.
     *
     * @param toolDefinitions The list of tools to toggle
     * @param enabled Whether to enable or disable the tools
     */
    suspend fun toggleTools(toolDefinitions: List<ToolDefinition>, enabled: Boolean) {
        val sessionId = state.activeSessionId.value ?: return

        toolRepository.setToolsEnabledForSession(sessionId, toolDefinitions, enabled).mapLeft { error ->
            notificationService.repositoryError(
                error = error,
                shortMessage = "Failed to toggle tools"
            )
        }
    }
}

