package eu.torvian.chatbot.server.service.core.chat.content

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
                    // Surface the tool's full output (e.g. run_command stdout/stderr) so the LLM can
                    // recover without a blind retry. The concise error message remains for humans.
                    toolCall.output?.takeIf { it.isNotBlank() }?.let { outputStr ->
                        // Try to parse as JSON for structured output; fall back to plain string.
                        try {
                            Json.parseToJsonElement(outputStr).jsonObject.let { put("output", it) }
                        } catch (_: Exception) {
                            put("output", outputStr)
                        }
                    }
                    toolCall.errorCode?.let { put("errorCode", it) }
                    // Surface structured error details (e.g. accumulated validation errors for run_command)
                    // so the LLM can understand the full context of the failure.
                    toolCall.errorDetails?.takeIf { it.isNotBlank() }?.let { errorDetailsStr ->
                        // Try to parse as JSON for structured details; fall back to plain string.
                        try {
                            Json.parseToJsonElement(errorDetailsStr).jsonObject.let { put("errorDetails", it) }
                        } catch (_: Exception) {
                            put("errorDetails", errorDetailsStr)
                        }
                    }
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