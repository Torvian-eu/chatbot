package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Events emitted by MessageService during streaming message processing.
 *
 * Used when the LLM streams content deltas (SSE from LLM provider like OpenAI).
 * These events are emitted via a Flow and sent to the client as SSE.
 *
 * The event flow allows the UI to:
 * - Display content as it's generated (character by character)
 * - Show when messages are saved
 * - Display tool execution progress
 * - Update in real-time as tools complete
 *
 * Event order during tool calling:
 * 1. UserMessageSaved (once)
 * 2. Loop until LLM stops calling tools:
 *    - AssistantMessageStarted (new empty assistant message written to DB)
 *    - AssistantMessageDelta (for each content chunk)
 *    - ToolCallDelta (for each tool call argument chunk)
 *    - AssistantMessageFinished (assistant message updated and complete)
 *    - ToolCallsReceived (if LLM wants to call tools)
 *    - LocalMCPToolCallReceived (for each local MCP tool)
 *    - ToolExecutionCompleted (for each tool, as it completes)
 * 3. StreamCompleted (once)
 */
sealed class MessageStreamEvent {
    /**
     * Emitted when the user's message has been processed and saved.
     *
     * @property userMessage The fully saved user message.
     * @property updatedParentMessage If provided, the parent message has been updated with the new child reference
     */
    data class UserMessageSaved(
        val userMessage: ChatMessage.UserMessage,
        val updatedParentMessage: ChatMessage?
    ) : MessageStreamEvent()

    /**
     * Emitted when the assistant's message starts.
     *
     * A new empty assistant message has been written to the database with the correct
     * parentMessageId, model, and settings. The message will be updated with content
     * as the stream progresses.
     *
     * @property assistantMessage The empty assistant message with real database ID.
     * @property updatedParentMessage The parent message has been updated with the new child reference
     */
    data class AssistantMessageStarted(
        val assistantMessage: ChatMessage.AssistantMessage,
        val updatedParentMessage: ChatMessage
    ) : MessageStreamEvent()

    /**
     * Emitted for each new content chunk from the LLM during streaming.
     *
     * These deltas should be appended to build the full message content progressively.
     * The message already exists in the database with a real ID.
     *
     * @property messageId The ID of the assistant message being updated.
     * @property deltaContent The new content chunk to append.
     */
    data class AssistantMessageDelta(val messageId: Long, val deltaContent: String) : MessageStreamEvent()

    /**
     * Emitted for each new tool call chunk from the LLM during streaming.
     *
     * Similar to AssistantMessageDelta but for tool call arguments.
     * The LLM streams tool call arguments as they are generated.
     *
     * For OpenAI:
     * - index: Position of this tool call in the array
     * - id: Unique identifier from OpenAI
     * - name: Tool name (sent once, then empty in subsequent deltas)
     * - argumentsDelta: Incremental JSON string chunks
     *
     * For Ollama:
     * - May not support streaming tool calls
     * - If supported, format is similar to OpenAI
     *
     * The UI should accumulate these deltas to show tool call progress.
     *
     * @property messageId The ID of the assistant message (real database ID)
     * @property index Position of this tool call in the array (null for Ollama)
     * @property id Unique identifier from LLM provider (null for Ollama)
     * @property name Name of the tool being called
     * @property argumentsDelta Incremental JSON string chunk for arguments
     */
    data class ToolCallDelta(
        val messageId: Long,
        val index: Int?,
        val id: String?,
        val name: String,
        val argumentsDelta: String?
    ) : MessageStreamEvent()

    /**
     * Emitted when all tool calls have been received from the LLM and saved to the database.
     *
     * This event is sent after the stream completes and tool calls have been accumulated
     * from ToolCallDelta events. The tool calls are saved with PENDING status.
     *
     * After this event, ToolExecutionCompleted events will be emitted as each
     * tool finishes executing.
     *
     * @property toolCalls List of tool calls in PENDING status
     */
    data class ToolCallsReceived(
        val toolCalls: List<ToolCall>
    ) : MessageStreamEvent()

    /**
     * Emitted when a local MCP tool call is requested from the client.
     *
     * The client should handle this event by executing the specified tool on the
     * designated local MCP server and sending a `LocalMCPToolCallResult` back to the
     * server via the appropriate channel (e.g., WebSocket).
     *
     * @property request The details of the tool call request.
     */
    data class LocalMCPToolCallReceived(
        val request: LocalMCPToolCallRequest
    ) : MessageStreamEvent()

    /**
     * Emitted when a tool call requires user approval before execution.
     *
     * The client should show an approval dialog to the user and send back a
     * `ToolCallApprovalResponse` via the appropriate channel (e.g., WebSocket).
     *
     * @property toolCall The tool call awaiting approval (with AWAITING_APPROVAL status).
     */
    data class ToolCallApprovalRequested(
        val toolCall: ToolCall
    ) : MessageStreamEvent()

    /**
     * Emitted when a single tool execution completes.
     *
     * The tool call has been updated in the database with:
     * - output: The result JSON string (or error JSON)
     * - status: Either SUCCESS or ERROR
     * - errorMessage: Set if execution failed
     * - durationMs: Execution time in milliseconds
     *
     * This event is emitted for each tool call as it completes, allowing the UI
     * to show progress when multiple tools are executed.
     *
     * @property toolCall The completed tool call with results
     */
    data class ToolExecutionCompleted(
        val toolCall: ToolCall
    ) : MessageStreamEvent()

    /**
     * Emitted when an assistant message has finished (all streaming content received and updated in the database).
     *
     * This signals the end of an assistant message for a given iteration of the tool calling loop.
     * The assistant message content has been fully received from the stream and persisted to the database.
     *
     * @property assistantMessage The completed assistant message with all streamed content
     */
    data class AssistantMessageFinished(
        val assistantMessage: ChatMessage.AssistantMessage
    ) : MessageStreamEvent()

    //    TODO: Add usage stats event
//    /**
//     * Emitted when usage statistics are available.
//     *
//     * @property promptTokens Number of tokens in the prompt/context
//     * @property completionTokens Number of tokens in the generated completion
//     * @property totalTokens Total tokens used (prompt + completion)
//     */
//    data class UsageStats(
//        val promptTokens: Int,
//        val completionTokens: Int,
//        val totalTokens: Int
//    ) : MessageStreamEvent()

    /**
     * Emitted as the final signal to indicate the end of the entire stream.
     */
    data object StreamCompleted : MessageStreamEvent()
}
