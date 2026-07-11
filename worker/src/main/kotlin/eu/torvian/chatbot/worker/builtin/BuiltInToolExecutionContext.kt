package eu.torvian.chatbot.worker.builtin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Execution context provided to every built-in tool invocation.
 *
 * @property workspace Absolute path to the worker's filesystem workspace. All file tools must
 *   resolve their arguments against this directory and reject any path that escapes it.
 * @property defaultCommandTimeoutSeconds Default timeout (in seconds) for the `run_command` tool
 *   when the call does not specify its own timeout.
 * @property ioDispatcher [CoroutineDispatcher] used for blocking filesystem and process operations.
 *   Defaults to [Dispatchers.IO].
 */
data class BuiltInToolExecutionContext(
    val workspace: Path,
    val defaultCommandTimeoutSeconds: Long,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
