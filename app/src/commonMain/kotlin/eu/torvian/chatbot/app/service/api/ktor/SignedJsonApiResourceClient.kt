package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.security.RequestSigningError
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.toDetachedSignatureHeaders
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Shared base class for API clients that need opt-in detached signing for selected JSON requests.
 *
 * This helper keeps signed-request handling explicit: subclasses choose which calls should serialize,
 * sign, and send the exact same JSON string. It does not globally affect unrelated API requests.
 *
 * @param client Ktor HTTP client used for transport.
 * @param json JSON codec used to serialize the exact request payload that will be signed and sent.
 * @param requestSigningService Detached request-signing service used to authorize selected request bodies.
 */
abstract class SignedJsonApiResourceClient(
    client: HttpClient,
    private val json: Json,
    private val requestSigningService: RequestSigningService
) : BaseApiResourceClient(client) {

    /**
     * Serializes [request] once, signs that exact JSON payload, and executes [execute] within the standard API
     * error-handling flow.
     *
     * @param Request Request DTO type that should be serialized and signed.
     * @param Response Successful response type returned by [execute].
     * @param request Request DTO whose serialized JSON must remain byte-for-byte identical to the body sent.
     * @param serializer Serializer used to encode [request] into its transport JSON string.
     * @param execute HTTP call that must use the returned [SignedRequest] unchanged for transport metadata.
     * @return Either an [ApiResourceError] or the successful response payload.
     */
    protected suspend fun <Request : Any, Response> safeSignedJsonApiCall(
        request: Request,
        serializer: KSerializer<Request>,
        execute: suspend (SignedRequest) -> Response
    ): Either<ApiResourceError, Response> = either {
        val signedRequest = signJsonRequest(request, serializer).bind()

        safeApiCall {
            execute(signedRequest)
        }.bind()
    }

    /**
     * Serializes [request] into JSON and signs that exact string for later detached transport.
     *
     * @param Request Request DTO type that should be encoded and signed.
     * @param request Request DTO value to encode.
     * @param serializer Serializer used for [request].
     * @return Either an [ApiResourceError] or the detached [SignedRequest].
     */
    protected suspend fun <Request : Any> signJsonRequest(
        request: Request,
        serializer: KSerializer<Request>
    ): Either<ApiResourceError, SignedRequest> = either {
        val payload = serializeJsonRequestBody(request, serializer).bind()
        requestSigningService.signPayload(payload).mapLeft { signingError ->
            signingError.toApiResourceError()
        }.bind()
    }

    /**
     * Applies the exact signed JSON payload and detached signature headers to the outgoing HTTP request.
     *
     * @receiver Builder for the outgoing HTTP request.
     * @param signedRequest Detached signed request whose payload and headers must stay aligned.
     */
    protected fun HttpRequestBuilder.applyDetachedSignedJsonBody(signedRequest: SignedRequest) {
        // Send the exact payload string returned from the signing step so detached verification stays byte-for-byte aligned.
        contentType(ContentType.Application.Json)
        setBody(signedRequest.payload)
        signedRequest.toDetachedSignatureHeaders().forEach { (headerName, headerValue) ->
            header(headerName, headerValue)
        }
    }

    /**
     * Serializes [request] into the exact JSON body string that will later be signed and sent.
     *
     * @param Request Request DTO type.
     * @param request Request DTO value to encode.
     * @param serializer Serializer used for [request].
     * @return Either a serialization-related [ApiResourceError] or the exact JSON payload string.
     */
    private fun <Request : Any> serializeJsonRequestBody(
        request: Request,
        serializer: KSerializer<Request>
    ): Either<ApiResourceError, String> = try {
        Either.Right(json.encodeToString(serializer, request))
    } catch (error: SerializationException) {
        Either.Left(
            ApiResourceError.SerializationError(
                description = "Failed to serialize request body for detached signing: ${error.message}",
                cause = error
            )
        )
    }

    /**
     * Converts detached-signing preparation failures into API-client level errors.
     *
     * Serialization failures stay serialization-specific, while signer identity and cryptographic failures surface as
     * local unknown client errors because the HTTP request could not be prepared for transport.
     *
     * @receiver Signing error produced before any HTTP request is sent.
     * @return Equivalent [ApiResourceError] for callers of API clients.
     */
    private fun RequestSigningError.toApiResourceError(): ApiResourceError = when (this) {
        is RequestSigningError.SerializationFailure -> ApiResourceError.SerializationError(
            description = message,
            cause = cause
        )

        is RequestSigningError.IdentityMissing,
        is RequestSigningError.CryptoFailure -> ApiResourceError.UnknownError(
            description = message,
            cause = cause
        )
    }
}