package eu.torvian.chatbot.server.service.core.toolcall

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
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
     * The execution context bundles the caller identity with the turn's session context (see
     * [ToolCallExecutionContext]) so the whole approval/execution chain carries one fully-populated
     * object instead of growing parameter lists: server built-in tool handlers consume the session
     * identity, the operator path uses the caller id and the validated agent role, and the Local
     * MCP path uses none of it.
     *
     * @param context Caller identity plus the turn's session/role context; fully populated because
     *            the chat-turn pipeline guarantees a validated session with a selected agent role.
     * @param pendingToolCalls Pending tool calls to process.
     * @param toolDefinitions Enabled tool definitions available to the current LLM turn.
     * @param toolApprovalFlow Normalized client approval submissions emitted by the chat WebSocket.
     * @param operatorToolResultFlow Dedicated client→server channel carrying operator tool execution
     *            results; semantically unrelated to [toolApprovalFlow].
     * @param controlSignal Signal that requests cooperative cancellation of approval and execution work.
     * @return Flow of tool execution lifecycle events.
     */
    fun executeAndUpdateToolCalls(
        context: ToolCallExecutionContext,
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>,
        controlSignal: TurnControlSignal
    ): Flow<ToolCallExecutionEvent>
}
