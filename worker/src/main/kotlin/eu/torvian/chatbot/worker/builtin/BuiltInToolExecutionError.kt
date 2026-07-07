package eu.torvian.chatbot.worker.builtin

/**
 * Stable error codes reported by the built-in tool executor and individual tools.
 *
 * Codes are intentionally short, machine-readable strings that can be surfaced in the
 * `command.result` errorCode field and persisted on the server.
 */
object BuiltInToolExecutionError {
    /** The tool name does not match any registered implementation. */
    const val UNKNOWN_TOOL = "unknown_tool"

    /** Input arguments failed JSON Schema validation. */
    const val INVALID_INPUT = "invalid_input"

    /** The requested file path resolves outside the configured workspace. */
    const val WORKSPACE_VIOLATION = "workspace_violation"

    /** The target file or directory does not exist. */
    const val NOT_FOUND = "not_found"

    /** The target already exists when the tool requires it not to. */
    const val ALREADY_EXISTS = "already_exists"

    /** The user is not permitted to perform the requested operation. */
    const val PERMISSION_DENIED = "permission_denied"

    /** The operation exceeded the allowed execution time. */
    const val TIMEOUT = "timeout"

    /** Any other execution failure (caller-supplied message). */
    const val EXECUTION_FAILED = "execution_failed"
}

