package eu.torvian.chatbot.worker.builtin

import java.nio.file.Path

/**
 * Execution context provided to every built-in tool invocation.
 *
 * @property workspace Absolute path to the worker's filesystem workspace. All file tools must
 *   resolve their arguments against this directory and reject any path that escapes it.
 * @property toolNamePrefix Optional namespace prefix prepended to public tool names. Used by the
 *   executor when resolving a public name to an implementation.
 * @property defaultCommandTimeoutSeconds Default timeout (in seconds) for the `run_command` tool
 *   when the call does not specify its own timeout.
 */
data class BuiltInToolExecutionContext(
    val workspace: Path,
    val toolNamePrefix: String?,
    val defaultCommandTimeoutSeconds: Long,
)

