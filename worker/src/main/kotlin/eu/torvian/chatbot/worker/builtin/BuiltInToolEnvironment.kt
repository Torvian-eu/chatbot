package eu.torvian.chatbot.worker.builtin

import kotlinx.coroutines.CoroutineDispatcher
import java.nio.file.Path

/**
 * Process-scoped environment shared by every built-in tool invocation.
 *
 * Values are derived from worker configuration and never change while the worker runs, so nothing
 * per-call belongs here. A call adds its own data by wrapping an instance in
 * [BuiltInToolExecutionContext].
 */
interface BuiltInToolEnvironment {
    /**
     * Absolute path to the worker's filesystem workspace. All file tools must resolve their
     * arguments against this directory and reject any path that escapes it.
     */
    val workspace: Path

    /** Default timeout (in seconds) for the `run_command` tool when the call omits one. */
    val defaultCommandTimeoutSeconds: Long

    /** Default timeout (in seconds) for the `search_text` tool when the call omits one. */
    val defaultSearchTimeoutSeconds: Long

    /** [CoroutineDispatcher] used for blocking filesystem and process operations. */
    val ioDispatcher: CoroutineDispatcher
}
