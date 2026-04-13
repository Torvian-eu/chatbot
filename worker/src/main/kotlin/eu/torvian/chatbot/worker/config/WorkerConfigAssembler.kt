package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.net.URI

/**
 * Strict assembly and validation helpers for worker configuration.
 *
 * Similar to server config assembly, this converts the merged nullable DTO model
 * into a strict domain configuration used by runtime services.
 */

/**
 * Converts the worker app config DTO into a validated domain model.
 *
 * Performs strict validation of all required configuration fields and constraints:
 * - Ensures the worker config group exists
 * - Validates all required string fields are non-blank
 * - Validates the server base URL is an absolute HTTP(S) URL
 * - Ensures the refresh skew is non-negative
 *
 * @receiver The WorkerAppConfigDto to convert and validate.
 * @return Either a logical configuration error or the fully validated WorkerConfiguration
 */
fun WorkerAppConfigDto.toDomain(): Either<WorkerConfigError, WorkerConfiguration> = either {
    val worker = worker ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker"))

    val serverBaseUrl = required("worker.serverBaseUrl", worker.serverBaseUrl)
    val workerUid = required("worker.workerUid", worker.workerUid)
    val certificateFingerprint = required("worker.certificateFingerprint", worker.certificateFingerprint)
    val secretsJsonPath = required("worker.secretsJsonPath", worker.secretsJsonPath)
    val tokenFilePath = required("worker.tokenFilePath", worker.tokenFilePath)
    val refreshSkewSeconds = worker.refreshSkewSeconds ?: 60L

    validateServerBaseUrl(serverBaseUrl)
    ensure(workerUid.isNotBlank()) { WorkerConfigError.ConfigInvalid("worker.workerUid must not be blank") }
    ensure(certificateFingerprint.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.certificateFingerprint must not be blank")
    }
    ensure(secretsJsonPath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.secretsJsonPath must not be blank")
    }
    ensure(tokenFilePath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.tokenFilePath must not be blank")
    }
    ensure(refreshSkewSeconds >= 0) {
        WorkerConfigError.ConfigInvalid("worker.refreshSkewSeconds must be >= 0")
    }

    WorkerConfiguration(
        setupRequired = setup?.required ?: true,
        worker = WorkerRuntimeConfig(
            serverBaseUrl = serverBaseUrl,
            workerUid = workerUid,
            certificateFingerprint = certificateFingerprint,
            secretsJsonPath = secretsJsonPath,
            tokenFilePath = tokenFilePath,
            refreshSkewSeconds = refreshSkewSeconds
        )
    )
}

/**
 * Extracts and validates a required string configuration value.
 *
 * If the value is null or missing, raises a configuration error with the path
 * identifying which field is missing. The caller is responsible for further
 * validation of the extracted value (e.g., checking if it's non-blank).
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param path The configuration path (for example "worker.serverBaseUrl") used in error messages.
 * @param value The configuration value to validate, which may be null.
 * @return The non-null configuration value if present.
 * @throws WorkerConfigError.ConfigInvalid if the value is null via Raise DSL.
 */
private fun Raise<WorkerConfigError>.required(path: String, value: String?): String {
    return value ?: raise(WorkerConfigError.ConfigInvalid("Missing required configuration value: $path"))
}

/**
 * Validates the server base URL configuration value.
 *
 * Performs the following checks:
 * - URL is not blank (after trimming whitespace)
 * - URL can be parsed as a valid URI
 * - URI is absolute with a non-blank host
 * - URI scheme is either "http" or "https"
 *
 * If any validation fails, raises a specific configuration error describing the issue.
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param rawValue The raw server base URL string from configuration (may contain whitespace).
 * @throws WorkerConfigError.ConfigInvalid if the URL fails any validation via Raise DSL.
 */
private fun Raise<WorkerConfigError>.validateServerBaseUrl(rawValue: String) {
    val value = rawValue.trim()
    ensure(!value.isBlank()) { WorkerConfigError.ConfigInvalid("serverBaseUrl must not be blank") }

    val uri = try {
        URI(value)
    } catch (_: Exception) {
        raise(WorkerConfigError.ConfigInvalid("serverBaseUrl must be a valid absolute HTTP(S) URL"))
    }

    ensure(!(!uri.isAbsolute || uri.host.isNullOrBlank())) { WorkerConfigError.ConfigInvalid("serverBaseUrl must be an absolute HTTP(S) URL") }

    val scheme = uri.scheme?.lowercase()
    ensure(!(scheme != "http" && scheme != "https")) { WorkerConfigError.ConfigInvalid("serverBaseUrl scheme must be http or https") }
}

