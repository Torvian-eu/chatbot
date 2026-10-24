package eu.torvian.chatbot.worker.credential.api

import arrow.core.Either
import eu.torvian.chatbot.common.api.resources.WorkerCredentialResource
import eu.torvian.chatbot.common.models.api.credential.AssignedCredentialsDto
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestError
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestExecutor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.request.bearerAuth
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Ktor implementation of [WorkerCredentialApi].
 *
 * @property client Shared HTTP client configured with content negotiation.
 * @property authenticatedRequestExecutor Executor handling token acquisition and retry policy.
 */
class KtorWorkerCredentialApi(
    private val client: HttpClient,
    private val authenticatedRequestExecutor: WorkerAuthenticatedRequestExecutor,
) : WorkerCredentialApi {

    override suspend fun getAssignedCredentials(): Either<WorkerCredentialApiError, AssignedCredentialsDto> {
        logger.debug("Fetching assigned credential profiles and secrets")
        return authenticatedRequestExecutor.execute("fetch assigned credentials") { accessToken ->
            client.get(WorkerCredentialResource.Assigned()) {
                bearerAuth(accessToken)
            }.body<AssignedCredentialsDto>()
        }.mapLeft { error ->
            when (error) {
                is WorkerAuthenticatedRequestError.Auth -> {
                    logger.warn("Failed to fetch assigned credentials due to auth error (error={})", error.error)
                    WorkerCredentialApiError.Auth(error.error)
                }

                is WorkerAuthenticatedRequestError.HttpStatus -> {
                    logger.warn(
                        "Failed to fetch assigned credentials with HTTP error (statusCode={})",
                        error.statusCode
                    )
                    WorkerCredentialApiError.UnexpectedHttpStatus(
                        operation = error.operation,
                        statusCode = error.statusCode,
                        description = error.responseBody
                    )
                }

                is WorkerAuthenticatedRequestError.Transport -> {
                    logger.warn(
                        "Failed to fetch assigned credentials due to transport error (reason={})",
                        error.reason
                    )
                    WorkerCredentialApiError.TransportError(
                        operation = error.operation,
                        message = error.reason
                    )
                }
            }
        }
    }

    private companion object {
        /** Logger used for credential API diagnostics; never receives payload contents. */
        val logger: Logger = LogManager.getLogger(KtorWorkerCredentialApi::class.java)
    }
}
