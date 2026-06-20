package eu.torvian.chatbot.server.service.core.chat.context

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Reconstructs the threaded chat context that is sent to the LLM.
 */
interface ChatContextBuilder {
    /**
     * Builds the raw chat context for a single thread ending at the starting message.
     *
     * @param startingMessageId Message ID that anchors the end of the thread to reconstruct.
     * @param sessionMessages All messages currently known for the session.
     * @param toolCalls Persisted tool calls already loaded for the session.
     * @return Chronological raw chat context from thread root through the starting message.
     */
    fun buildContext(
        startingMessageId: Long,
        sessionMessages: List<ChatMessage>,
        toolCalls: List<ToolCall>
    ): List<RawChatMessage>
}