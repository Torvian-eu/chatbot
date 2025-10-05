package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_switching_branch
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.api.core.UpdateSessionLeafMessageRequest

/**
 * Use case for switching the currently displayed chat branch.
 * Finds the leaf message of the target branch and persists the change via SessionRepository.
 */
class SwitchBranchUseCase(
    private val sessionRepository: SessionRepository,
    private val threadBuilder: ThreadBuilder,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<SwitchBranchUseCase>()

    /**
     * Switches the currently displayed chat branch to the one that includes the given message ID.
     * Finds the actual leaf message of this branch by traversing down the path of first children
     * starting from the provided targetMessageId. This new leaf message ID is then persisted
     * to the session record.
     *
     * @param targetMessageId The ID of the message that serves as the starting point for
     *                        determining the new displayed branch. This message itself may be
     *                        a root, middle, or leaf message in the conversation tree.
     */
    suspend fun execute(targetMessageId: Long) {
        val currentSession = state.currentSession.value ?: return
        if (currentSession.currentLeafMessageId == targetMessageId) return

        val messageMap = currentSession.messages.associateBy { it.id }

        // Use the ThreadBuilder to find the actual leaf ID
        val finalLeafId = threadBuilder.findLeafOfBranch(targetMessageId, messageMap)
        if (finalLeafId == null) {
            logger.warn("Could not determine a valid leaf for branch starting with $targetMessageId.")
            return
        }

        if (currentSession.currentLeafMessageId == finalLeafId) return // Already on this exact branch

        logger.info("Switching branch to message $targetMessageId (leaf: $finalLeafId) for session ${currentSession.id}")

        sessionRepository.updateSessionLeafMessage(
            sessionId = currentSession.id,
            request = UpdateSessionLeafMessageRequest(leafMessageId = finalLeafId)
        ).fold(
            ifLeft = { repositoryError ->
                logger.error("Switch branch repository error: ${repositoryError.message}")
                errorNotifier.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_switching_branch
                )
            },
            ifRight = {
                logger.info("Successfully switched branch to $finalLeafId")
            }
        )
    }
}
