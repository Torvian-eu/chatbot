package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent

/**
 * Executes one operator-tool request on the operator (in v1 the client app) side.
 *
 * When the server relays an operator tool execution (e.g. a `spawn_agent` call), the operator runs
 * the tool headlessly — without disturbing the active chat UI state — and reports the result back
 * through the same chat WebSocket. The executor aggregates the outcome into a
 * [ChatClientEvent.ToolExecutionResult] that the server feeds back to the calling LLM.
 */
interface AgentSpawnExecutor {

    /**
     * Executes one operator-tool request.
     *
     * @param toolCallId Correlation key echoed back in the emitted [ChatClientEvent.ToolExecutionResult].
     * @param toolName Operator-tool name (e.g. `spawn_agent`); selects the payload decoder. Because
     *            operator tools are per-user instances the name is unique within the user's tool set.
     * @param payload JSON text of the tool-specific payload (e.g. an
     *            [eu.torvian.chatbot.common.models.agent.AgentSpawnRequest]).
     * @param clientEvents Sink that receives the [ChatClientEvent.ToolExecutionResult] to send back to
     *            the server on the original chat WebSocket. Implementations must use a non-suspending,
     *            best-effort emission so a closed primary socket cannot hang the spawned coroutine.
     */
    suspend fun execute(
        toolCallId: Long,
        toolName: String,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    )
}
