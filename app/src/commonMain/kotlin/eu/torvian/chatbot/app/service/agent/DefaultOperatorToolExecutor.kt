package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.common.models.api.core.ChatClientEvent

/**
 * Central [OperatorToolExecutor] that dispatches every operator-tool request to the [OperatorTool]
 * registered for the request's catalog name.
 *
 * This is the **single** implementation of [OperatorToolExecutor] — the one operator-tool executor
 * the chat pipeline (in particular `SendMessageUseCase`) depends on. The [tools] map is keyed by
 * the catalog names (`spawn_agent`, `send_message`; see `OperatorToolCatalog`) and is wired in the
 * Koin module, mirroring the server's built-in tool registry style: each per-tool implementation
 * ([AgentSpawnTool], [SendMessageTool]) implements [OperatorTool] directly and is free of routing
 * concerns.
 *
 * An unknown tool name (no registered tool) fails fast with a readable tool error — no tool ever
 * receives a name it does not handle.
 *
 * @property tools Registered operator tools keyed by the catalog tool name they handle.
 */
class DefaultOperatorToolExecutor(
    private val tools: Map<String, OperatorTool>
) : OperatorToolExecutor {

    override suspend fun execute(
        toolCallId: Long,
        toolName: String,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    ) {
        val tool = tools[toolName]
        if (tool == null) {
            // Unknown operator tool names degrade gracefully: report a tool error instead of
            // crashing, so future operator tools remain compatible with older clients.
            clientEvents(toolError(toolCallId, "Unknown operator tool: $toolName"))
            return
        }
        tool.execute(toolCallId, payload, clientEvents)
    }
}