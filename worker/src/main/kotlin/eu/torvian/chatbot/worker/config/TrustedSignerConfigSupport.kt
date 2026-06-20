package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlin.io.encoding.Base64

/**
 * Builds a normalized trusted-signer DTO from raw operator-provided values.
 *
 * The worker uses the same validation rules for CLI-driven config mutations and runtime config
 * assembly so trusted signer data behaves consistently no matter how it enters the system.
 *
 * @param signerId Stable signer identifier supplied by the operator.
 * @param publicKeyBase64 Base64-encoded public key supplied by the operator.
 * @param permissionsCsv Optional comma-separated permission list supplied by the operator.
 * @param signerPath Configuration path label used in validation messages.
 * @return Either a logical configuration error or a normalized [TrustedSignerDto].
 */
fun trustedSignerDtoFromInput(
    signerId: String,
    publicKeyBase64: String,
    permissionsCsv: String?,
    signerPath: String = "worker.trustedSigners[0]"
): Either<WorkerConfigError, TrustedSignerDto> = either {
    normalizeTrustedSignerDto(
        TrustedSignerDto(
            signerId = signerId,
            publicKeyBase64 = publicKeyBase64,
            permissions = parseTrustedSignerPermissions(permissionsCsv)
        ),
        signerPath = signerPath
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
internal fun Raise<WorkerConfigError>.trustedSignerToDomain(dto: TrustedSignerDto, index: Int): TrustedSigner {
    val signerPath = "worker.trustedSigners[$index]"
    val normalizedDto = normalizeTrustedSignerDto(dto, signerPath)

    return TrustedSigner(
        signerId = normalizedDto.signerId,
        publicKey = decodeBase64ByteArray("$signerPath.publicKeyBase64", normalizedDto.publicKeyBase64),
        permissions = normalizedDto.permissions
    )
}

/**
 * Normalizes and validates the storage DTO representation of a trusted signer.
 *
 * The DTO stays distinct from the runtime [TrustedSigner] model so the persisted config keeps the
 * Base64 key material as text, while runtime consumers receive decoded bytes.
 *
 * @receiver The [Raise] context used to propagate configuration errors.
 * @param dto DTO value to normalize.
 * @param signerPath Configuration path label used in validation messages.
 * @return A normalized DTO ready for persistence.
 */
internal fun Raise<WorkerConfigError>.normalizeTrustedSignerDto(
    dto: TrustedSignerDto,
    signerPath: String
): TrustedSignerDto {
    val normalizedSignerId = dto.signerId.trim()
    ensure(normalizedSignerId.isNotEmpty()) {
        WorkerConfigError.ConfigInvalid("$signerPath.signerId must not be blank")
    }

    val normalizedPublicKeyBase64 = dto.publicKeyBase64.trim()
    decodeBase64ByteArray("$signerPath.publicKeyBase64", normalizedPublicKeyBase64)

    return dto.copy(
        signerId = normalizedSignerId,
        publicKeyBase64 = normalizedPublicKeyBase64,
        permissions = normalizeTrustedSignerPermissions(dto.permissions)
    )
}

/**
 * Parses a comma-separated permissions argument into a normalized ordered list.
 *
 * Empty fragments are discarded and duplicates are removed while preserving the first observed
 * value because permission order may be operator-authored for readability.
 *
 * @param permissionsCsv Raw comma-separated CLI value.
 * @return Normalized permission list.
 */
internal fun parseTrustedSignerPermissions(permissionsCsv: String?): List<String> {
    if (permissionsCsv == null) {
        return emptyList()
    }

    return normalizeTrustedSignerPermissions(permissionsCsv.split(','))
}

/**
 * Normalizes trusted signer permissions by trimming, dropping empty entries, and deduplicating.
 *
 * @param permissions Raw permission values.
 * @return Normalized permission list.
 */
private fun normalizeTrustedSignerPermissions(permissions: List<String>): List<String> {
    val normalizedPermissions = LinkedHashSet<String>()
    permissions.forEach { permission ->
        val normalizedPermission = permission.trim()
        if (normalizedPermission.isNotEmpty()) {
            normalizedPermissions += normalizedPermission
        }
    }
    return normalizedPermissions.toList()
}

/**
 * Decodes a Base64 byte string into binary configuration data.
 *
 * The decoder is intentionally strict: whitespace is only tolerated around the whole value,
 * and the value must contain at least one byte. This keeps configuration mistakes visible at
 * startup instead of deferring them to signature verification.
 *
 * @receiver The [Raise] context used to propagate configuration errors.
 * @param path Configuration path used in validation error messages.
 * @param rawValue Base64 string to decode.
 * @return Decoded bytes represented by [rawValue].
 * @throws WorkerConfigError.ConfigInvalid if the value is blank, not valid Base64, or decodes to no bytes via Raise DSL.
 */
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