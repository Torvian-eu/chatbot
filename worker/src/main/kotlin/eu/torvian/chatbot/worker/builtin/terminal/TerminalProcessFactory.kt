package eu.torvian.chatbot.worker.builtin.terminal

import com.pty4j.PtyProcessBuilder
import java.io.IOException
import java.nio.file.Path

/**
 * Complete description of the process a terminal session must start.
 *
 * @property argv Already launcher-wrapped command line.
 * @property environment Complete child environment; the factory never inherits the worker's own.
 * @property workingDirectory Working directory for the child, normally the worker workspace.
 * @property columns Initial PTY width in character cells.
 * @property rows Initial PTY height in character cells.
 */
internal data class TerminalProcessSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
    val columns: Int,
    val rows: Int,
)

/**
 * Starts the PTY-backed process for a terminal session.
 *
 * The returned value is a plain [Process]. pty4j's process type already extends it, the existing
 * terminators accept a [Process], and tests can supply an ordinary process started over pipes; no
 * adapter type is needed.
 */
internal fun interface TerminalProcessFactory {
    /**
     * @param spec Fully resolved command, environment, working directory and window size.
     * @return The started process; the caller owns its streams and lifecycle.
     * @throws IOException When the platform cannot create the PTY or execute the command.
     */
    fun start(spec: TerminalProcessSpec): Process
}

/**
 * Production factory backed by pty4j.
 *
 * pty4j is reached only inside [start], never in a constructor, so constructing the manager and
 * resolving the tool registry load no native library.
 */
internal class Pty4jTerminalProcessFactory : TerminalProcessFactory {
    override fun start(spec: TerminalProcessSpec): Process =
        PtyProcessBuilder(spec.argv.toTypedArray())
            .setEnvironment(spec.environment)
            .setDirectory(spec.workingDirectory.toString())
            .setInitialColumns(spec.columns)
            .setInitialRows(spec.rows)
            .setConsole(false)
            .start()
}
