package eu.torvian.chatbot.common.security

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Default JSON policy for generic signed-payload decoding.
 *
 * Unknown keys are ignored so detached signed payloads can remain forward-compatible when callers
 * only need to recover the typed subset they understand. Callers that need stricter behavior can
 * still pass an explicit [Json] instance to the decode helpers.
 */
@PublishedApi
internal val signedRequestPayloadDecodingJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Structured outcome of decoding one [SignedRequest.payload] into a typed DTO.
 *
 * @param T Target DTO type requested by the caller.
 */
sealed interface SignedRequestPayloadDecodingResult<out T> {
    /**
     * Indicates that the signed payload was decoded successfully.
     *
     * @property value Typed DTO recovered from the exact stored payload string.
     */
    data class Decoded<T>(
        val value: T
    ) : SignedRequestPayloadDecodingResult<T>

    /**
     * Indicates that the signed payload was malformed JSON or otherwise could not be deserialized.
     */
    data object MalformedPayload : SignedRequestPayloadDecodingResult<Nothing>

    /**
     * Indicates that payload decoding reached DTO construction but rejected the data as invalid.
     */
    data object InvalidPayload : SignedRequestPayloadDecodingResult<Nothing>
}

/**
 * Attempts to decode the exact stored [SignedRequest.payload] into [T].
 *
 * This helper centralizes the shared signed-payload decoding contract while leaving payload-specific
 * interpretation and verification behavior to the caller.
 *
 * @receiver Detached signed request whose payload contains the exact JSON string that was signed.
 * @param json JSON codec that should be used for payload deserialization. Defaults to the shared
 *   signed-payload decoder policy.
 * @param T Target DTO type the caller expects to recover from [SignedRequest.payload].
 * @return Structured decoding result that preserves whether deserialization succeeded.
 */
inline fun <reified T> SignedRequest.decodePayload(
    json: Json = signedRequestPayloadDecodingJson
): SignedRequestPayloadDecodingResult<T> {
    return try {
        SignedRequestPayloadDecodingResult.Decoded(
            value = json.decodeFromString(payload)
        )
    } catch (_: SerializationException) {
        SignedRequestPayloadDecodingResult.MalformedPayload
    } catch (_: IllegalArgumentException) {
        SignedRequestPayloadDecodingResult.InvalidPayload
    }
}

/**
 * Attempts to decode the exact stored [SignedRequest.payload] into [T], suppressing decode failures.
 *
 * Callers that only need a best-effort typed DTO can use this convenience wrapper and keep any
 * payload-specific fallback behavior local.
 *
 * @receiver Detached signed request whose payload contains the exact JSON string that was signed.
 * @param json JSON codec that should be used for payload deserialization. Defaults to the shared
 *   signed-payload decoder policy.
 * @param T Target DTO type the caller expects to recover from [SignedRequest.payload].
 * @return Decoded DTO, or `null` when the signed payload is malformed or incompatible with [T].
 */
inline fun <reified T> SignedRequest.decodePayloadOrNull(
    json: Json = signedRequestPayloadDecodingJson
): T? {
    return when (val result = decodePayload<T>(json)) {
        is SignedRequestPayloadDecodingResult.Decoded -> result.value
        SignedRequestPayloadDecodingResult.MalformedPayload,
        SignedRequestPayloadDecodingResult.InvalidPayload -> null
    }
}