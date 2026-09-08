package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent

/**
 * Contract for a single operator tool executed by the chat pipeline, e.g. `spawn_agent` or
 * `send_message`.
 *
 * Each tool implementation is deliberately unrelated to the others: it owns its complete execution
 * sequence (decode its own payload, run any pre-turn work, drive the target conversation, and
 * format its mode-aware result). The central router ([DefaultOperatorToolExecutor]) registers
 * tools under the catalog names wired into it (see `OperatorToolCatalog`) and forwards every
 * operator-tool request to the matching tool, so a tool never sees a foreign tool name and needs
 * no name guard of its own.
 *
 * Shared machinery that genuinely spans tools (payload decoding, error-result building, and the
 * chat-turn driver used by conversation-driving tools) lives in package-level internal helpers
 * ([operatorToolJson], [toolError], [runTurnThroughViewModel]) instead of a common base type, so
 * future operator tools remain free to shape their own execution.
 */
interface OperatorTool {

    /**
     * Executes this tool for one operator-tool request.
     *
     * @param toolCallId Correlation key echoed back in the emitted [ChatClientEvent.ToolExecutionResult].
     * @param payload JSON text of the tool-specific payload.
     * @param clientEvents Sink that receives the [ChatClientEvent.ToolExecutionResult] to send back
     *            to the server on the original chat WebSocket. Implementations must use a
     *            non-suspending, best-effort emission so a closed primary socket cannot hang the
     *            driven coroutine.
     */
    suspend fun execute(
        toolCallId: Long,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    )
}