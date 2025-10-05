package eu.torvian.chatbot.app.viewmodel.chat.util

import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Service responsible for building thread branches from flat message lists
 * and finding leaf messages in message trees.
 *
 * This utility handles the complex logic of traversing message hierarchies
 * to construct the correct display order for threaded conversations.
 */
interface ThreadBuilder {

    /**
     * Builds the list of messages for a specific branch from root to leaf.
     * Operates on the flat list of messages provided.
     *
     * @param allMessages The flat list of all messages in the session.
     * @param leafId The ID of the desired leaf message for the branch.
     * @return An ordered list of messages from the root of the branch down to the leaf.
     */
    fun buildThreadBranch(allMessages: List<ChatMessage>, leafId: Long?): List<ChatMessage>

    /**
     * Finds the ultimate leaf message ID by traversing down the
     * first child path from a given starting message ID.
     *
     * @param startMessageId The ID of the message to start the traversal from.
     * @param messageMap A map of all messages in the session for efficient lookup.
     * @return The ID of the leaf message found, or null if the startMessageId is invalid or a cycle/broken link is detected.
     */
    fun findLeafOfBranch(startMessageId: Long, messageMap: Map<Long, ChatMessage>): Long?
}
