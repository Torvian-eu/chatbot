package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Default [ToolResultContentBuilder] that preserves the legacy tool-result formatting behavior.
 */
class DefaultToolResultContentBuilder : ToolResultContentBuilder {
    /**
     * Serializes the stored tool outcome using the historical success, error, and denial fallbacks.
     *
     * @param toolCall Persisted tool-call record whose result should be serialized.
     * @return Serialized tool-result payload for LLM context reconstruction.
     */
    override fun build(toolCall: ToolCall): String {
        return when (toolCall.status) {
            ToolCallStatus.ERROR -> {
                buildJsonObject {
                    put("error", toolCall.errorMessage ?: "Unknown error")
                }.toString()
            }

            ToolCallStatus.USER_DENIED -> {
                buildJsonObject {
                    put("user_denied", "Tool call was denied by user.")
                    put("reason", toolCall.denialReason ?: "No reason provided")
                }.toString()
            }

            else -> {
                val output = toolCall.output
                if (output.isNullOrBlank()) {
                    buildJsonObject { }.toString()
                } else {
                    output
                }
            }
        }
    }
}