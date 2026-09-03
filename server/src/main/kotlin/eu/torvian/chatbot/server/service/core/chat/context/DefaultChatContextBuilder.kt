package eu.torvian.chatbot.server.service.core.chat.context

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
     * Walks parent links from the starting message back to the root while rejecting invalid chains.
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
        val startingMessage = messageMap[startingMessageId]
            ?: throw IllegalStateException(
                "Cannot build context: starting message $startingMessageId does not exist in the session"
            )
        val sessionId = startingMessage.sessionId

        val threadMessages = mutableListOf<ChatMessage>()
        var currentMessage: ChatMessage? = startingMessage
        val visitedIds = mutableSetOf<Long>()

        while (currentMessage != null) {
            if (!visitedIds.add(currentMessage.id)) {
                throw IllegalStateException(
                    "Cannot build context: cyclic parent chain detected at message ${currentMessage.id}"
                )
            }
            if (currentMessage.sessionId != sessionId) {
                throw IllegalStateException(
                    "Cannot build context: message ${currentMessage.id} belongs to session " +
                        "${currentMessage.sessionId}, expected $sessionId"
                )
            }
            threadMessages.add(currentMessage)
            val parentId = currentMessage.parentMessageId
            currentMessage = if (parentId == null) {
                null
            } else {
                messageMap[parentId] ?: throw IllegalStateException(
                    "Cannot build context: parent $parentId of message ${threadMessages.last().id} " +
                        "does not exist in the session"
                )
            }
        }

        threadMessages.reverse()
        return threadMessages
    }
}
