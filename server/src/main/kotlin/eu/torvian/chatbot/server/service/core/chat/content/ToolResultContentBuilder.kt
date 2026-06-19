package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Builds serialized tool-result payloads for chat context reconstruction.
 */
interface ToolResultContentBuilder {
    /**
     * Builds the tool-result content that should be sent back to the LLM.
     *
     * @param toolCall Persisted tool-call record whose result should be serialized.
     * @return Serialized tool-result payload that preserves the existing chat-context contract.
     */
    fun build(toolCall: ToolCall): String
}