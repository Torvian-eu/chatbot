package eu.torvian.chatbot.server.service.core.toolcall

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates the approval and execution lifecycle for a batch of pending tool calls.
 *
 * The orchestrator resolves user approval (including auto-approval preferences and Local MCP
 * signed authorizations), persists status transitions, invokes the appropriate executor, and emits
 * lifecycle events for each tool call.
 */
interface ToolCallOrchestrator {
    /**
     * Executes [pendingToolCalls] sequentially, emitting lifecycle events as they transition.
     *
     * @param userId User whose non-Local-MCP approval preferences may be consulted.
     * @param pendingToolCalls Pending tool calls to process.
     * @param toolDefinitions Enabled tool definitions available to the current LLM turn.
     * @param toolApprovalFlow Normalized client approval submissions emitted by the chat WebSocket.
     * @return Flow of tool execution lifecycle events.
     */
    fun executeAndUpdateToolCalls(
        userId: Long,
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): Flow<ToolCallExecutionEvent>
}
