package eu.torvian.chatbot.worker.mcp.api

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.worker.mcp.McpServerConfigStore
import eu.torvian.chatbot.worker.mcp.SignedMcpServerConfigValidationResult
import eu.torvian.chatbot.worker.mcp.SignedMcpServerConfigValidator
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Bootstrapper that fetches assigned MCP server configurations and populates the config store.
 *
 * This is called once after successful session handshake to load all worker-assigned
 * server configurations from the server. The loaded configs are cached locally so that
 * the MCP runtime service can resolve server configuration without network calls.
 *
 * @property mcpServerApi HTTP API client for fetching assigned server configs.
 * @property signedMcpServerConfigValidator Authorization validator used before cached configs are trusted.
 * @property configStore Local config store to populate with assigned servers.
 */
class AssignedConfigBootstrapper(
    private val mcpServerApi: WorkerMcpServerApi,
    private val signedMcpServerConfigValidator: SignedMcpServerConfigValidator,
    private val configStore: McpServerConfigStore
) {

    /**
     * Fetches assigned server configurations from the server and populates the local store.
     *
     * On success, all assigned configurations are cached and available for the runtime.
     * On failure, logs the error but does not block session continuation.
     *
     * @return Either a configuration bootstrap error or `Unit` on success.
     */
    suspend fun bootstrap(): Either<AssignedConfigBootstrapError, Unit> {
        logger.info("Bootstrapping assigned MCP server configurations")
        return mcpServerApi.getAssignedServers()
            .mapLeft { apiError ->
                logger.warn("Failed to fetch assigned MCP servers: {}", apiError)
                AssignedConfigBootstrapError.FetchFailed(apiError)
            }
            .flatMap { servers ->
                try {
                    val authorizedServers = authorizeBootstrapServers(servers)
                    logger.info(
                        "Populating config store with {} verified assigned servers out of {} received",
                        authorizedServers.size,
                        servers.size
                    )
                    configStore.replaceAll(authorizedServers)
                    logger.info("Successfully bootstrapped assigned MCP server configurations")
                    Unit.right()
                } catch (e: Exception) {
                    logger.error("Failed to populate config store with assigned servers", e)
                    AssignedConfigBootstrapError.StoreFailed(e.message ?: "Unknown error").left()
                }
            }
    }

    /**
     * Filters bootstrap payloads down to the configurations that pass worker-side signed-request validation.
     *
     * One bad config must not block other valid configs from loading, so each entry is validated independently
     * and only accepted entries are returned to the caller.
     *
     * @param servers Worker-facing configs fetched from the server bootstrap endpoint.
     * @return Only the relayed server DTOs that the worker can authorize locally.
     */
    private suspend fun authorizeBootstrapServers(servers: List<SignedLocalMCPServerDto>) = buildList {
        servers.forEach { signedServer ->
            when (
                val validation = signedMcpServerConfigValidator.validate(
                    server = signedServer.server,
                    signedRequest = signedServer.signedRequest
                )
            ) {
                SignedMcpServerConfigValidationResult.Authorized -> add(signedServer.server)
                is SignedMcpServerConfigValidationResult.Rejected -> {
                    logger.warn(
                        "Rejected assigned MCP server {} during bootstrap (code={}, message={}, details={})",
                        signedServer.server.id,
                        validation.code,
                        validation.message,
                        validation.details
                    )
                }
            }
        }
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(AssignedConfigBootstrapper::class.java)
    }
}

/**
 * Errors that can occur during assigned configuration bootstrap.
 */
sealed interface AssignedConfigBootstrapError {
    /**
     * Failed to fetch assigned server configurations from the server.
     *
     * @property apiError Underlying API error that occurred.
     */
    data class FetchFailed(val apiError: WorkerMcpServerApiError) : AssignedConfigBootstrapError

    /**
     * Failed to populate the local config store.
     *
     * @property message Error description.
     */
    data class StoreFailed(val message: String) : AssignedConfigBootstrapError
}
