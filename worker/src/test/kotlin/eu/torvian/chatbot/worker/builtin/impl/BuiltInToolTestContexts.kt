package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.DefaultBuiltInToolEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Builds a tool execution context for tests.
 *
 * @param workspace Workspace every path-taking tool must stay inside.
 * @param defaultCommandTimeoutSeconds Configured `run_command` timeout.
 * @param defaultSearchTimeoutSeconds Configured `search_text` timeout.
 * @param ioDispatcher Dispatcher for blocking filesystem and process operations.
 * @param agentRoleId Acting agent role, or null for a call that carries none.
 * @return Execution context over a [DefaultBuiltInToolEnvironment] with the given role.
 */
internal fun testExecutionContext(
    workspace: Path,
    defaultCommandTimeoutSeconds: Long,
    defaultSearchTimeoutSeconds: Long,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    agentRoleId: Long? = null,
): BuiltInToolExecutionContext = BuiltInToolExecutionContext(
    DefaultBuiltInToolEnvironment(
        workspace = workspace,
        defaultCommandTimeoutSeconds = defaultCommandTimeoutSeconds,
        defaultSearchTimeoutSeconds = defaultSearchTimeoutSeconds,
        ioDispatcher = ioDispatcher,
    ),
    agentRoleId = agentRoleId,
)
