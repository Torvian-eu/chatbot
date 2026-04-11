package eu.torvian.chatbot.worker.auth

import arrow.core.Either

/**
 * Signs server-issued worker challenges using the worker's private key material.
 */
interface ChallengeSigner {
    /**
     * Produces a Base64-encoded signature for the provided challenge payload.
     *
     * @param challenge Plain-text challenge string received from the server.
     * @return Signature text on success or a logical signing error.
     */
    fun sign(challenge: String): Either<ChallengeSignerError, String>
}

