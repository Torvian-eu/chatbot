package eu.torvian.chatbot.app.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.KSerializer

/**
 * Service that signs exact request payloads without wrapping them in a transport envelope.
 *
 * The resulting [SignedRequest] keeps the canonical JSON payload string plus detached signature metadata so callers
 * can send the body normally and propagate authorization metadata separately at the protocol layer.
 */
interface RequestSigningService {

    /**
     * Serializes [request] to JSON and signs the resulting exact payload string.
     *
     * @param T Static request DTO type.
     * @param request Request value that should become the canonical signed body.
     * @param serializer Serializer used to encode [request] into JSON.
     * @return Either signing error or detached signed-request data.
     */
    suspend fun <T : Any> signRequest(
        request: T,
        serializer: KSerializer<T>
    ): Either<RequestSigningError, SignedRequest>

    /**
     * Signs an already serialized request payload exactly as provided.
     *
     * @param payload Exact body string that should be preserved and signed byte-for-byte.
     * @return Either signing error or detached signed-request data.
     */
    suspend fun signPayload(payload: String): Either<RequestSigningError, SignedRequest>
}