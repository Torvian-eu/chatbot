package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenChallengeRequest
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenChallengeResponse
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenRequest
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Ktor-based implementation of [WorkerAuthApi].
 *
 * It speaks to the server's worker authentication endpoints and translates HTTP-level
 * failures into logical [WorkerAuthApiError] values for the caller.
 *
 * @property client Configured Ktor client used for auth requests.
 */
class KtorWorkerAuthApi(
    private val client: HttpClient
) : WorkerAuthApi {
    override suspend fun createChallenge(workerId: Long, certificateFingerprint: String): Either<WorkerAuthApiError, ServiceTokenChallengeResponse> {
        logger.debug("Requesting worker challenge (workerId={}, fingerprintSuffix={})", workerId, certificateFingerprint.takeLast(8))
        return execute("create challenge", workerId) {
            client.post(AuthResource.ServiceTokenChallenge()) {
                setBody(ServiceTokenChallengeRequest(workerId = workerId, certificateFingerprint = certificateFingerprint))
            }.body<ServiceTokenChallengeResponse>()
        }
    }

    override suspend fun exchangeServiceToken(
        workerId: Long,
        challengeId: String,
        signatureBase64: String
    ): Either<WorkerAuthApiError, ServiceTokenResponse> {
        logger.debug("Exchanging worker challenge for service token (workerId={}, challengeId={})", workerId, challengeId)
        return execute("exchange service token", workerId) {
            client.post(AuthResource.ServiceToken()) {
                setBody(
                    ServiceTokenRequest(
                        workerId = workerId,
                        challengeId = challengeId,
                        signatureBase64 = signatureBase64
                    )
                )
            }.body<ServiceTokenResponse>()
        }
    }

    /**
     * Wraps HTTP exceptions and maps them into a logical [WorkerAuthApiError].
     *
     * The helper keeps transport concerns localized: success values flow through unchanged,
     * server failures are mapped by status code, and unexpected exceptions become a generic
     * logical auth failure.
     *
     * @param operation Human-readable operation name used for logging and mapping.
     * @param workerId Worker identifier associated with the request.
     * @param block HTTP call block to execute.
     * @return The successful value or a mapped worker-auth API error.
     */
    private suspend fun <T> execute(operation: String, workerId: Long, block: suspend () -> T): Either<WorkerAuthApiError, T> {
        return try {
            block().right()
        } catch (e: ResponseException) {
            val bodyText = runCatching { e.response.bodyAsText() }.getOrNull()
            mapHttpFailure(operation, workerId, e.response.status.value, bodyText).left()
        } catch (e: Exception) {
            logger.warn("Unexpected failure while {} for worker {}", operation, workerId, e)
            WorkerAuthApiError.TransportFailure(operation, e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    /**
     * Maps an HTTP status and optional response body into a logical worker-auth error.
     *
     * @param operation Human-readable operation name.
     * @param workerId Worker identifier associated with the request.
     * @param statusCode HTTP status returned by the server.
     * @param responseBody Optional raw response body for diagnostics.
     * @return The logical auth API error corresponding to the HTTP response.
     */
    private fun mapHttpFailure(operation: String, workerId: Long, statusCode: Int, responseBody: String?): WorkerAuthApiError {
        val description = responseBody?.takeIf { it.isNotBlank() }

        return when (statusCode) {
            // The server contract uses 404 when the worker identity is unknown.
            404 -> WorkerAuthApiError.WorkerNotFound(workerId)

            // The server contract uses 401 when the worker certificate proof is rejected.
            401 -> WorkerAuthApiError.InvalidCredentials("$operation: invalid credentials")

            // Keep explicit status/body for diagnostics and future retry-policy tuning.
            in 400..499 -> WorkerAuthApiError.UnexpectedHttpStatus(operation, statusCode, description)

            // Preserve server-failure status codes without introducing extra semantics yet.
            in 500..599 -> WorkerAuthApiError.UnexpectedHttpStatus(operation, statusCode, description)

            // Any non-standard status is still surfaced verbatim.
            else -> WorkerAuthApiError.UnexpectedHttpStatus(operation, statusCode, description)
        }
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(KtorWorkerAuthApi::class.java)
    }
}

