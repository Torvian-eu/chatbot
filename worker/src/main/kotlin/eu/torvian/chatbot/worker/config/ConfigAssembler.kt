package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    val displayName = identityDto.displayName?.takeIf { it.isNotBlank() } ?: "my-worker"
    val certificateFingerprint = required("worker.identity.certificateFingerprint", identityDto.certificateFingerprint)
    val certificatePem = required("worker.identity.certificatePem", identityDto.certificatePem)
    val secretsJsonPath = required("worker.storage.secretsJsonPath", storageDto.secretsJsonPath)
    val tokenFilePath = required("worker.storage.tokenFilePath", storageDto.tokenFilePath)
    val refreshSkewSeconds = authDto.refreshSkewSeconds ?: 60L
    val trustedSigners = worker.trustedSigners.orEmpty().mapIndexed { index, signerDto ->
        trustedSignerToDomain(signerDto, index)
    }

    validateServerBaseUrl(baseUrl)
    ensure(uid.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.uid must not be blank")
    }
    ensure(displayName.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.displayName must not be blank")
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
                displayName = displayName,
                certificateFingerprint = certificateFingerprint,
                certificatePem = certificatePem
            ),
            storage = StorageConfig(
                secretsJsonPath = secretsJsonPath,
                tokenFilePath = tokenFilePath
            ),
            auth = AuthConfig(
                refreshSkewSeconds = refreshSkewSeconds
            ),
            trustedSigners = trustedSigners
        )
    )
}

/**
 * Converts a trusted signer DTO into the strict domain representation.
 *
 * Validation is performed while assembling configuration so invalid trust-store entries fail
 * startup before worker services attempt to verify any signed payloads.
 *
 * @receiver The [Raise] context used to propagate configuration errors.
 * @param dto Nullable-layer DTO entry to validate and decode.
 * @param index Zero-based index of the signer entry within `worker.trustedSigners`.
 * @return The decoded trusted signer domain model.
 * @throws WorkerConfigError.ConfigInvalid if the signer identifier or public key is invalid via Raise DSL.
 */
private fun Raise<WorkerConfigError>.trustedSignerToDomain(dto: TrustedSignerDto, index: Int): TrustedSigner {
    val signerPath = "worker.trustedSigners[$index]"
    ensure(dto.signerId.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("$signerPath.signerId must not be blank")
    }

    return TrustedSigner(
        signerId = dto.signerId,
        publicKey = decodeBase64ByteArray("$signerPath.publicKeyBase64", dto.publicKeyBase64),
        permissions = dto.permissions
    )
}

/**
 * Decodes a Base64 byte string into binary configuration data.
 *
 * The decoder is intentionally strict: whitespace is only tolerated around the whole value,
 * and the value must contain at least one byte. This keeps
 * configuration mistakes visible at startup instead of deferring them to signature verification.
 *
 * @receiver The [Raise] context used to propagate configuration errors.
 * @param path Configuration path used in validation error messages.
 * @param rawValue Base64 string to decode.
 * @return Decoded bytes represented by [rawValue].
 * @throws WorkerConfigError.ConfigInvalid if the value is blank, not valid Base64, or decodes to no bytes via Raise DSL.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun Raise<WorkerConfigError>.decodeBase64ByteArray(path: String, rawValue: String): ByteArray {
    val value = rawValue.trim()
    ensure(value.isNotEmpty()) {
        WorkerConfigError.ConfigInvalid("$path must not be blank")
    }

    val bytes = try {
        Base64.decode(value)
    } catch (_: IllegalArgumentException) {
        raise(WorkerConfigError.ConfigInvalid("$path must be valid Base64"))
    }

    ensure(bytes.isNotEmpty()) {
        WorkerConfigError.ConfigInvalid("$path must decode to at least one byte")
    }

    return bytes
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
