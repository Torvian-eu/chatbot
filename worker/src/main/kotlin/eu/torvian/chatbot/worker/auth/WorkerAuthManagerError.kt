package eu.torvian.chatbot.worker.auth

import kotlin.time.Instant

/**
 * Logical errors for worker auth orchestration.
 */
sealed interface WorkerAuthManagerError {
    /**
     * Wraps a token-store failure that happened while loading, saving, or clearing the cached token.
     *
     * @property error The underlying token-store logical error.
     */
    data class TokenStore(val error: ServiceTokenStoreError) : WorkerAuthManagerError

    /**
     * Wraps a private-key signing failure while producing the challenge signature.
     *
     * @property error The underlying signer logical error.
     */
    data class ChallengeSigner(val error: ChallengeSignerError) : WorkerAuthManagerError

    /**
     * Wraps a worker-auth API failure returned by the HTTP client layer.
     *
     * @property error The underlying auth API logical error.
     */
    data class AuthApi(val error: WorkerAuthApiError) : WorkerAuthManagerError

    /**
     * Indicates that the server returned an empty challenge payload, which is invalid for signing.
     */
    data object BlankChallengePayload : WorkerAuthManagerError

    /**
     * Indicates that the server challenge is already expired according to the worker clock.
     *
     * @property challengeId Challenge identifier returned by the server.
     * @property expiresAt Server-provided challenge expiration time.
     * @property now Worker-side current time used for the check.
     */
    data class ExpiredChallenge(
        val challengeId: String,
        val expiresAt: Instant,
        val now: Instant
    ) : WorkerAuthManagerError
}

