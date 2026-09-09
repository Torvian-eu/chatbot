package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_projects
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for loading the current user's projects into the reactive project repository.
 *
 * Used both during session load (so the top-bar project selector has the full catalog) and by the
 * top-bar retry action. The repository StateFlow updates all consumers reactively on success.
 */
class LoadProjectsUseCase(
    private val projectRepository: ProjectRepository,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<LoadProjectsUseCase>()

    /**
     * Triggers a load of all projects for the current user.
     *
     * @return True when the load succeeded, false otherwise (an error notification is emitted).
     */
    suspend fun execute(): Boolean {
        return projectRepository.loadProjects().fold(
            ifLeft = { error ->
                logger.error("Failed to load projects: $error")
                notificationService.repositoryError(
                    error = error,
                    shortMessageRes = Res.string.error_loading_projects
                )
                false
            },
            ifRight = {
                logger.debug("Projects loaded")
                true
            }
        )
    }
}