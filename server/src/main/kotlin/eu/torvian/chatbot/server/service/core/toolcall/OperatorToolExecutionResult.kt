package eu.torvian.chatbot.server.service.core.toolcall

/**
 * The operator's reply to one `OperatorToolExecutionRequested`.
 *
 * Deliberately unrelated to [ToolCallApprovalSubmission]: it flows from the client to the server on a
 * dedicated result channel (`operatorToolResultFlow`) and is consumed only by the
 * [eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor] of the matching tool call, so a
 * tool result never travels through the approval flow.
 *
 * @property toolCallId Echoes the id from `OperatorToolExecutionRequested` (correlation key).
 * @property output The tool's textual output (e.g. the spawned agent's summary), or `null` on error.
 * @property isError Whether the operator reports a failed execution.
 * @property errorMessage Optional error detail surfaced to the LLM when [isError] is true.
 */
data class OperatorToolExecutionResult(
    val toolCallId: Long,
    val output: String?,
    val isError: Boolean,
    val errorMessage: String?
)
