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
     * @param workerUid Worker UID.
     * @param certificateFingerprint Worker certificate fingerprint.
     * @return The issued challenge or a logical auth API error.
     */
    suspend fun createChallenge(workerUid: String, certificateFingerprint: String): Either<WorkerAuthApiError, ServiceTokenChallengeResponse>

    /**
     * Exchanges a signed challenge for a service token.
     *
     * @param workerUid Worker UID.
     * @param challengeId Challenge identifier.
     * @param signatureBase64 Base64 signature for the challenge.
     * @return The issued service token or a logical auth API error.
     */
    suspend fun exchangeServiceToken(workerUid: String, challengeId: String, signatureBase64: String): Either<WorkerAuthApiError, ServiceTokenResponse>
}

