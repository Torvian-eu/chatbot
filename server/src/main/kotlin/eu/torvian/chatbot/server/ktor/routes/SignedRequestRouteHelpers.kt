package eu.torvian.chatbot.server.ktor.routes

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.SignedRequestHttpHeaders
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager

/**
 * Logger used by generic detached signed-request route helpers.
 */
private val signedRequestRouteLogger = LogManager.getLogger("SignedRequestRouteHelpers")

/**
 * Reads the exact request body string first, then decodes detached signing headers and the typed DTO from that same
 * string so the persisted payload remains byte-for-byte identical to what the client signed.
 *
 * @receiver Current routing context.
 * @param json JSON codec used to decode the DTO without reserializing the original body.
 * @return Either a structured API error or the decoded DTO paired with its detached signed-request metadata.
 */
internal suspend inline fun <reified T : Any> RoutingContext.receiveDetachedSignedRequest(
    json: Json
): Either<ApiError, Pair<T, SignedRequest>> = either {
    // The raw body must be captured before decoding so signature persistence keeps the original JSON bytes intact.
    val rawBody = call.receiveText()
    val signedRequest = requireDetachedSignedRequest(payload = rawBody).bind()
    val request = decodeDetachedSignedRequestBody<T>(json = json, rawBody = rawBody).bind()
    request to signedRequest
}

/**
 * Extracts the detached request-signing metadata required for signed HTTP requests.
 *
 * Unsigned requests are rejected explicitly so routes that depend on persisted end-to-end attestation data do not
 * accidentally accept bodies without the detached metadata needed for later verification.
 *
 * @receiver Current routing context.
 * @param payload Exact raw request body string that must be preserved for later verification.
 * @return Either a structured API error or a [SignedRequest] combining the raw payload with detached header values.
 */
internal fun RoutingContext.requireDetachedSignedRequest(payload: String): Either<ApiError, SignedRequest> {
    val headers = call.request.headers
    val missingHeaders = SignedRequestHttpHeaders.all.filter { headerName ->
        headers[headerName].isNullOrBlank()
    }

    if (missingHeaders.isNotEmpty()) {
        signedRequestRouteLogger.warn(
            "Rejecting signed request without detached signing headers (uri={}, missingHeaders={})",
            call.request.uri,
            missingHeaders.joinToString(",")
        )
        return apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Detached signing headers are required for signed requests",
            "missingHeaders" to missingHeaders.joinToString(",")
        ).left()
    }

    val timestampHeader = headers[SignedRequestHttpHeaders.TIMESTAMP]
    val timestamp = timestampHeader?.toLongOrNull()
    if (timestamp == null) {
        signedRequestRouteLogger.warn(
            "Rejecting signed request with invalid detached signing timestamp (uri={}, header={})",
            call.request.uri,
            SignedRequestHttpHeaders.TIMESTAMP
        )
        return apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Detached signing timestamp must be a valid epoch-millisecond value",
            "field" to SignedRequestHttpHeaders.TIMESTAMP
        ).left()
    }

    return Either.Right(
        SignedRequest(
            payload = payload,
            signature = requireNotNull(headers[SignedRequestHttpHeaders.SIGNATURE]),
            signerId = requireNotNull(headers[SignedRequestHttpHeaders.SIGNER_ID]),
            timestamp = timestamp,
            nonce = requireNotNull(headers[SignedRequestHttpHeaders.NONCE])
        )
    )
}

/**
 * Decodes the raw signed request body into the expected DTO type after the exact body string has already been
 * captured for detached signature persistence.
 *
 * @receiver Current routing context.
 * @param json JSON codec used for request deserialization.
 * @param rawBody Exact request body string received over HTTP.
 * @return Either a structured API error or the decoded request DTO.
 */
internal inline fun <reified T : Any> RoutingContext.decodeDetachedSignedRequestBody(
    json: Json,
    rawBody: String
): Either<ApiError, T> = try {
    Either.Right(json.decodeFromString<T>(rawBody))
} catch (error: SerializationException) {
    signedRequestRouteLogger.warn(
        "Rejecting malformed signed request body (uri={}, error={})",
        call.request.uri,
        error.message ?: "serialization failed"
    )
    apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid signed request body",
        "reason" to (error.message ?: "request JSON is invalid")
    ).left()
} catch (error: IllegalArgumentException) {
    signedRequestRouteLogger.warn(
        "Rejecting invalid signed request body (uri={}, error={})",
        call.request.uri,
        error.message ?: "invalid argument"
    )
    apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid signed request body",
        "reason" to (error.message ?: "request JSON is invalid")
    ).left()
}