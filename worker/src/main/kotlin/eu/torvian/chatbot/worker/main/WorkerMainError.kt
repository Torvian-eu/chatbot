package eu.torvian.chatbot.worker.main

import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError
import eu.torvian.chatbot.worker.config.WorkerConfigError

/**
 * Logical startup errors for the standalone worker process.
 */
sealed interface WorkerMainError {
    /**
     * Wraps a configuration loading error encountered during startup.
     *
     * @property error The underlying worker configuration error.
     */
    data class Config(val error: WorkerConfigError) : WorkerMainError

    /**
     * Indicates that a required bootstrap file could not be read.
     *
     * @property path File path that failed to read.
     * @property reason Human-readable file failure reason.
     */
    data class FileReadFailed(val path: String, val reason: String) : WorkerMainError

    /**
     * Wraps an auth flow error that occurred while bootstrapping the worker.
     *
     * @property error The underlying worker auth orchestration error.
     */
    data class Auth(val error: WorkerAuthManagerError) : WorkerMainError
}

