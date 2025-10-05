package eu.torvian.chatbot.app.viewmodel.chat.util

import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Default implementation of [ThreadBuilder] that handles message thread traversal
 * and branch building with cycle detection and error handling.
 */
class DefaultThreadBuilder : ThreadBuilder {

    companion object {
        private val logger: KmpLogger = kmpLogger<DefaultThreadBuilder>()
    }

    override fun buildThreadBranch(allMessages: List<ChatMessage>, leafId: Long?): List<ChatMessage> {
        if (leafId == null || allMessages.isEmpty()) return emptyList()

        val messageMap = allMessages.associateBy { it.id }
        val branch = mutableListOf<ChatMessage>()
        var currentMessageId: Long? = leafId
        val visitedIds = mutableSetOf<Long>() // Added for cycle detection

        // Traverse upwards from the leaf to the root
        while (currentMessageId != null) {
            val message = messageMap[currentMessageId]
            if (message == null) {
                // This indicates a data inconsistency
                logger.warn("Warning: Could not find message with ID $currentMessageId while building branch. Aborting traversal.")
                return emptyList() // Return empty as the branch is incomplete/corrupt
            }
            if (!visitedIds.add(message.id)) {
                // Cycle detected during upward traversal
                logger.warn("Warning: Cycle detected in message thread path during upward traversal at message ID: ${message.id}. Aborting traversal.")
                return emptyList() // Return empty as the branch is corrupted
            }
            branch.add(message)
            currentMessageId = message.parentMessageId
        }

        // Reverse the list to get the correct order (root to leaf)
        return branch.reversed()
    }

    override fun findLeafOfBranch(startMessageId: Long, messageMap: Map<Long, ChatMessage>): Long? {
        var currentPathMessage: ChatMessage? = messageMap[startMessageId]
        if (currentPathMessage == null) {
            println("Warning: Starting message for branch traversal not found: $startMessageId")
            return null
        }

        var finalLeafId: Long = startMessageId
        val visitedIds = mutableSetOf<Long>() // To detect cycles and prevent infinite loops

        while (currentPathMessage?.childrenMessageIds?.isNotEmpty() == true) {
            if (!visitedIds.add(currentPathMessage.id)) {
                // Cycle detected
                println("Warning: Cycle detected in message thread path at message ID: ${currentPathMessage.id}. Aborting traversal.")
                break
            }
            // Select the first child to traverse down
            val firstChildId = currentPathMessage.childrenMessageIds.first()
            currentPathMessage = messageMap[firstChildId]
            if (currentPathMessage == null) {
                // Data inconsistency: a child ID exists but the message is not in the map
                println("Warning: Child message $firstChildId not found during branch traversal. Using last valid message as leaf.")
                break
            }
            finalLeafId = currentPathMessage.id
        }
        return finalLeafId
    }
}
