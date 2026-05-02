package eu.torvian.chatbot.worker.config

/**
 * Logical errors while loading worker runtime configuration.
 */
sealed interface WorkerConfigError {
    /**
     * Indicates that the worker configuration file was not present at the expected path.
     *
     * @property path Missing config file path.
     */
    data class ConfigMissing(val path: String) : WorkerConfigError

    /**
     * Indicates that the configuration file could not be read from disk.
     *
     * @property path Config file path that failed to read.
     * @property reason Human-readable I/O failure reason.
     */
    data class ConfigReadFailed(val path: String, val reason: String) : WorkerConfigError

    /**
     * Indicates that the configuration file was present but invalid.
     *
     * @property description Human-readable validation or parse failure detail.
     */
    data class ConfigInvalid(val description: String) : WorkerConfigError
}

