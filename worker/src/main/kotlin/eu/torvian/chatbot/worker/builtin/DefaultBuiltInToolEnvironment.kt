package eu.torvian.chatbot.worker.builtin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

/**
 * Configured [BuiltInToolEnvironment] registered once per worker process.
 *
 * @property workspace Absolute path to the worker's filesystem workspace.
 * @property defaultCommandTimeoutSeconds Default timeout (in seconds) for the `run_command` tool
 *   when the call does not specify its own timeout.
 * @property defaultSearchTimeoutSeconds Default timeout (in seconds) for the `search_text` tool
 *   when the call does not specify its own timeout.
 * @property ioDispatcher Dispatcher for blocking filesystem and process operations. Defaults to
 *   [Dispatchers.IO].
 */
data class DefaultBuiltInToolEnvironment(
    override val workspace: Path,
    override val defaultCommandTimeoutSeconds: Long,
    override val defaultSearchTimeoutSeconds: Long,
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BuiltInToolEnvironment
