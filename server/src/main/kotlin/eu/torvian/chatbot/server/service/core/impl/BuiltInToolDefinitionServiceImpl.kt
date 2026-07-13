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
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.service.core.BuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.builtin.GetBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.builtin.ResetBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.builtin.UpdateBuiltInToolError
import eu.torvian.chatbot.server.service.core.error.tool.SeedBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

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
    private val builtInToolDefinitionSeeder: BuiltInToolDefinitionSeeder,
    private val toolService: ToolService,
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
        tool: BuiltInWorkerToolDefinition
    ): Either<UpdateBuiltInToolError, BuiltInWorkerToolDefinition> = transactionScope.transaction {
        either {
            // Step 1: Resolve the persisted built-in tool definition to validate ownership
            // and to recover the immutable fields (workerId, builtInToolName) that the client
            // must not be allowed to change.
            val existing = withError({ _: BuiltInToolDefinitionError.NotFound ->
                UpdateBuiltInToolError.ToolNotFound(tool.id)
            }) {
                builtInToolDefinitionDao.getToolById(tool.id).bind()
            }

            // Step 2: Verify the worker exists and the user owns it.
            val worker = withError({ _: WorkerError.NotFound ->
                UpdateBuiltInToolError.ToolNotFound(tool.id)
            }) {
                workerDao.getWorkerById(existing.workerId).bind()
            }

            if (worker.ownerUserId != userId) {
                raise(UpdateBuiltInToolError.Forbidden(existing.workerId, worker.ownerUserId))
            }

            // Step 3: Reconstruct the definition, preserving the immutable identity fields
            // (id, workerId, builtInToolName, type, timestamps) and applying only the
            // administrator-editable fields from the request.
            val updatedDefinition = existing.copy(
                name = tool.name,
                description = tool.description,
                config = tool.config,
                inputSchema = tool.inputSchema,
                outputSchema = tool.outputSchema,
                isEnabled = tool.isEnabled
            )

            // Step 4: Validate and persist via the shared ToolService, mapping its typed
            // errors into the built-in tool error hierarchy.
            val updatedTool = withError({ error: UpdateToolError ->
                when (error) {
                    is UpdateToolError.ToolNotFound -> UpdateBuiltInToolError.ToolNotFound(error.id)
                    is UpdateToolError.ValidationError ->
                        UpdateBuiltInToolError.ValidationError(error.error)
                }
            }) {
                toolService.updateTool(updatedDefinition).bind() as BuiltInWorkerToolDefinition
            }

            logger.info(
                "Updated built-in tool {} (worker {}) isEnabled={}",
                tool.id, existing.workerId, updatedTool.isEnabled
            )

            updatedTool
        }
    }

    override suspend fun resetBuiltInToolsToDefaults(
        userId: Long,
        workerId: Long
    ): Either<ResetBuiltInToolsError, List<BuiltInWorkerToolDefinition>> = transactionScope.transaction {
        either {
            // Step 1: Verify the worker exists and belongs to the calling user.
            val worker = withError({ _: WorkerError.NotFound ->
                ResetBuiltInToolsError.WorkerNotFound(workerId)
            }) {
                workerDao.getWorkerById(workerId).bind()
            }

            if (worker.ownerUserId != userId) {
                raise(ResetBuiltInToolsError.Forbidden(workerId, worker.ownerUserId))
            }

            // Step 2: Reconcile the worker's tools with the catalog, preserving enabled state and
            // approval preferences. The seeder reads the worker's current prefix so public names
            // stay consistent with the worker configuration.
            withError({ error: SeedBuiltInToolsError ->
                ResetBuiltInToolsError.SeedFailed(error)
            }) {
                builtInToolDefinitionSeeder.resetToDefaults(workerId, worker.toolNamePrefix).bind()
            }
        }
    }
}
