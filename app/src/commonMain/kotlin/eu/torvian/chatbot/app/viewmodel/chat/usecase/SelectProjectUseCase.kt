package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_updating_session_project
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for selecting (or deselecting) the project of the active chat session.
 *
 * This use case follows the action-only pattern: it updates the session's project via the repository
 * and lets the reactive state layer re-derive the role dropdown from the new project scoping. A
 * `null` project id deselects the project; the server clears an attached role that became illegal
 * (Session Legality Invariant) and the response-driven cache update in the repository reflects that
 * in one round-trip.
 */
class SelectProjectUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<SelectProjectUseCase>()

    /**
     * Selects a project for the current session, or deselects it when [projectId] is null.
     *
     * @param projectId The ID of the project to select, or null to clear the selection.
     */
    suspend fun execute(projectId: Long?) {
        val sessionId = state.activeSessionId.value ?: return
        logger.info("Selecting project $projectId for session $sessionId")

        sessionRepository.updateSessionProject(
            sessionId = sessionId,
            projectId = projectId
        ).fold(
            ifLeft = { repositoryError ->
                logger.error("Failed to update session project: $repositoryError")
                notificationService.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_updating_session_project
                )
            },
            ifRight = {
                logger.info("Successfully updated session project")
            }
        )
    }
}