package eu.torvian.chatbot.common.misc.thread

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies the shared thread-branch walk of [resolveThreadBranch] and the failure reasons it reports.
 */
class ThreadBranchResolverTest {

    /** Session every message of a fixture belongs to, unless a case deliberately differs. */
    private val sessionId = 44L

    /**
     * Builds a user message with only the threading fields the resolver reads.
     */
    private fun message(id: Long, parentId: Long?): ChatMessage.UserMessage =
        ChatMessage.UserMessage(
            id = id,
            sessionId = sessionId,
            content = "Message $id",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            parentMessageId = parentId
        )

    /**
     * Indexes messages by ID the way a caller would.
     */
    private fun indexOf(vararg messages: ChatMessage): Map<Long, ChatMessage> =
        messages.associateBy { it.id }

    /**
     * A `null` leaf means the session has no thread, so the branch is empty and no error is raised.
     */
    @Test
    fun `null leaf resolves to an empty branch`() {
        val result = resolveThreadBranch(indexOf(message(1L, null)), null)

        assertEquals(emptyList(), result.getOrElse { error("Expected an empty branch but got $it") })
    }

    /**
     * A `null` leaf is not an error even when the message set is empty.
     */
    @Test
    fun `null leaf resolves to an empty branch for an empty message set`() {
        val result = resolveThreadBranch(emptyMap(), null)

        assertEquals(emptyList(), result.getOrElse { error("Expected an empty branch but got $it") })
    }

    /**
     * A linear chain is returned root first, including messages of other branches that are not on it.
     */
    @Test
    fun `linear chain resolves from root to leaf`() {
        val messages = indexOf(
            message(1L, null),
            message(2L, 1L),
            message(3L, 2L),
            message(4L, 2L)
        )

        val branch = resolveThreadBranch(messages, 3L).getOrElse { error("Expected a branch but got $it") }

        assertEquals(listOf(1L, 2L, 3L), branch.map { it.id })
    }

    /**
     * A root message that is also the leaf resolves to itself.
     */
    @Test
    fun `root message resolves to a single-message branch`() {
        val branch = resolveThreadBranch(indexOf(message(1L, null)), 1L)
            .getOrElse { error("Expected a branch but got $it") }

        assertEquals(listOf(1L), branch.map { it.id })
    }

    /**
     * A requested leaf that is absent from a populated message set is reported as such.
     */
    @Test
    fun `absent leaf is reported as not found`() {
        val result = resolveThreadBranch(indexOf(message(1L, null), message(2L, 1L)), 9L)

        assertEquals(ThreadBranchError.LeafNotFound(9L), result.swap().getOrNull())
    }

    /**
     * An empty message set reports the requested leaf as missing rather than as a broken chain.
     */
    @Test
    fun `empty message set reports the leaf as not found`() {
        val result = resolveThreadBranch(emptyMap(), 9L)

        assertEquals(ThreadBranchError.LeafNotFound(9L), result.swap().getOrNull())
    }

    /**
     * A parent link that points outside the message set reports both ends of the broken link.
     */
    @Test
    fun `missing parent is reported with the child and the parent id`() {
        val result = resolveThreadBranch(indexOf(message(1L, null), message(3L, 99L)), 3L)

        assertEquals(ThreadBranchError.BrokenChain(childId = 3L, parentId = 99L), result.swap().getOrNull())
    }

    /**
     * A missing parent is reported even when the leaf itself exists, so the caller sees the gap.
     */
    @Test
    fun `missing parent deeper in the chain is reported`() {
        val result = resolveThreadBranch(indexOf(message(2L, 1L), message(3L, 2L)), 3L)

        assertEquals(ThreadBranchError.BrokenChain(childId = 2L, parentId = 1L), result.swap().getOrNull())
    }

    /**
     * A cycle is reported at the revisited message, and no partial branch is produced.
     */
    @Test
    fun `cyclic chain is reported at the revisited message`() {
        val result = resolveThreadBranch(indexOf(message(1L, 2L), message(2L, 1L)), 2L)

        assertEquals(ThreadBranchError.CyclicChain(2L), result.swap().getOrNull())
    }

    /**
     * A message that is its own parent is reported as a cycle.
     */
    @Test
    fun `self-referencing message is reported as a cycle`() {
        val result = resolveThreadBranch(indexOf(message(1L, 1L)), 1L)

        assertEquals(ThreadBranchError.CyclicChain(1L), result.swap().getOrNull())
    }
}
