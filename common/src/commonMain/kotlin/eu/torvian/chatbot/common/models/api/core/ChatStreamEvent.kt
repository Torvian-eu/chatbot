package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.serialization.Serializable

/**
 * Represents a discrete, server-sent event delivered as part of a streaming chat completion.
 * These events are designed for incremental server-to-client communication, allowing clients
 * to build the chat message dynamically as it's generated.
 *
 * Each update carries an [eventType] corresponding to the 'event' field in Server-Sent Events (SSE).
 */
@Serializable
sealed interface ChatStreamEvent {
    val eventType: String

    /**
     * Sent once the user's message has been successfully processed and saved on the server.
     * This typically signals the start of the assistant's response generation on the client side.
     *
     * @property userMessage The fully saved user message.
     * @property updatedParentMessage If provided, the parent message has been updated with the new child reference
     */
    @Serializable
    data class UserMessageSaved(
        val userMessage: ChatMessage.UserMessage,
        val updatedParentMessage: ChatMessage?
    ) : ChatStreamEvent {
        override val eventType: String = "user_message"
    }

    /**
     * Sent when the assistant's message generation begins.
     * Provides an initial `ChatMessage.AssistantMessage` object with a real database ID,
     * but initially with empty content.
     *
     * @property assistantMessage The initial assistant message from the database.
     * @property updatedParentMessage The parent message has been updated with the new child reference
     */
    @Serializable
    data class AssistantMessageStart(
        val assistantMessage: ChatMessage.AssistantMessage,
        val updatedParentMessage: ChatMessage
    ) : ChatStreamEvent {
        override val eventType: String = "assistant_message_start"
    }

    /**
     * Sent for each new content chunk received from the Large Language Model (LLM).
     * Clients should append `deltaContent` to the existing message identified by `messageId`.
     *
     * @property messageId The database ID of the assistant message being updated.
     * @property deltaContent The new content chunk to append.
     */
    @Serializable
    data class AssistantMessageDelta(val messageId: Long, val deltaContent: String) : ChatStreamEvent {
        override val eventType: String = "assistant_message_delta"
    }

    /**
     * Sent for each new tool call chunk received from the LLM during streaming.
     * The LLM streams tool call arguments as they are generated.
     *
     * @property messageId The database ID of the assistant message.
     * @property index Position of this tool call in the array (null for Ollama).
     * @property id Unique identifier from LLM provider (null for Ollama).
     * @property name Name of the tool being called.
     * @property argumentsDelta Incremental JSON string chunk for arguments.
     */
    @Serializable
    data class ToolCallDelta(
        val messageId: Long,
        val index: Int?,
        val id: String?,
        val name: String,
        val argumentsDelta: String?
    ) : ChatStreamEvent {
        override val eventType: String = "tool_call_delta"
    }

    /**
     * Sent when all tool calls have been received from the LLM and saved to the database.
     * The tool calls are saved with PENDING status.
     *
     * @property toolCalls List of tool calls in PENDING status.
     */
    @Serializable
    data class ToolCallsReceived(
        val toolCalls: List<ToolCall>
    ) : ChatStreamEvent {
        override val eventType: String = "tool_calls_received"
    }

    /**
     * Sent when a local MCP tool call is requested from the client.
     * The client should execute the tool and send back the result.
     *
     * @property request The details of the tool call request.
     */
    @Serializable
    data class LocalMCPToolCallReceived(
        val request: LocalMCPToolCallRequest
    ) : ChatStreamEvent {
        override val eventType: String = "local_mcp_tool_call_received"
    }

    /**
     * Sent when a tool call requires user approval before execution.
     * The client should show an approval dialog and send back the user's decision.
     *
     * @property toolCall The tool call awaiting approval (with AWAITING_APPROVAL status).
     */
    @Serializable
    data class ToolCallApprovalRequested(
        val toolCall: ToolCall
    ) : ChatStreamEvent {
        override val eventType: String = "tool_call_approval_requested"
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
    ) : ChatStreamEvent {
        override val eventType: String = "tool_execution_completed"
    }

    /**
     * Sent when the assistant's message generation is complete and the message has been persisted.
     * This signals the end of a specific assistant message's stream.
     *
     * @property assistantMessage The final, persisted assistant message with full content.
     */
    @Serializable
    data class AssistantMessageEnd(
        val assistantMessage: ChatMessage.AssistantMessage
    ) : ChatStreamEvent {
        override val eventType: String = "assistant_message_end"
    }

    /**
     * Sent as the final signal to indicate the end of the entire chat completion stream.
     * After this event, no further events for this specific completion will be sent on this stream.
     */
    @Serializable
    data object StreamCompleted : ChatStreamEvent {
        override val eventType: String = "done"
    }

    /**
     * Sent if a non-fatal error occurs during the streaming process that doesn't terminate the connection.
     * The client should display this error appropriately, but the stream may continue if applicable.
     *
     * @property error Details of the API error that occurred.
     */
    @Serializable
    data class ErrorOccurred(val error: ApiError) : ChatStreamEvent {
        override val eventType: String = "error"
    }
}