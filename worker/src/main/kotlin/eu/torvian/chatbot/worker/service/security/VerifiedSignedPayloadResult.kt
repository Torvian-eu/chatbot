package eu.torvian.chatbot.worker.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.SignedRequestPayloadDecodingResult
import eu.torvian.chatbot.common.security.decodePayload
import kotlin.jvm.JvmName

/**
 * Internal outcome of the shared worker-side signed-request verification and payload-decoding pipeline.
 *
 * The worker uses this intermediate result so each validator can keep its own domain-specific rejection
 * model while still sharing the common detached-signature verification and payload-decoding flow.
 *
 * @param T Typed DTO expected in the detached [SignedRequest.payload].
 */
internal sealed interface VerifiedSignedPayloadResult<out T> {
    /**
     * Indicates that the detached signature verified successfully and the payload decoded into [T].
     *
     * @property payload Typed DTO recovered from the signed payload.
     */
    data class Verified<T>(
        val payload: T
    ) : VerifiedSignedPayloadResult<T>

    /**
     * Indicates that detached-signature verification failed.
     *
     * The pipeline still exposes [decodedPayload] when best-effort payload decoding succeeded so callers
     * can preserve correlation metadata on rejection paths without re-decoding.
     *
     * @property error Verification failure returned by [VerificationService].
     * @property decodedPayload Best-effort decoded payload, or `null` when decoding failed.
     */
    data class VerificationFailed<T>(
        val error: VerificationError,
        val decodedPayload: T?
    ) : VerifiedSignedPayloadResult<T>

    /**
     * Indicates that the payload could not be deserialized as valid JSON for the requested DTO shape.
     */
    data object MalformedPayload : VerifiedSignedPayloadResult<Nothing>

    /**
     * Indicates that payload decoding reached DTO construction but rejected the data as invalid.
     */
    data object InvalidPayload : VerifiedSignedPayloadResult<Nothing>
}

/**
 * Verifies a detached signed request and decodes its typed payload with the shared worker pipeline.
 *
 * The payload is decoded before the verification result is interpreted so validators can keep any
 * decoded identifiers needed for rejection correlation when signature verification fails.
 *
 * @receiver Verification service responsible for detached-signature validation.
 * @param signedRequest Detached signed request whose payload should be decoded and trusted conditionally.
 * @param options Verification options controlling timestamp-expiration policy.
 * @param decodePayload Decoder that converts the exact signed payload into the caller's typed DTO.
 * @return Intermediate verification/decoding outcome for domain-specific mapping by the caller.
 */
internal suspend fun <T> VerificationService.verifyAndDecodeSignedPayload(
    signedRequest: SignedRequest,
    options: VerificationOptions = VerificationOptions(),
    decodePayload: (SignedRequest) -> SignedRequestPayloadDecodingResult<T>
): VerifiedSignedPayloadResult<T> {
    // Decode first so validators can reuse typed correlation fields even when verification fails.
    val decodingResult = decodePayload(signedRequest)
    val decodedPayload = when (decodingResult) {
        is SignedRequestPayloadDecodingResult.Decoded -> decodingResult.value
        SignedRequestPayloadDecodingResult.MalformedPayload,
        SignedRequestPayloadDecodingResult.InvalidPayload -> null
    }

    return when (val verificationResult = verify(signedRequest = signedRequest, options = options)) {
        is Either.Left -> VerifiedSignedPayloadResult.VerificationFailed(
            error = verificationResult.value,
            decodedPayload = decodedPayload
        )

        is Either.Right -> when (decodingResult) {
            is SignedRequestPayloadDecodingResult.Decoded -> VerifiedSignedPayloadResult.Verified(
                payload = decodingResult.value
            )

            SignedRequestPayloadDecodingResult.MalformedPayload -> VerifiedSignedPayloadResult.MalformedPayload
            SignedRequestPayloadDecodingResult.InvalidPayload -> VerifiedSignedPayloadResult.InvalidPayload
        }
    }
}

/**
 * Verifies a detached signed request and decodes its typed payload with the default signed-payload decoder.
 *
 * @receiver Verification service responsible for detached-signature validation.
 * @param signedRequest Detached signed request whose payload should be decoded and trusted conditionally.
 * @param options Verification options controlling timestamp-expiration policy.
 * @param T Typed DTO expected in the signed payload.
 * @return Intermediate verification/decoding outcome for domain-specific mapping by the caller.
 */
internal suspend inline fun <reified T> VerificationService.verifyAndDecodeSignedPayload(
    signedRequest: SignedRequest,
    options: VerificationOptions = VerificationOptions()
): VerifiedSignedPayloadResult<T> = verifyAndDecodeSignedPayload(
    signedRequest = signedRequest,
    options = options,
    decodePayload = { request -> request.decodePayload<T>() }
)

/**
 * Verifies and decodes a nullable detached signed request when present.
 *
 * Validators that treat missing detached metadata as a domain-specific rejection can use this overload to
 * share the same verification/decoding pipeline while still mapping the missing-request case locally.
 *
 * @receiver Verification service responsible for detached-signature validation.
 * @param signedRequest Optional detached signed request that may be absent from the relayed worker command.
 * @param options Verification options controlling timestamp-expiration policy.
 * @param decodePayload Decoder that converts the exact signed payload into the caller's typed DTO.
 * @return `null` when no signed request was supplied; otherwise the intermediate verification/decoding outcome.
 */
@JvmName("verifyAndDecodeNullableSignedPayload")
internal suspend fun <T> VerificationService.verifyAndDecodeSignedPayload(
    signedRequest: SignedRequest?,
    options: VerificationOptions = VerificationOptions(),
    decodePayload: (SignedRequest) -> SignedRequestPayloadDecodingResult<T>
): VerifiedSignedPayloadResult<T>? = signedRequest?.let { request ->
    verifyAndDecodeSignedPayload(
        signedRequest = request,
        options = options,
        decodePayload = decodePayload
    )
}

/**
 * Verifies and decodes a nullable detached signed request with the default signed-payload decoder when present.
 *
 * @receiver Verification service responsible for detached-signature validation.
 * @param signedRequest Optional detached signed request that may be absent from the relayed worker command.
 * @param options Verification options controlling timestamp-expiration policy.
 * @param T Typed DTO expected in the signed payload.
 * @return `null` when no signed request was supplied; otherwise the intermediate verification/decoding outcome.
 */
@JvmName("verifyAndDecodeNullableSignedPayloadReified")
internal suspend inline fun <reified T> VerificationService.verifyAndDecodeSignedPayload(
    signedRequest: SignedRequest?,
    options: VerificationOptions = VerificationOptions()
): VerifiedSignedPayloadResult<T>? = signedRequest?.let { request ->
    verifyAndDecodeSignedPayload<T>(
        signedRequest = request,
        options = options
    )
}