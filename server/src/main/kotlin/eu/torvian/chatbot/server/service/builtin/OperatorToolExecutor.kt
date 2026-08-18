package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.service.core.toolcall.OperatorToolExecutionResult
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import kotlinx.coroutines.flow.Flow

/**
 * Executes operator tools by relaying the tool call to the operator and awaiting its result.
 *
 * Operator tools (e.g. `spawn_agent`) are server-orchestrated: the executor emits an
 * [ToolCallExecutionEvent.OperatorToolExecutionRequested] carrying the tool-specific payload (as a
 * generic envelope: `toolName` + JSON-string `payload`) and then waits on the dedicated
 * `operatorToolResultFlow` for the correlated [OperatorToolExecutionResult]. There is no worker
 * dispatch, so no signed authorization is involved.
 */
interface OperatorToolExecutor {

    /**
     * Executes one operator tool call and returns the terminal [ToolCall] (SUCCESS or ERROR).
     *
     * The implementation is responsible for building the tool-specific payload server-side (input
     * parsing, user-scoped role resolution, and the source role's spawn allow-list), emitting the
     * relay event, awaiting the operator's result correlated by [ToolCall.id], and mapping any
     * failure into a tool-level error result the LLM can read.
     *
     * @param userId The user whose operator tool instance is being executed (ownership scope for
     *            payload building).
     * @param requestingAgentRoleId Source role id from the validated session, never model input.
     * @param toolCall The persisted tool call being executed.
     * @param emitEvent Sink used to emit [ToolCallExecutionEvent] instances (in particular the
     *            [ToolCallExecutionEvent.OperatorToolExecutionRequested] relay event).
     * @param operatorToolResultFlow Dedicated client→server channel carrying
     *            [OperatorToolExecutionResult] replies; never the approval flow.
     * @return The terminal [ToolCall] with output/error fields populated.
     */
    suspend fun executeTool(
        userId: Long,
        requestingAgentRoleId: Long,
        toolCall: ToolCall,
        emitEvent: suspend (ToolCallExecutionEvent) -> Unit,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>
    ): ToolCall
}
