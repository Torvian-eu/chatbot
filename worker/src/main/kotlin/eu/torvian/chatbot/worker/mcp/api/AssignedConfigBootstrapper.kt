package eu.torvian.chatbot.worker.mcp.api

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.worker.mcp.McpServerConfigStore
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
 * @property configStore Local config store to populate with assigned servers.
 */
class AssignedConfigBootstrapper(
    private val mcpServerApi: WorkerMcpServerApi,
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
                    logger.info("Populating config store with {} assigned servers", servers.size)
                    configStore.replaceAll(servers)
                    logger.info("Successfully bootstrapped assigned MCP server configurations")
                    Unit.right()
                } catch (e: Exception) {
                    logger.error("Failed to populate config store with assigned servers", e)
                    AssignedConfigBootstrapError.StoreFailed(e.message ?: "Unknown error").left()
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
