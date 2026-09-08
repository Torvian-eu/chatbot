package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent

/**
 * Executes one operator-tool request on the operator (in v1 the client app) side.
 *
 * When the server relays an operator tool execution (a `spawn_agent` or `send_message` call), the
 * operator runs the tool headlessly — without disturbing the active chat UI state — and reports the
 * result back through the same chat WebSocket. The executor aggregates the outcome into a
 * [ChatClientEvent.ToolExecutionResult] that the server feeds back to the calling LLM.
 *
 * This is the **general** operator-tool contract the chat pipeline depends on. The app wires
 * [DefaultOperatorToolExecutor] as the single implementation: a central router that dispatches
 * each call by `toolName` to the per-tool [OperatorTool] registered for that catalog name
 * (`spawn_agent` → [AgentSpawnTool], `send_message` → [SendMessageTool]).
 */
interface OperatorToolExecutor {

    /**
     * Executes one operator-tool request.
     *
     * @param toolCallId Correlation key echoed back in the emitted [ChatClientEvent.ToolExecutionResult].
     * @param toolName Operator-tool name (`spawn_agent` or `send_message`); selects the payload
     *            decoder and the per-tool executor. Because operator tools are per-user instances the
     *            name is unique within the user's tool set.
     * @param payload JSON text of the tool-specific payload (an
     *            [eu.torvian.chatbot.common.models.agent.AgentSpawnRequest] for `spawn_agent`, a
     *            [eu.torvian.chatbot.common.models.agent.SendMessageRequest] for `send_message`).
     * @param clientEvents Sink that receives the [ChatClientEvent.ToolExecutionResult] to send back to
     *            the server on the original chat WebSocket. Implementations must use a non-suspending,
     *            best-effort emission so a closed primary socket cannot hang the driven coroutine.
     */
    suspend fun execute(
        toolCallId: Long,
        toolName: String,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    )
}