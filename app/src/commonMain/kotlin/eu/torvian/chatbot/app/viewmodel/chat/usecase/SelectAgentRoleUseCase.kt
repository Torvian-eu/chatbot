package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_agent_role
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for selecting (or deselecting) the agent role of the active chat session.
 *
 * This use case follows the action-only pattern: it updates the session's role via the repository
 * and lets the reactive state layer re-derive the role's model/settings/tools automatically.
 * A `null` role id deselects the role and makes the session inert until another role is selected.
 */
class SelectAgentRoleUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<SelectAgentRoleUseCase>()

    /**
     * Selects an agent role for the current session, or deselects it when [agentRoleId] is null.
     *
     * @param agentRoleId The ID of the role to select, or null to clear the selection.
     */
    suspend fun execute(agentRoleId: Long?) {
        val sessionId = state.activeSessionId.value ?: return
        logger.info("Selecting agent role $agentRoleId for session $sessionId")

        sessionRepository.updateSessionAgentRole(
            sessionId = sessionId,
            agentRoleId = agentRoleId
        ).fold(
            ifLeft = { repositoryError ->
                logger.error("Failed to update session agent role: $repositoryError")
                notificationService.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_updating_session_agent_role
                )
            },
            ifRight = {
                logger.info("Successfully updated session agent role")
            }
        )
    }
}
