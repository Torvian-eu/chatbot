package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for processing new chat messages.
 */
interface ChatService {
    /**
     * Validates a new message request and prepares the configuration.
     *
     * @param sessionId The ID of the session
     * @param parentMessageId Optional parent message ID
     * @param isStreaming Whether the message is being processed in streaming mode
     * @return Either a validation error or a pair of (ChatSession, LLMConfig)
     */
    suspend fun validateProcessNewMessageRequest(
        sessionId: Long,
        parentMessageId: Long?,
        isStreaming: Boolean
    ): Either<ValidateNewMessageError, Pair<ChatSession, LLMConfig>>

    /**
     * Processes a new incoming user message with a non-streaming LLM.
     *
     * Both the user message and assistant messages are saved to the database,
     * with events emitted via a Flow as processing progresses. This allows
     * the client to receive updates in real-time via SSE.
     *
     * When tool calling is enabled and the LLM requests tool execution, the
     * method implements a tool calling loop that creates a NEW assistant message
     * for each LLM response (preserving message ordering for LLM caching).
     *
     * Event flow:
     * 1. UserMessageSaved - When user message is saved
     * 2. Loop (if tool calling occurs):
     *    a. AssistantMessageSaved - LLM response (may include tool call intent)
     *    b. ToolCallsReceived - Tool calls saved with PENDING status
     *    c. LocalMCPToolCallReceived - For each local MCP tool that needs client-side execution
     *    d. ToolExecutionCompleted - For each tool as it completes
     * 3. AssistantMessageSaved - Final response from LLM
     * 4. StreamCompleted - End of processing
     *
     * @param userId The ID of the user making the request (used for auto-approval preferences).
     * @param session The session the message belongs to.
     * @param llmConfig The LLM configuration to use for the request.
     * @param content The user's message content.
     * @param parentMessageId Optional ID of the message being replied to.
     * @param mcpResponseFlow A flow of tool execution results from the client for local MCP tools.
     * @param approvalResponseFlow A flow of tool call approval decisions from the client.
     * @return A Flow of Either<ProcessNewMessageError, MessageEvent>.
     *         The flow emits MessageEvent objects as processing progresses,
     *         or ProcessNewMessageError if an error occurs.
     */
    fun processNewMessage(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long? = null,
        mcpResponseFlow: Flow<LocalMCPToolCallResult>,
        approvalResponseFlow: Flow<ToolCallApprovalResponse>
    ): Flow<Either<ProcessNewMessageError, MessageEvent>>

    /**
     * Processes a new incoming user message with streaming LLM response.
     *
     * The user message is saved immediately to the database. Then, an empty assistant message
     * is created and saved with the model and settings information. As the LLM streams content,
     * the assistant message is updated with deltas. Upon completion of the stream, the message
     * is finalized in the database with the full content.
     *
     * When tool calling is enabled and the LLM requests tool execution, the method implements
     * a tool calling loop that creates a NEW assistant message for each LLM response
     * (preserving message ordering for LLM caching).
     *
     * Event flow for each tool calling iteration:
     * 1. AssistantMessageStarted - Empty assistant message is created and saved to DB
     * 2. AssistantMessageDelta - For each content chunk received from LLM (if any)
     * 3. ToolCallDelta - For each tool call argument delta (if tool calling)
     * 4. AssistantMessageFinished - Assistant message is updated with final content
     * 5. If finish_reason == "tool_calls":
     *    a. ToolCallsReceived - Tool calls are saved with PENDING status
     *    b. LocalMCPToolCallReceived - For each local MCP tool that needs client-side execution
     *    c. ToolExecutionCompleted - For each tool as it completes execution
     *    Then continues to next iteration (new assistant message, etc.)
     * 6. If finish_reason != "tool_calls":
     *    a. StreamCompleted - Final event indicating end of processing
     *
     * Key characteristics:
     * - User message is saved immediately (before LLM interaction)
     * - New assistant message is created at START of each iteration (empty, with model/settings)
     * - Content deltas update the message in real-time (not added to DB until completion)
     * - Each assistant message preserves parent-child relationships (for LLM caching)
     * - Tool calls are accumulated from deltas and saved only after streaming completes
     * - Multiple assistant messages may be created during tool calling loops
     * - All operations emit events for real-time UI updates via SSE
     *
     * @param userId The ID of the user making the request (used for auto-approval preferences).
     * @param session The session the message belongs to.
     * @param llmConfig The LLM configuration to use for the request (model, provider, settings, tools).
     * @param content The user's message content.
     * @param parentMessageId Optional ID of the message being replied to. If provided, the new user
     *                        message will be threaded as a child of this message.
     * @param mcpResponseFlow A flow of tool execution results from the client for local MCP tools.
     * @param approvalResponseFlow A flow of tool call approval decisions from the client.
     * @return A Flow of Either<ProcessNewMessageError, MessageStreamEvent>.
     *
     * @see MessageStreamEvent for detailed event type documentation
     * @see ProcessNewMessageError for possible error types
     */
    fun processNewMessageStreaming(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        content: String,
        parentMessageId: Long? = null,
        mcpResponseFlow: Flow<LocalMCPToolCallResult>,
        approvalResponseFlow: Flow<ToolCallApprovalResponse>
    ): Flow<Either<ProcessNewMessageError, MessageStreamEvent>>
}