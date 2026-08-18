package eu.torvian.chatbot.server.service.core.toolcall

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.runtime.TurnControlSignal
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
     * @param requestingAgentRoleId Source role attached to the validated session; used for operator
     *            authorization (e.g. the spawn allow-list) and never taken from model input.
     * @param pendingToolCalls Pending tool calls to process.
     * @param toolDefinitions Enabled tool definitions available to the current LLM turn.
     * @param toolApprovalFlow Normalized client approval submissions emitted by the chat WebSocket.
     * @param operatorToolResultFlow Dedicated client→server channel carrying operator tool execution
     *            results; semantically unrelated to [toolApprovalFlow].
     * @param controlSignal Signal that requests cooperative cancellation of approval and execution work.
     * @return Flow of tool execution lifecycle events.
     */
    fun executeAndUpdateToolCalls(
        userId: Long,
        requestingAgentRoleId: Long,
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>,
        controlSignal: TurnControlSignal
    ): Flow<ToolCallExecutionEvent>
}
