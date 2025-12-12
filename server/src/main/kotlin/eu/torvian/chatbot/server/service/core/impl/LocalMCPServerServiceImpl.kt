package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.DeleteServerError
import eu.torvian.chatbot.server.service.core.error.mcp.ValidateOwnershipError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of the [LocalMCPServerService] interface.
 * Manages Local MCP Server creation, ID generation, and ownership tracking.
 */
class LocalMCPServerServiceImpl(
    private val localMCPServerDao: LocalMCPServerDao,
    private val localMCPToolDefinitionDao: LocalMCPToolDefinitionDao,
    private val transactionScope: TransactionScope,
) : LocalMCPServerService {

    private val logger: Logger = LogManager.getLogger(LocalMCPServerServiceImpl::class.java)

    override suspend fun createServer(userId: Long, isEnabled: Boolean): Long =
        localMCPServerDao.createServer(userId, isEnabled)


    override suspend fun getServerIdsByUserId(userId: Long): List<Long> =
        localMCPServerDao.getIdsByUserId(userId)


    override suspend fun deleteServer(serverId: Long): Either<DeleteServerError, Unit> =
        transactionScope.transaction {
            either {
                // Step 1: Delete all associated tools first
                val deletedCount = localMCPToolDefinitionDao.deleteToolsByServerId(serverId)
                logger.info("Deleted $deletedCount tools for MCP server $serverId")

                // Step 2: Delete the server itself
                withError({ daoError: DeleteLocalMCPServerError ->
                    when (daoError) {
                        is DeleteLocalMCPServerError.NotFound ->
                            DeleteServerError.ServerNotFound(daoError.id)
                    }
                }) {
                    localMCPServerDao.deleteById(serverId).bind()
                    logger.info("Deleted MCP server $serverId")
                }
            }
        }

    override suspend fun validateOwnership(userId: Long, serverId: Long): Either<ValidateOwnershipError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: LocalMCPServerError.Unauthorized ->
                    when (daoError) {
                        is LocalMCPServerError.Unauthorized ->
                            ValidateOwnershipError.Unauthorized(daoError.userId, daoError.serverId)
                    }
                }) {
                    localMCPServerDao.validateOwnership(userId, serverId).bind()
                    logger.debug("Validated ownership of server $serverId for user $userId")
                }
            }
        }

    override suspend fun setServerEnabled(serverId: Long, isEnabled: Boolean): Either<DeleteServerError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: LocalMCPServerError.NotFound ->
                    DeleteServerError.ServerNotFound(daoError.id)
                }) {
                    localMCPServerDao.setEnabled(serverId, isEnabled).bind()
                    logger.debug("Updated enabled state of server $serverId to $isEnabled")
                }
            }
        }
}
