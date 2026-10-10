package eu.torvian.chatbot.app.viewmodel.chat.util

import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.misc.thread.resolveThreadBranch
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Default implementation of [ThreadBuilder] that handles message thread traversal
 * and branch building with cycle detection and error handling.
 */
class DefaultThreadBuilder : ThreadBuilder {

    companion object {
        private val logger: KmpLogger = kmpLogger<DefaultThreadBuilder>()
    }

    override fun buildThreadBranch(allMessages: List<ChatMessage>, leafId: Long?): List<ChatMessage> =
        resolveThreadBranch(allMessages.associateBy { it.id }, leafId).fold(
            // The UI tolerates a corrupt branch: it shows no thread rather than failing the screen.
            ifLeft = { error ->
                logger.warn("Cannot build the thread branch from leaf $leafId: $error")
                emptyList()
            },
            ifRight = { it }
        )

    override fun findLeafOfBranch(startMessageId: Long, messageMap: Map<Long, ChatMessage>): Long? {
        var currentPathMessage: ChatMessage? = messageMap[startMessageId]
        if (currentPathMessage == null) {
            logger.warn("Starting message for branch traversal not found: $startMessageId")
            return null
        }

        var finalLeafId: Long = startMessageId
        val visitedIds = mutableSetOf<Long>() // To detect cycles and prevent infinite loops

        while (currentPathMessage?.childrenMessageIds?.isNotEmpty() == true) {
            if (!visitedIds.add(currentPathMessage.id)) {
                // Cycle detected
                logger.warn("Cycle detected in message thread path at message ID: ${currentPathMessage.id}. Aborting traversal.")
                break
            }
            // Select the first child to traverse down
            val firstChildId = currentPathMessage.childrenMessageIds.first()
            currentPathMessage = messageMap[firstChildId]
            if (currentPathMessage == null) {
                // Data inconsistency: a child ID exists but the message is not in the map
                logger.warn("Child message $firstChildId not found during branch traversal. Using last valid message as leaf.")
                break
            }
            finalLeafId = currentPathMessage.id
        }
        return finalLeafId
    }
}
