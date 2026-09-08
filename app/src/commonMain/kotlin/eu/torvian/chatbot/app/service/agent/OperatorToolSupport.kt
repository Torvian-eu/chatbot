package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import kotlinx.serialization.json.Json

/**
 * JSON codec shared by the operator-tool implementations for decoding their tool-specific payloads.
 *
 * Unknown keys are ignored so stale server output carrying removed fields (e.g. the legacy
 * `interactive` spawn flag) decodes gracefully. A future operator tool with stricter payload needs
 * can define its own `Json` instance instead of this shared default.
 */
internal val operatorToolJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Builds an error [ChatClientEvent.ToolExecutionResult] for the given tool call.
 *
 * Operator tools report failures to the calling LLM through a structured tool error: the correlation
 * id is echoed back and a human-readable message describes what went wrong.
 *
 * @param toolCallId Correlation key of the originating tool call.
 * @param message Human-readable error message to feed back to the calling LLM.
 * @return The error result to emit on the primary socket.
 */
internal fun toolError(
    toolCallId: Long,
    message: String
): ChatClientEvent.ToolExecutionResult =
    ChatClientEvent.ToolExecutionResult(
        toolCallId = toolCallId,
        isError = true,
        errorMessage = message
    )