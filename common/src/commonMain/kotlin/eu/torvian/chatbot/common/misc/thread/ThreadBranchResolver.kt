package eu.torvian.chatbot.common.misc.thread

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Reason why the root→leaf branch of a requested leaf could not be resolved.
 *
 * The walk only detects what is visible in the message set it was given; a caller that knows more than
 * that (for example which session the branch must belong to) has to validate the returned branch itself.
 */
sealed interface ThreadBranchError {
    /**
     * The requested leaf is absent from the message set, which also covers an empty set.
     *
     * @property leafId Leaf message ID that was requested.
     */
    data class LeafNotFound(val leafId: Long) : ThreadBranchError

    /**
     * A message links to a parent that is absent from the message set, so the branch is incomplete.
     *
     * @property childId Message whose parent link could not be followed.
     * @property parentId Missing parent message ID referenced by [childId].
     */
    data class BrokenChain(val childId: Long, val parentId: Long) : ThreadBranchError

    /**
     * The walk reached a message it had already visited, so no root→leaf order exists.
     *
     * @property messageId Revisited message ID at which the walk stopped.
     */
    data class CyclicChain(val messageId: Long) : ThreadBranchError
}

/**
 * Resolves the branch ending at [leafId], root message first.
 *
 * A `null` [leafId] yields an empty branch: a session without a current leaf has no thread, which is not an
 * integrity failure. Every other reason the walk stops is reported as a [ThreadBranchError] instead of
 * being resolved here, so each caller keeps its own policy (log and continue, or fail loudly).
 *
 * [messagesById] is expected to hold the messages of a single session.
 *
 * @param messagesById Session messages indexed by ID, consulted once per visited message.
 * @param leafId Message whose root→leaf branch is wanted, or `null` when no leaf is selected.
 * @return The branch from root to leaf, or the reason it could not be resolved.
 */
fun resolveThreadBranch(
    messagesById: Map<Long, ChatMessage>,
    leafId: Long?
): Either<ThreadBranchError, List<ChatMessage>> = either {
    if (leafId == null) return@either emptyList()

    val branch = mutableListOf<ChatMessage>()
    val visitedIds = mutableSetOf<Long>()
    var currentId: Long? = leafId
    // ID of the message whose parent link is being followed, absent while the requested leaf is looked up.
    var childId: Long? = null

    while (currentId != null) {
        val messageId = currentId
        ensure(visitedIds.add(messageId)) { ThreadBranchError.CyclicChain(messageId) }

        val message = messagesById[messageId] ?: raise(
            if (childId == null) {
                ThreadBranchError.LeafNotFound(messageId)
            } else {
                ThreadBranchError.BrokenChain(childId = childId, parentId = messageId)
            }
        )

        branch.add(message)
        childId = messageId
        currentId = message.parentMessageId
    }

    branch.reverse()
    branch
}
