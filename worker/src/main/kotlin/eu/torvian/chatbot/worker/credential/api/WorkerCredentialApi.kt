package eu.torvian.chatbot.worker.credential.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.AssignedCredentialsDto
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerError

/**
 * HTTP API client for the worker credential bootstrap.
 *
 * The response carries the worker id the server resolved, which is the only identity the worker can
 * compare relayed rows against.
 */
interface WorkerCredentialApi {
    /**
     * @return Either a logical API error or everything assigned to the calling worker.
     */
    suspend fun getAssignedCredentials(): Either<WorkerCredentialApiError, AssignedCredentialsDto>
}

/**
 * Logical errors surfaced by the worker credential HTTP API.
 */
sealed interface WorkerCredentialApiError {
    /**
     * Worker authentication or token management failed.
     *
     * @property error Underlying auth manager error.
     */
    data class Auth(val error: WorkerAuthManagerError) : WorkerCredentialApiError

    /**
     * Unexpected HTTP status returned by the server.
     *
     * @property operation Human-readable operation name.
     * @property statusCode HTTP status code returned by the server.
     * @property description Optional error details from the server response body.
     */
    data class UnexpectedHttpStatus(
        val operation: String,
        val statusCode: Int,
        val description: String? = null,
    ) : WorkerCredentialApiError

    /**
     * A network or serialization error occurred during the API call.
     *
     * @property operation Human-readable operation name.
     * @property message Error description.
     */
    data class TransportError(
        val operation: String,
        val message: String,
    ) : WorkerCredentialApiError
}
