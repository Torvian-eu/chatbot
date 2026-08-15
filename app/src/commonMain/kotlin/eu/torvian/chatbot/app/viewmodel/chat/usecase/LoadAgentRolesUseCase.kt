package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_agent_roles
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService

/**
 * Use case for loading the current user's agent roles into the reactive role repository.
 *
 * Used both during session load (so the top-bar selector has the full role catalog) and by the
 * management tab. The repository StateFlow updates all consumers reactively on success.
 */
class LoadAgentRolesUseCase(
    private val agentRoleRepository: AgentRoleRepository,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<LoadAgentRolesUseCase>()

    /**
     * Triggers a load of all agent roles for the current user.
     *
     * @return True when the load succeeded, false otherwise (an error notification is emitted).
     */
    suspend fun execute(): Boolean {
        return agentRoleRepository.loadRoles().fold(
            ifLeft = { error ->
                logger.error("Failed to load agent roles: $error")
                notificationService.repositoryError(
                    error = error,
                    shortMessageRes = Res.string.error_loading_agent_roles
                )
                false
            },
            ifRight = {
                logger.debug("Agent roles loaded")
                true
            }
        )
    }
}
