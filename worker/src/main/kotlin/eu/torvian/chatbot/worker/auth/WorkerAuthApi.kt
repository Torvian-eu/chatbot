package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenChallengeResponse
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenResponse

/**
 * HTTP-level worker authentication client.
 *
 * This interface isolates the worker auth manager from Ktor and maps transport problems
 * into logical [WorkerAuthApiError] values.
 */
interface WorkerAuthApi {
    /**
     * Fetches a new worker challenge from the server.
     *
     * @param workerId Worker identifier.
     * @param certificateFingerprint Worker certificate fingerprint.
     * @return The issued challenge or a logical auth API error.
     */
    suspend fun createChallenge(workerId: Long, certificateFingerprint: String): Either<WorkerAuthApiError, ServiceTokenChallengeResponse>

    /**
     * Exchanges a signed challenge for a service token.
     *
     * @param workerId Worker identifier.
     * @param challengeId Challenge identifier.
     * @param signatureBase64 Base64 signature for the challenge.
     * @return The issued service token or a logical auth API error.
     */
    suspend fun exchangeServiceToken(workerId: Long, challengeId: String, signatureBase64: String): Either<WorkerAuthApiError, ServiceTokenResponse>
}

