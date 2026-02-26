package eu.torvian.chatbot.app.config

/**
 * Sealed interface representing all possible failures that can occur during
 * configuration loading, parsing, merging, and validation for the client application.
 *
 * Errors are categorized for better diagnostics:
 * - [FileError]: Problems with reading or parsing configuration files.
 * - [ValidationError]: Problems with the content of the configuration data (missing keys, invalid values).
 */
sealed interface ConfigError {

    /**
     * Errors related to the physical configuration files on disk or bundled resources.
     */
    sealed interface FileError : ConfigError {
        /**
         * The configuration file was not found at the specified path or resource identifier.
         * @property path The path or identifier that was searched.
         */
        data class NotFound(val path: String) : FileError

        /**
         * The configuration file exists but contains invalid JSON syntax or structure.
         * @property path The path or identifier that was parsed.
         * @property cause A diagnostic message from the parser.
         */
        data class Malformed(val path: String, val cause: String) : FileError

        /**
         * An I/O error occurred while trying to read the configuration file (e.g., permissions).
         * @property path The path that failed to read.
         * @property cause A message describing the I/O error.
         */
        data class IOFailure(val path: String, val cause: String) : FileError
    }

    /**
     * Errors related to the configuration data content after successful parsing.
     * These indicate that a required value is missing or that an existing value is invalid.
     */
    sealed interface ValidationError : ConfigError {
        /**
         * A mandatory configuration key was not found in the merged result.
         * @property path The dotted configuration path (e.g., "network.serverUrl").
         */
        data class MissingKey(val path: String) : ValidationError

        /**
         * A configuration key exists, but its value is invalid or does not meet business rules.
         * @property path The dotted configuration path.
         * @property reason A human-readable reason why the value is invalid.
         */
        data class InvalidValue(val path: String, val reason: String) : ValidationError
    }

    /**
     * Converts the [ConfigError] instance into a human-friendly error message.
     * This message is suitable for logging or displaying to the user.
     * @return A string describing the error.
     */
    fun toMessage(): String = when (this) {
        is FileError.NotFound -> "Configuration file not found: '$path'."
        is FileError.Malformed -> "Malformed JSON in '$path': $cause"
        is FileError.IOFailure -> "I/O error reading '$path': $cause"
        is ValidationError.MissingKey -> "Missing required configuration key: '$path'."
        is ValidationError.InvalidValue -> "Invalid configuration value for '$path': $reason"
    }
}