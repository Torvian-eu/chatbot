package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.DeleteServerError
import eu.torvian.chatbot.server.service.core.error.mcp.ValidateOwnershipError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of the [LocalMCPServerService] interface.
 * Manages Local MCP Server ID generation and ownership tracking.
 */
class LocalMCPServerServiceImpl(
    private val localMCPServerDao: LocalMCPServerDao,
    private val transactionScope: TransactionScope,
) : LocalMCPServerService {

    private val logger: Logger = LogManager.getLogger(LocalMCPServerServiceImpl::class.java)

    override suspend fun generateServerId(userId: Long): Long =
        localMCPServerDao.generateId(userId)


    override suspend fun getServerIdsByUserId(userId: Long): List<Long> =
        localMCPServerDao.getIdsByUserId(userId)


    override suspend fun deleteServer(serverId: Long): Either<DeleteServerError, Unit> =
        transactionScope.transaction {
            either {
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
}
