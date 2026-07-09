package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.data.dao.BuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.BuiltInToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.service.core.BuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.error.builtin.GetBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.builtin.UpdateBuiltInToolError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Implementation of [BuiltInToolDefinitionService].
 *
 * Coordinates between [WorkerDao], [BuiltInToolDefinitionDao], and [ToolDefinitionDao]
 * to enforce strict worker-ownership validation before returning or modifying built-in
 * tool definitions. All operations are wrapped in database transactions via [TransactionScope].
 */
class BuiltInToolDefinitionServiceImpl(
    private val workerDao: WorkerDao,
    private val builtInToolDefinitionDao: BuiltInToolDefinitionDao,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val transactionScope: TransactionScope,
) : BuiltInToolDefinitionService {

    private val logger: Logger = LogManager.getLogger(BuiltInToolDefinitionServiceImpl::class.java)

    override suspend fun getBuiltInToolsForWorker(
        userId: Long,
        workerId: Long
    ): Either<GetBuiltInToolsError, List<BuiltInWorkerToolDefinition>> = transactionScope.transaction {
        either {
            // Step 1: Verify the worker exists and belongs to the calling user.
            val worker = withError({ _: WorkerError.NotFound ->
                GetBuiltInToolsError.WorkerNotFound(workerId)
            }) {
                workerDao.getWorkerById(workerId).bind()
            }

            // Step 2: Assert ownership.
            if (worker.ownerUserId != userId) {
                raise(GetBuiltInToolsError.Forbidden(workerId, worker.ownerUserId))
            }

            // Step 3: Return all built-in tools for this worker.
            builtInToolDefinitionDao.getToolsByWorkerId(workerId)
        }
    }

    override suspend fun updateBuiltInTool(
        userId: Long,
        toolId: Long,
        isEnabled: Boolean
    ): Either<UpdateBuiltInToolError, BuiltInWorkerToolDefinition> = transactionScope.transaction {
        either {
            // Step 1: Resolve the built-in tool definition.
            val tool = withError({ _: BuiltInToolDefinitionError.NotFound ->
                UpdateBuiltInToolError.ToolNotFound(toolId)
            }) {
                builtInToolDefinitionDao.getToolById(toolId).bind()
            }

            // Step 2: Verify the worker exists and the user owns it.
            val worker = withError({ _: WorkerError.NotFound ->
                UpdateBuiltInToolError.ToolNotFound(toolId)
            }) {
                workerDao.getWorkerById(tool.workerId).bind()
            }

            if (worker.ownerUserId != userId) {
                raise(UpdateBuiltInToolError.Forbidden(tool.workerId, worker.ownerUserId))
            }

            // Step 3: Update only the enabled state via the base tool-definition DAO.
            val updatedTool = tool.copy(
                isEnabled = isEnabled,
                updatedAt = Clock.System.now()
            )

            withError({ _: ToolDefinitionError.NotFound ->
                UpdateBuiltInToolError.ToolNotFound(toolId)
            }) {
                toolDefinitionDao.updateToolDefinition(updatedTool).bind()
            }

            logger.info("Updated built-in tool {} (worker {}) isEnabled={}", toolId, tool.workerId, isEnabled)

            updatedTool
        }
    }
}
