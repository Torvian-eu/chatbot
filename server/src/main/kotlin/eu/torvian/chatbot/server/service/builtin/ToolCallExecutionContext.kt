package eu.torvian.chatbot.server.service.builtin

/**
 * Execution context bundled through the whole tool-call approval and execution chain.
 *
 * Mirrors the worker-side `eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext` style by
 * bundling the caller identity and the turn's session context into one object, so contracts stay
 * stable when future context fields are added. The caller-identity and session fields are non-null:
 * the chat-turn pipeline is the only producer (see
 * [eu.torvian.chatbot.server.service.core.chat.turn.DefaultConversationTurnOrchestrator]) and it
 * always has a validated session with a selected agent role before tool calls execute, so a fully
 * populated context is guaranteed for every dispatch path — operator execution, server built-in
 * tools, and (structurally) the Local MCP and worker built-in paths that intentionally ignore it.
 *
 * [projectId] is nullable because a session may legitimately have no project selected; it carries
 * the project scope (if any) of the current turn so project-aware handlers — most notably the
 * `spawn_agent` role-by-name lookup — resolve against the session's project instead of the
 * unassociated scope.
 *
 * @property userId The user whose tool calls are being executed; the ownership scope for every
 *   handler.
 * @property sessionId Id of the chat session the current turn belongs to.
 * @property sessionName Name of the chat session the current turn belongs to.
 * @property agentRoleId Id of the agent role selected for the current session.
 * @property projectId Id of the project selected for the current session, or `null` when the
 *   session has no project (unassociated scope for project-scoped lookups).
 */
data class ToolCallExecutionContext(
    val userId: Long,
    val sessionId: Long,
    val sessionName: String,
    val agentRoleId: Long,
    val projectId: Long? = null,
)