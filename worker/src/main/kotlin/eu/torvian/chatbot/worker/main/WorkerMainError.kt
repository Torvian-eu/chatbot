package eu.torvian.chatbot.worker.main

import eu.torvian.chatbot.worker.config.WorkerConfigError
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeError
import eu.torvian.chatbot.worker.setup.WorkerSetupError

/**
 * Logical startup errors for the standalone worker process.
 */
sealed interface WorkerMainError {
    /**
     * Indicates invalid CLI arguments or unsupported argument combinations.
     *
     * @property reason Human-readable validation detail.
     */
    data class InvalidArguments(val reason: String) : WorkerMainError

    /**
     * Wraps a configuration loading error encountered during startup.
     *
     * @property error The underlying worker configuration error.
     */
    data class Config(val error: WorkerConfigError) : WorkerMainError

    /**
     * Indicates that the worker secrets file could not be read or parsed.
     *
     * @property path Secrets file path that failed to read.
     * @property reason Human-readable file failure reason.
     */
    data class SecretsReadFailed(val path: String, val reason: String) : WorkerMainError

    /**
     * Wraps a worker runtime error that occurred during operation.
     *
     * Can represent either auth failures or assigned-config bootstrap failures.
     *
     * @property error The underlying worker runtime orchestration error.
     */
    data class Runtime(val error: WorkerRuntimeError) : WorkerMainError

    /**
     * Wraps a setup flow error that occurred while preparing initial configuration.
     *
     * @property error The underlying setup error.
     */
    data class Setup(val error: WorkerSetupError) : WorkerMainError
}

