package eu.torvian.chatbot.worker.mcp.api

import arrow.core.Either
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestError
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestExecutor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.request.bearerAuth
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Ktor-based implementation of [WorkerMcpServerApi].
 *
 * Fetches MCP server configurations assigned to this worker via authenticated REST API calls
 * and translates errors into logical [WorkerMcpServerApiError] values.
 */
class KtorWorkerMcpServerApi(
    /**
     * Configured Ktor HTTP client used for API requests.
     *
     * This client is shared across the worker runtime and must be pre-configured with appropriate
     * plugins (content negotiation, logging, etc.) before use.
     */
    private val client: HttpClient,

    /**
     * Executor that handles token acquisition and 401/403 retry logic.
     *
     * Centralizes auth policy so that all authenticated API calls follow the same pattern
     * and share consistent token refresh behavior.
     */
    private val authenticatedRequestExecutor: WorkerAuthenticatedRequestExecutor
) : WorkerMcpServerApi {

    override suspend fun getAssignedServers(): Either<WorkerMcpServerApiError, List<LocalMCPServerDto>> {
        logger.debug("Fetching worker-assigned MCP servers")
        return authenticatedRequestExecutor.execute("fetch assigned servers") { accessToken ->
            client.get(LocalMCPServerResource.Assigned()) {
                bearerAuth(accessToken)
            }.body<List<LocalMCPServerDto>>()
        }.mapLeft { error ->
            when (error) {
                is WorkerAuthenticatedRequestError.Auth -> {
                    logger.warn("Failed to fetch assigned servers due to auth error (error={})", error.error)
                    WorkerMcpServerApiError.Auth(error.error)
                }
                is WorkerAuthenticatedRequestError.HttpStatus -> {
                    logger.warn(
                        "Failed to fetch assigned servers with HTTP error (statusCode={})",
                        error.statusCode
                    )
                    WorkerMcpServerApiError.UnexpectedHttpStatus(
                        operation = error.operation,
                        statusCode = error.statusCode,
                        description = error.responseBody
                    )
                }
                is WorkerAuthenticatedRequestError.Transport -> {
                    logger.warn("Failed to fetch assigned servers due to transport error (reason={})", error.reason)
                    WorkerMcpServerApiError.TransportError(
                        operation = error.operation,
                        message = error.reason
                    )
                }
            }
        }
    }

    companion object {
        /**
         * Logger used for MCP server API call diagnostics.
         *
         * Logs debug messages for operation starts and warnings for HTTP failures.
         */
        private val logger: Logger = LogManager.getLogger(KtorWorkerMcpServerApi::class.java)
    }
}
