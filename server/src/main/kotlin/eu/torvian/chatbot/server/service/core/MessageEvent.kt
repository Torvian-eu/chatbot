package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Events emitted by MessageService during non-streaming message processing.
 * 
 * Used when the LLM does not stream content (returns complete responses).
 * These events are emitted via a Flow and sent to the client as SSE.
 * 
 * The event flow allows the UI to:
 * - Show when messages are saved
 * - Display tool execution progress
 * - Update in real-time as tools complete
 * 
 * Event order during tool calling:
 * 1. UserMessageSaved (once)
 * 2. Loop until LLM stops calling tools:
 *    - AssistantMessageSaved (LLM response with tool calls or final content)
 *    - ToolCallsReceived (if LLM wants to call tools)
 *    - ToolExecutionCompleted (for each tool, as it completes)
 * 3. StreamCompleted (once)
 */
sealed class MessageEvent {
    /**
     * Emitted when the user's message has been saved to the database.
     * 
     * This is the first event in the processing flow, confirming that
     * the user's input has been persisted.
     * 
     * @property userMessage The fully saved user message with its ID
     * @property updatedParentMessage If provided, the parent message has been updated with the new child reference
     */
    data class UserMessageSaved(
        val userMessage: ChatMessage.UserMessage,
        val updatedParentMessage: ChatMessage?
    ) : MessageEvent()

    /**
     * Emitted when an assistant message has been saved to the database.
     * 
     * This can occur multiple times during tool calling loops:
     * - First time: LLM decides to call tools (may have content explaining intent)
     * - Subsequent times: LLM generates another response after seeing tool results
     * - Final time: LLM generates the final answer
     * 
     * Each assistant message is a separate database record, preserving the
     * conversation ordering for LLM caching.
     * 
     * @property assistantMessage The fully saved assistant message with its ID
     * @property updatedParentMessage The parent message has been updated with the new child reference
     */
    data class AssistantMessageSaved(
        val assistantMessage: ChatMessage.AssistantMessage,
        val updatedParentMessage: ChatMessage
    ) : MessageEvent()

    /**
     * Emitted when tool calls are received from the LLM and saved to the database.
     * 
     * The tool calls are initially saved with PENDING status before execution begins.
     * The list contains all tool calls from the LLM's response (may be multiple).
     * 
     * After this event, ToolExecutionCompleted events will be emitted as each
     * tool finishes executing.
     * 
     * @property toolCalls List of tool calls in PENDING status
     */
    data class ToolCallsReceived(
        val toolCalls: List<ToolCall>
    ) : MessageEvent()

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
     * to show progress when multiple tools are executed in parallel.
     * 
     * @property toolCall The completed tool call with results
     */
    data class ToolExecutionCompleted(
        val toolCall: ToolCall
    ) : MessageEvent()

    /**
     * Emitted as the final signal to indicate the end of the entire event stream.
     * 
     * After this event, no more events will be emitted for this message processing session.
     * The client can close the SSE connection.
     */
    data object StreamCompleted : MessageEvent()
}

fun MessageEvent.toChatEvent(): ChatEvent {
    return when (this) {
        is MessageEvent.UserMessageSaved -> ChatEvent.UserMessageSaved(
            this.userMessage,
            this.updatedParentMessage
        )

        is MessageEvent.AssistantMessageSaved -> ChatEvent.AssistantMessageSaved(
            this.assistantMessage,
            this.updatedParentMessage
        )

        is MessageEvent.ToolCallsReceived -> ChatEvent.ToolCallsReceived(
            this.toolCalls
        )

        is MessageEvent.ToolExecutionCompleted -> ChatEvent.ToolExecutionCompleted(
            this.toolCall
        )

        is MessageEvent.StreamCompleted -> ChatEvent.StreamCompleted
    }
}