package eu.torvian.chatbot.worker.config

/**
 * Errors that can occur while mutating trusted signer data in `application.json`.
 */
sealed interface WorkerTrustedSignerManagerError {
    /**
     * Indicates that the operator-supplied signer input was invalid.
     *
     * @property error Validation detail produced by the shared config validation helpers.
     */
    data class InvalidInput(val error: WorkerConfigError.ConfigInvalid) : WorkerTrustedSignerManagerError

    /**
     * Indicates that reading or writing the application config layer failed.
     *
     * @property error Underlying configuration file error.
     */
    data class Config(val error: WorkerConfigError) : WorkerTrustedSignerManagerError
}