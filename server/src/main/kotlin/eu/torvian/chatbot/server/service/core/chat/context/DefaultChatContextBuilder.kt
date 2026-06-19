package eu.torvian.chatbot.server.service.core.chat.context

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.service.core.chat.content.FileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Default [ChatContextBuilder] that reconstructs a single conversation branch from preloaded data.
 *
 * @property fileReferenceContentBuilder Formatter used to embed user-message file references.
 * @property toolResultContentBuilder Formatter used to serialize completed tool results.
 */
class DefaultChatContextBuilder(
    private val fileReferenceContentBuilder: FileReferenceContentBuilder,
    private val toolResultContentBuilder: ToolResultContentBuilder,
) : ChatContextBuilder {
    /**
     * Reconstructs the thread ending at [startingMessageId] and converts it into [RawChatMessage] values.
     *
     * @param startingMessageId Message ID that anchors the end of the thread to reconstruct.
     * @param sessionMessages All messages currently known for the session.
     * @param toolCalls Persisted tool calls already loaded for the session.
     * @return Chronological raw chat context from thread root through the starting message.
     */
    override fun buildContext(
        startingMessageId: Long,
        sessionMessages: List<ChatMessage>,
        toolCalls: List<ToolCall>
    ): List<RawChatMessage> {
        val sortedToolCalls = toolCalls.sortedBy { it.id }
        val messageMap = sessionMessages.associateBy { it.id }
        val threadMessages = buildThreadMessages(startingMessageId, messageMap)
        val rawContext = mutableListOf<RawChatMessage>()

        threadMessages.forEach { message ->
            when (message) {
                is ChatMessage.UserMessage -> {
                    val contentWithFileRefs = fileReferenceContentBuilder.build(message.content, message.fileReferences)
                    rawContext.add(RawChatMessage.User(contentWithFileRefs))
                }

                is ChatMessage.AssistantMessage -> {
                    val messageToolCalls = sortedToolCalls.filter { it.messageId == message.id }
                    val assistantToolCalls = messageToolCalls.map { toolCall ->
                        RawChatMessage.Assistant.ToolCall(
                            id = toolCall.toolCallId,
                            name = toolCall.toolName,
                            arguments = toolCall.input
                        )
                    }.takeIf { it.isNotEmpty() }

                    rawContext.add(
                        RawChatMessage.Assistant(
                            content = message.content,
                            toolCalls = assistantToolCalls
                        )
                    )

                    messageToolCalls
                        .filter {
                            it.status in setOf(
                                ToolCallStatus.SUCCESS,
                                ToolCallStatus.ERROR,
                                ToolCallStatus.USER_DENIED
                            )
                        }
                        .forEach { toolCall ->
                            rawContext.add(
                                RawChatMessage.Tool(
                                    content = toolResultContentBuilder.build(toolCall),
                                    toolCallId = toolCall.toolCallId ?: "",
                                    name = toolCall.toolName
                                )
                            )
                        }
                }
            }
        }

        return rawContext
    }

    /**
     * Walks parent links from the starting message back to the root while avoiding cycles.
     *
     * @param startingMessageId Message ID that anchors the end of the thread to reconstruct.
     * @param messageMap Messages indexed by ID for efficient parent traversal.
     * @return Thread messages in chronological order from root to starting message.
     */
    private fun buildThreadMessages(
        startingMessageId: Long,
        messageMap: Map<Long, ChatMessage>
    ): List<ChatMessage> {
        val threadMessages = mutableListOf<ChatMessage>()
        var currentMessage: ChatMessage? = messageMap[startingMessageId]
        val visitedIds = mutableSetOf<Long>()

        while (currentMessage != null && !visitedIds.contains(currentMessage.id)) {
            visitedIds.add(currentMessage.id)
            threadMessages.add(currentMessage)
            currentMessage = currentMessage.parentMessageId?.let { messageMap[it] }
        }

        threadMessages.reverse()
        return threadMessages
    }
}