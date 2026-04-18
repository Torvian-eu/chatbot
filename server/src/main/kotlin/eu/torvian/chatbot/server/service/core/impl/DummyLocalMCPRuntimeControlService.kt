package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.server.service.core.LocalMCPRuntimeControlService
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPRuntimeControlError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPRuntimeControlInternalError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPRuntimeControlServerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPRuntimeControlUnauthorizedError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerNotFoundError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerServiceError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerUnauthorizedError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Temporary runtime-control implementation used during control-plane migration.
 *
 * This implementation validates user ownership and then returns deterministic placeholder
 * responses. It does not dispatch to workers and does not execute MCP runtime processes.
 *
 * @property localMCPServerService Service used for ownership and existence validation.
 */
class DummyLocalMCPRuntimeControlService(
    private val localMCPServerService: LocalMCPServerService
) : LocalMCPRuntimeControlService {
    /**
     * Logger used to make dummy behavior explicit in server logs.
     */
    private val logger: Logger = LogManager.getLogger(DummyLocalMCPRuntimeControlService::class.java)

    override suspend fun startServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit> = either {
        validateOwnership(userId, serverId).bind()
        logger.info("Dummy runtime-control start accepted for server {}", serverId)
    }


    override suspend fun stopServer(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit> = either {
        validateOwnership(userId, serverId).bind()
        logger.info("Dummy runtime-control stop accepted for server {}", serverId)
    }

    override suspend fun testConnection(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, TestLocalMCPServerConnectionResponse> = either {
        validateOwnership(userId, serverId).bind()
        logger.info("Dummy runtime-control test-connection accepted for server {}", serverId)
        TestLocalMCPServerConnectionResponse(
            serverId = serverId,
            success = true,
            discoveredToolCount = 3,
            message = DUMMY_MESSAGE
        )
    }

    override suspend fun refreshTools(
        userId: Long,
        serverId: Long
    ): Either<LocalMCPRuntimeControlError, RefreshMCPToolsResponse> = either {
        validateOwnership(userId, serverId).bind()
        logger.info("Dummy runtime-control refresh-tools accepted for server {}", serverId)
        RefreshMCPToolsResponse(
            addedTools = emptyList(),
            updatedTools = emptyList(),
            deletedTools = emptyList()
        )
    }

    /**
     * Ensures the authenticated user owns the target server before runtime control is executed.
     *
     * @param userId Authenticated user identifier.
     * @param serverId Target server identifier.
     * @return Either runtime-control error or Unit when ownership is valid.
     */
    private suspend fun validateOwnership(userId: Long, serverId: Long): Either<LocalMCPRuntimeControlError, Unit> =
        either {
            withError({ error: LocalMCPServerServiceError -> error.toRuntimeControlError() }) {
                localMCPServerService.validateOwnership(userId, serverId).bind()
            }
        }

    /**
     * Maps server-configuration service errors into runtime-control errors.
     *
     * @receiver Upstream Local MCP server service error.
     * @return Runtime-control error suitable for route-level API mapping.
     */
    private fun LocalMCPServerServiceError.toRuntimeControlError(): LocalMCPRuntimeControlError = when (this) {
        is LocalMCPServerNotFoundError -> LocalMCPRuntimeControlServerNotFoundError(serverId)
        is LocalMCPServerUnauthorizedError -> LocalMCPRuntimeControlUnauthorizedError(userId, serverId)
        else -> LocalMCPRuntimeControlInternalError("Failed to validate server ownership for runtime control")
    }

    /**
     * Companion object containing stable constants for dummy behavior.
     */
    private companion object {
        /**
         * Stable placeholder message returned by dummy runtime-control responses.
         */
        private const val DUMMY_MESSAGE: String =
            "Dummy MCP runtime control implementation; real worker dispatch not yet enabled"
    }
}



