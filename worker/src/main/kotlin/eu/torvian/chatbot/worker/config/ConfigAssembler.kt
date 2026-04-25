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
 * @receiver The [AppConfigDto] to convert and validate.
 * @return Either a logical configuration error or the fully validated [Configuration].
 */
fun AppConfigDto.toDomain(): Either<WorkerConfigError, Configuration> = either {
    val worker = worker ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker"))

    val serverDto = worker.server ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.server"))
    val identityDto = worker.identity ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.identity"))
    val storageDto = worker.storage ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.storage"))
    val authDto = worker.auth ?: AuthConfigDto()

    val baseUrl = required("worker.server.baseUrl", serverDto.baseUrl)
    val uid = required("worker.identity.uid", identityDto.uid)
    val certificateFingerprint = required("worker.identity.certificateFingerprint", identityDto.certificateFingerprint)
    val certificatePem = required("worker.identity.certificatePem", identityDto.certificatePem)
    val secretsJsonPath = required("worker.storage.secretsJsonPath", storageDto.secretsJsonPath)
    val tokenFilePath = required("worker.storage.tokenFilePath", storageDto.tokenFilePath)
    val refreshSkewSeconds = authDto.refreshSkewSeconds ?: 60L

    validateServerBaseUrl(baseUrl)
    ensure(uid.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.uid must not be blank")
    }
    ensure(certificateFingerprint.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.certificateFingerprint must not be blank")
    }
    ensure(certificatePem.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.certificatePem must not be blank")
    }
    ensure(secretsJsonPath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.storage.secretsJsonPath must not be blank")
    }
    ensure(tokenFilePath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.storage.tokenFilePath must not be blank")
    }
    ensure(refreshSkewSeconds >= 0) {
        WorkerConfigError.ConfigInvalid("worker.auth.refreshSkewSeconds must be >= 0")
    }

    Configuration(
        setupRequired = setup?.required ?: true,
        worker = RuntimeConfig(
            server = ServerConfig(
                baseUrl = baseUrl
            ),
            identity = IdentityConfig(
                uid = uid,
                certificateFingerprint = certificateFingerprint,
                certificatePem = certificatePem
            ),
            storage = StorageConfig(
                secretsJsonPath = secretsJsonPath,
                tokenFilePath = tokenFilePath
            ),
            auth = AuthConfig(
                refreshSkewSeconds = refreshSkewSeconds
            )
        )
    )
}

/**
 * Extracts and validates a required string configuration value.
 *
 * If the value is null or missing, raises a configuration error with the path
 * identifying which field is missing. The caller is responsible for further
 * validation of the extracted value (for example checking if it's non-blank).
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param path The configuration path (for example "worker.server.baseUrl") used in error messages.
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
    ensure(!value.isBlank()) {
        WorkerConfigError.ConfigInvalid("worker.server.baseUrl must not be blank")
    }

    val uri = try {
        URI(value)
    } catch (_: Exception) {
        raise(WorkerConfigError.ConfigInvalid("worker.server.baseUrl must be a valid absolute HTTP(S) URL"))
    }

    ensure(uri.isAbsolute && !uri.host.isNullOrBlank()) {
        WorkerConfigError.ConfigInvalid("worker.server.baseUrl must be an absolute HTTP(S) URL")
    }

    val scheme = uri.scheme?.lowercase()
    ensure(scheme == "http" || scheme == "https") {
        WorkerConfigError.ConfigInvalid("worker.server.baseUrl scheme must be http or https")
    }
}
