package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Executes server built-in tools in-process on the server.
 *
 * Server built-in tools (e.g. `list_agent_roles`, `update_agent_role`) are cataloged in
 * `ServerBuiltInToolCatalog` and seeded as per-user instances. After a plain (non-signed) approval
 * the orchestrator dispatches the tool call here; the implementation runs the matching
 * user-scoped handler against the server's own services and returns a terminal [ToolCall]
 * (SUCCESS or ERROR), never throwing for expected failures.
 *
 * The session-context fields of [ToolCallExecutionContext] are non-null: the chat-turn pipeline
 * is the only producer and always supplies a validated session with a selected agent role before
 * tool calls execute. This lets tools that need the current session/role identity (e.g.
 * `get_current_session_info`) resolve it directly from the context.
 */
interface ServerBuiltInToolExecutor {

    /**
     * Executes one server built-in tool call and returns the terminal [ToolCall].
     *
     * The implementation dispatches on [ServerBuiltInToolDefinition.builtInToolName] — the
     * canonical, unprefixed catalog name persisted on the definition and passed by the orchestrator
     * from the already-resolved row. [ToolCall.toolName] is the public name the LLM emitted
     * (prefix + canonical) and is **not** used for dispatch. No preference lookup happens at
     * execution time, so the dispatch key stays stable across prefix changes.
     *
     * Unsupported canonical names (a registry/DB inconsistency) and malformed inputs produce a
     * terminal ERROR [ToolCall] instead of an exception.
     *
     * @param context Caller identity plus the turn's session/role context, bundled so the
     *            executor contract stays stable; see [ToolCallExecutionContext].
     * @param toolDefinition The resolved server built-in tool definition being executed; dispatch
     *            keys on its [ServerBuiltInToolDefinition.builtInToolName].
     * @param toolCall The persisted tool call being executed.
     * @return The terminal [ToolCall] with output/error fields populated.
     */
    suspend fun executeTool(
        context: ToolCallExecutionContext,
        toolDefinition: ServerBuiltInToolDefinition,
        toolCall: ToolCall
    ): ToolCall
}
