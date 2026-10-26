package eu.torvian.chatbot.worker.builtin

/**
 * Execution context of a single built-in tool call.
 *
 * Layers the verified agent role acting in this call over the process-scoped [environment], so a
 * tool that authorizes on the role reads it from the context. Environment members are forwarded
 * unchanged.
 *
 * @property environment Process-scoped environment shared by every invocation.
 * @property agentRoleId Verified agent role acting in this call, or null when the authorization
 *   carries none. A tool that authorizes on the role treats null as a rejection.
 */
data class BuiltInToolExecutionContext(
    val environment: BuiltInToolEnvironment,
    val agentRoleId: Long? = null,
) : BuiltInToolEnvironment by environment
