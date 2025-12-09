package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.serialization.Serializable

/**
 * Represents a discrete, server-sent event delivered during non-streaming message processing.
 * These events are sent via SSE to provide progress updates when using non-streaming LLMs.
 *
 * Each update carries an [eventType] corresponding to the 'event' field in Server-Sent Events (SSE).
 */
@Serializable
sealed interface ChatEvent {
    val eventType: String

    /**
     * Sent when the user's message has been saved to the database.
     *
     * @property userMessage The saved user message.
     * @property updatedParentMessage If provided, the parent message has been updated with the new child reference
     */
    @Serializable
    data class UserMessageSaved(
        val userMessage: ChatMessage.UserMessage,
        val updatedParentMessage: ChatMessage?
    ) : ChatEvent {
        override val eventType: String = "user_message_saved"
    }

    /**
     * Sent when an assistant message has been saved to the database.
     * This can occur multiple times during tool calling loops.
     *
     * @property assistantMessage The saved assistant message.
     * @property updatedParentMessage The parent message has been updated with the new child reference
     */
    @Serializable
    data class AssistantMessageSaved(
        val assistantMessage: ChatMessage.AssistantMessage,
        val updatedParentMessage: ChatMessage
    ) : ChatEvent {
        override val eventType: String = "assistant_message_saved"
    }

    /**
     * Sent when tool calls are received from the LLM and saved with PENDING status.
     *
     * @property toolCalls List of tool calls in PENDING status.
     */
    @Serializable
    data class ToolCallsReceived(
        val toolCalls: List<ToolCall>
    ) : ChatEvent {
        override val eventType: String = "tool_calls_received"
    }

    /**
     * Sent when a local MCP tool call is requested from the client.
     * The client should execute the tool and send back the result.
     *
     * @property request The tool call request.
     */
    @Serializable
    data class LocalMCPToolCallReceived(
        val request: LocalMCPToolCallRequest
    ) : ChatEvent {
        override val eventType: String = "local_mcp_tool_call_received"
    }

    /**
     * Sent when a single tool execution completes.
     * The tool call has been updated in the database with results.
     *
     * @property toolCall The completed tool call with results.
     */
    @Serializable
    data class ToolExecutionCompleted(
        val toolCall: ToolCall
    ) : ChatEvent {
        override val eventType: String = "tool_execution_completed"
    }

    /**
     * Sent as the final signal to indicate the end of message processing.
     */
    @Serializable
    data object StreamCompleted : ChatEvent {
        override val eventType: String = "done"
    }

    /**
     * Sent if an error occurs during message processing.
     *
     * @property error Details of the API error that occurred.
     */
    @Serializable
    data class ErrorOccurred(val error: ApiError) : ChatEvent {
        override val eventType: String = "error"
    }
}
