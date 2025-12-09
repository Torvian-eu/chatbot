package eu.torvian.chatbot.app.service.mcp

/**
 * Common interface for all process manager errors.
 *
 * Provides standard properties for logging and debugging.
 */
sealed interface ProcessManagerError {
    /**
     * Comprehensive error message suitable for logging and debugging.
     */
    val message: String

    /**
     * Optional underlying cause (technical exception).
     */
    val cause: Throwable?
}

/**
 * Errors that can occur when starting an MCP server process.
 *
 * Used by: startServer(), restartServer()
 */
sealed class StartServerError : ProcessManagerError {
    /**
     * The process failed to start.
     *
     * @property serverId The ID of the MCP server that failed to start
     * @property command The command that was attempted
     * @property reason Human-readable reason for the failure
     * @property cause The underlying exception if available
     */
    data class ProcessStartFailed(
        val serverId: Long,
        val command: String,
        val reason: String,
        override val cause: Throwable? = null
    ) : StartServerError() {
        override val message: String =
            "Failed to start MCP server (ID: $serverId). Command: '$command'. Reason: $reason"
    }

    /**
     * The process is already running.
     *
     * @property serverId The ID of the MCP server
     */
    data class ProcessAlreadyRunning(
        val serverId: Long
    ) : StartServerError() {
        override val message: String =
            "MCP server (ID: $serverId) is already running"
        override val cause: Throwable? = null
    }

    /**
     * Invalid server configuration.
     *
     * @property serverId The ID of the MCP server
     * @property reason Human-readable reason for the invalidity
     */
    data class InvalidConfiguration(
        val serverId: Long,
        val reason: String
    ) : StartServerError() {
        override val message: String =
            "Invalid configuration for MCP server (ID: $serverId): $reason"
        override val cause: Throwable? = null
    }

    /**
     * An unexpected error occurred during server start.
     *
     * @property description Brief description of the error
     * @property cause The underlying exception
     */
    data class UnexpectedError(
        val description: String,
        override val cause: Throwable? = null
    ) : StartServerError() {
        override val message: String =
            "Unexpected error starting server: $description" +
                    (cause?.message?.let { " - $it" } ?: "")

        companion object {
            fun from(throwable: Throwable): UnexpectedError =
                UnexpectedError("An unexpected error occurred", throwable)
        }
    }
}

/**
 * Errors that can occur when stopping an MCP server process.
 *
 * Used by: stopServer(), restartServer()
 */
sealed class StopServerError : ProcessManagerError {
    /**
     * The process is not running (cannot stop).
     *
     * @property serverId The ID of the MCP server
     */
    data class ProcessNotRunning(
        val serverId: Long
    ) : StopServerError() {
        override val message: String =
            "MCP server (ID: $serverId) is not running"
        override val cause: Throwable? = null
    }

    /**
     * The process failed to stop gracefully.
     *
     * @property serverId The ID of the MCP server
     * @property reason Human-readable reason for the failure
     * @property cause The underlying exception if available
     */
    data class ProcessStopFailed(
        val serverId: Long,
        val reason: String,
        override val cause: Throwable? = null
    ) : StopServerError() {
        override val message: String =
            "Failed to stop MCP server (ID: $serverId). Reason: $reason"
    }

    /**
     * An unexpected error occurred during server stop.
     *
     * @property description Brief description of the error
     * @property cause The underlying exception
     */
    data class UnexpectedError(
        val description: String,
        override val cause: Throwable? = null
    ) : StopServerError() {
        override val message: String =
            "Unexpected error stopping server: $description" +
                    (cause?.message?.let { " - $it" } ?: "")

        companion object {
            fun from(throwable: Throwable): UnexpectedError =
                UnexpectedError("An unexpected error occurred", throwable)
        }
    }
}


/**
 * Errors that can occur when restarting an MCP server process.
 *
 * This is a union type that can be either a stop error or a start error,
 * since restart is a composite operation.
 *
 * Used by: restartServer()
 */
sealed class RestartServerError : ProcessManagerError {
    /**
     * The server failed to stop during restart.
     *
     * @property stopError The underlying stop error
     */
    data class StopFailed(
        val stopError: StopServerError
    ) : RestartServerError() {
        override val message: String = "Failed to stop server during restart: ${stopError.message}"
        override val cause: Throwable? = stopError.cause
    }

    /**
     * The server failed to start during restart.
     *
     * @property startError The underlying start error
     */
    data class StartFailed(
        val startError: StartServerError
    ) : RestartServerError() {
        override val message: String = "Failed to start server during restart: ${startError.message}"
        override val cause: Throwable? = startError.cause
    }
}

