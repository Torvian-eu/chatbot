package eu.torvian.chatbot.server.main

/**
 * Sealed interface representing the status of the embedded server.
 *
 * - [NotStarted]: The server has not been started yet.
 * - [Starting]: The server is in the process of starting up.
 * - [Started]: The server is running.
 * - [Error]: An error occurred during server startup or runtime.
 * - [Stopping]: The server is in the process of shutting down.
 * - [Stopped]: The server has been stopped gracefully.
 */
sealed interface ServerStatus {
    /** The server has not been started yet. */
    data object NotStarted : ServerStatus

    /** The server is in the process of starting up. */
    data object Starting : ServerStatus

    /**
     * The server is running.
     * @property serverInstanceInfo The information about the running server instance.
     */
    data class Started(val serverInstanceInfo: ServerInstanceInfo) : ServerStatus

    /**
     * An error occurred during server startup or runtime.
     * @property error The exception that was thrown.
     */
    data class Error(val error: Throwable) : ServerStatus

    /** The server is in the process of shutting down. */
    data object Stopping : ServerStatus

    /** The server has been stopped gracefully. */
    data object Stopped : ServerStatus
}