package eu.torvian.chatbot.server.service.builtin

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
 * No session or turn-anchor context is needed: every handler is a user-scoped read/manage
 * operation that needs only [userId] plus the tool call.
 */
interface ServerBuiltInToolExecutor {

    /**
     * Executes one server built-in tool call and returns the terminal [ToolCall].
     *
     * The implementation dispatches on [ToolCall.toolName] (unique within a user's tool set), parses
     * [ToolCall.input], runs the user-scoped handler, and maps any failure into a tool-level error
     * result the LLM can read. Unsupported tool names and malformed inputs produce a terminal ERROR
     * [ToolCall] instead of an exception.
     *
     * @param userId The user whose server built-in tool instance is being executed (ownership scope
     *            for every handler).
     * @param toolCall The persisted tool call being executed.
     * @return The terminal [ToolCall] with output/error fields populated.
     */
    suspend fun executeTool(
        userId: Long,
        toolCall: ToolCall
    ): ToolCall
}
