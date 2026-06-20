package eu.torvian.chatbot.worker.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Validates detached signed requests against the worker's local signer trust store.
 */
interface VerificationService {
    /**
     * Verifies detached signed-request metadata and returns the list of permissions if valid.
     *
     * @param signedRequest Detached signed request to validate before applying privileged worker behavior.
     * @param options Allows disabling expiration checks for persistent stored signatures, such as MCP config.
     * @return Either a logical verification failure or the trusted signer's permissions.
     */
    suspend fun verify(
        signedRequest: SignedRequest,
        options: VerificationOptions = VerificationOptions()
    ): Either<VerificationError, List<String>>
}
