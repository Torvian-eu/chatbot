package eu.torvian.chatbot.worker.builtin.command

/**
 * Turns a requested command into the argv that a built-in tool must actually execute.
 *
 * A launcher exists so a tool can spawn a command "through" a privilege-dropping intermediary
 * without knowing how that intermediary works. Implementations must return an argv that the
 * operating system `exec`s as the requested command itself, rather than as a supervisor process:
 * the tool's timeout, exit status and reaping all operate on the direct child. How that child's
 * tree is terminated is not the launcher's concern and is carried by the resolved isolation state.
 */
interface CommandLauncher {
    /**
     * Wraps a command and its arguments into the argv that must actually be executed.
     *
     * @param command Executable name or path requested by the caller.
     * @param args Arguments requested by the caller, in order.
     * @return The argv to execute, possibly prefixed by the privilege-dropping helper.
     */
    fun wrap(command: String, args: List<String>): List<String>
}

/**
 * Executes commands directly, so children run as the worker's own OS account.
 *
 * Used wherever no privilege boundary is available or configured; children then share the worker's
 * account and its view of the filesystem.
 */
class NoopLauncher : CommandLauncher {
    override fun wrap(command: String, args: List<String>): List<String> = listOf(command) + args
}

/**
 * Executes commands through the setuid helper, which drops to the isolated account.
 *
 * The helper `exec`s the requested command, so the spawned process is the command itself and keeps
 * its own exit status, signal target and descendants.
 *
 * @property helperPath Absolute path of the root-owned helper binary.
 */
class SetuidHelperLauncher(private val helperPath: String) : CommandLauncher {
    override fun wrap(command: String, args: List<String>): List<String> = listOf(helperPath, command) + args
}

