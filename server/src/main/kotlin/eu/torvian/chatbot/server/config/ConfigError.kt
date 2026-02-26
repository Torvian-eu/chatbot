package eu.torvian.chatbot.server.config

/**
 * Top-level interface for all failures that can happen during configuration loading, parsing,
 * merging, and validation.
 *
 * Error types are split into three broad categories:
 * - [FileError]: problems with the physical configuration files (missing, malformed, IO)
 * - [ValidationError]: problems with the parsed data (missing required keys, invalid values)
 * - [EnvironmentError]: problems with the runtime environment (database files, keystores, permissions)
 *
 * Use pattern matching (when) to react differently to file-level problems vs. data validation problems vs. environment issues.
 */
sealed interface ConfigError {

    /**
     * Errors related to the physical configuration files on disk or bundled resources.
     */
    sealed interface FileError : ConfigError {
        /**
         * The file was not found at the specified path.
         *
         * @property path The filesystem path or resource identifier that was searched.
         */
        data class NotFound(val path: String) : FileError

        /**
         * The file exists but contains invalid/malformed JSON.
         *
         * @property path The filesystem path or resource identifier that was parsed.
         * @property cause The parser error message or other diagnostic text.
         */
        data class Malformed(val path: String, val cause: String) : FileError

        /**
         * The file exists but could not be read due to I/O issues (permissions, locked, etc).
         *
         * @property path The filesystem path that failed reading.
         * @property cause A message describing the I/O error.
         */
        data class IOFailure(val path: String, val cause: String) : FileError
    }

    /**
     * Errors related to the configuration data content after successful parsing.
     */
    sealed interface ValidationError : ConfigError {
        /**
         * A required configuration key was not found in the merged result.
         *
         * @property path The dotted configuration path (for example "jwt.secret").
         */
        data class MissingKey(val path: String) : ValidationError

        /**
         * A configuration key exists but its value is invalid (wrong type or fails business rules).
         *
         * @property path The dotted configuration path (for example "network.port").
         * @property reason A human-readable reason why the value is invalid.
         */
        data class InvalidValue(val path: String, val reason: String) : ValidationError
    }

    /**
     * Errors related to the runtime environment and external resources
     * (Database files, Keystores, Certificates, OS Permissions).
     */
    sealed interface EnvironmentError : ConfigError {
        /**
         * The resource (db, keystore) exists but we lack permissions.
         *
         * @property path The filesystem path to the resource.
         * @property operation The operation that requires permissions (e.g., "READ", "WRITE").
         */
        data class PermissionDenied(val path: String, val operation: String) : EnvironmentError

        /**
         * Failed to physically create a required resource.
         *
         * @property path The filesystem path where creation was attempted.
         * @property cause A message describing why creation failed.
         */
        data class CreationFailure(val path: String, val cause: String) : EnvironmentError

        /**
         * A required non-config file is missing (e.g. the Keystore).
         *
         * @property path The filesystem path that was expected.
         * @property resourceType The type of resource (e.g., "Keystore", "Database file").
         */
        data class ResourceMissing(val path: String, val resourceType: String) : EnvironmentError
    }

    /**
     * Convert the error to a human-friendly message appropriate for logs or console output.
     *
     * @return A string describing the error in an actionable way.
     */
    fun toMessage(): String = when (this) {
        is FileError.NotFound -> "Configuration file not found: '$path'."
        is FileError.Malformed -> "Malformed JSON in '$path': $cause"
        is FileError.IOFailure -> "I/O error reading '$path': $cause"
        is ValidationError.MissingKey -> "Missing required configuration key: '$path'."
        is ValidationError.InvalidValue -> "Invalid configuration value for '$path': $reason"
        is EnvironmentError.PermissionDenied -> "Permission denied for '$path' during $operation."
        is EnvironmentError.CreationFailure -> "Failed to create '$path': $cause"
        is EnvironmentError.ResourceMissing -> "Required $resourceType missing at '$path'."
    }
}