package eu.torvian.chatbot.server.service.core.chat.context

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.thread.ThreadBranchError
import eu.torvian.chatbot.common.misc.thread.resolveThreadBranch
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.chat.content.FileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Default [ChatContextBuilder] that reconstructs a single conversation branch from preloaded data.
 *
 * The builder returns one [ConversationContextUnit] per source `ChatMessage` so the exact ordered
 * coverage `(id, updatedAt)` of the thread is preserved for compaction eligibility and chunk
 * persistence. An assistant source expands into its assistant raw message plus all reconstructed
 * tool-result raw messages inside the same unit, so a replacement boundary can never split an
 * assistant tool call from its results.
 *
 * @property fileReferenceContentBuilder Formatter used to embed user-message file references.
 * @property toolResultContentBuilder Formatter used to serialize completed tool results.
 */
class DefaultChatContextBuilder(
    private val fileReferenceContentBuilder: FileReferenceContentBuilder,
    private val toolResultContentBuilder: ToolResultContentBuilder,
) : ChatContextBuilder {
    /**
     * Reconstructs the thread ending at [startingMessageId] and converts it into identity-bearing units.
     *
     * @param startingMessageId Message ID that anchors the end of the thread to reconstruct.
     * @param sessionMessages All messages currently known for the session.
     * @param toolCalls Persisted tool calls already loaded for the session.
     * @return Chronological context from thread root through the starting message, one unit per source.
     * @throws IllegalStateException When the starting message is missing, the parent chain is broken or
     *         cyclic, or the thread mixes messages from different sessions. A partial chain would
     *         produce incorrect compaction coverage, so it fails loudly instead.
     */
    override fun buildContext(
        startingMessageId: Long,
        sessionMessages: List<ChatMessage>,
        toolCalls: List<ToolCall>
    ): ConversationContext {
        val sortedToolCalls = toolCalls.sortedBy { it.id }
        val messageMap = sessionMessages.associateBy { it.id }
        val threadMessages = buildThreadMessages(startingMessageId, messageMap)

        val units = threadMessages.map { message ->
            when (message) {
                is ChatMessage.UserMessage -> {
                    val contentWithFileRefs = fileReferenceContentBuilder.build(message.content, message.fileReferences)
                    ConversationContextUnit(
                        source = SourceMessageSnapshot(message.id, message.updatedAt),
                        rawMessages = listOf(RawChatMessage.User(contentWithFileRefs))
                    )
                }

                is ChatMessage.AssistantMessage -> {
                    // Derive both sides of the provider transcript from the same ordered row set so a
                    // result can never be emitted without its matching assistant call. Every recorded
                    // tool call is replayed so a tool-calling assistant message never becomes a bare
                    // assistant message, which would confuse providers that reject consecutive
                    // assistant turns without an intervening tool response.
                    val pairedToolCalls = sortedToolCalls
                        .filter { it.messageId == message.id }
                    val assistantToolCalls = pairedToolCalls
                        .map { toolCall ->
                            RawChatMessage.Assistant.ToolCall(
                                id = toolCall.toolCallId,
                                name = toolCall.toolName,
                                arguments = toolCall.input
                            )
                        }.takeIf { it.isNotEmpty() }

                    val rawMessages = buildList {
                        add(
                            RawChatMessage.Assistant(
                                content = message.content,
                                toolCalls = assistantToolCalls,
                                reasoningItems = message.reasoningItems,
                                reasoningModelId = message.modelId
                            )
                        )
                        pairedToolCalls.forEach { toolCall ->
                            add(
                                RawChatMessage.Tool(
                                    content = toolResultContentBuilder.build(toolCall),
                                    toolCallId = toolCall.toolCallId ?: "",
                                    name = toolCall.toolName
                                )
                            )
                        }
                    }
                    ConversationContextUnit(
                        source = SourceMessageSnapshot(message.id, message.updatedAt),
                        rawMessages = rawMessages
                    )
                }
            }
        }

        return ConversationContext(units)
    }

    /**
     * Collects the messages of the thread ending at [startingMessageId], root first, and rejects
     * chains that cannot describe one session's history.
     *
     * @param startingMessageId Message ID that anchors the end of the thread to reconstruct.
     * @param messageMap Messages indexed by ID for efficient parent traversal.
     * @return Thread messages in chronological order from root to starting message.
     * @throws IllegalStateException When [startingMessageId] is absent, the chain is cyclic, a parent
     *         link points to a missing message, or the chain mixes messages from different sessions.
     */
    private fun buildThreadMessages(
        startingMessageId: Long,
        messageMap: Map<Long, ChatMessage>
    ): List<ChatMessage> {
        val branch = resolveThreadBranch(messageMap, startingMessageId).getOrElse { error ->
            throw IllegalStateException(
                when (error) {
                    is ThreadBranchError.LeafNotFound ->
                        "Cannot build context: starting message ${error.leafId} does not exist in the session"

                    is ThreadBranchError.BrokenChain ->
                        "Cannot build context: parent ${error.parentId} of message ${error.childId} " +
                            "does not exist in the session"

                    is ThreadBranchError.CyclicChain ->
                        "Cannot build context: cyclic parent chain detected at message ${error.messageId}"
                }
            )
        }

        // The shared walk sees one message set and cannot know which session the context belongs to, so
        // a chain whose messages disagree on the session is rejected here. A non-null starting message
        // guarantees at least one message, because the walk either returns a branch or fails above.
        val sessionId = branch.first().sessionId
        branch.firstOrNull { it.sessionId != sessionId }?.let { foreignMessage ->
            throw IllegalStateException(
                "Cannot build context: message ${foreignMessage.id} belongs to session " +
                    "${foreignMessage.sessionId}, expected $sessionId"
            )
        }

        return branch
    }
}
