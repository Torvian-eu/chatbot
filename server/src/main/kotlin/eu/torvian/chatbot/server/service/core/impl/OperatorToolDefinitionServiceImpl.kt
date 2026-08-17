package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.data.dao.OperatorToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError
import eu.torvian.chatbot.server.service.core.OperatorToolDefinitionService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.operator.ResetOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.operator.UpdateOperatorToolError
import eu.torvian.chatbot.server.service.core.error.tool.SeedOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [OperatorToolDefinitionService].
 *
 * Coordinates between [OperatorToolDefinitionDao] and [OperatorToolDefinitionSeeder] to enforce
 * strict user-ownership validation before returning or modifying operator tool definitions. All
 * operations are wrapped in database transactions via [TransactionScope].
 */
class OperatorToolDefinitionServiceImpl(
    private val operatorToolDefinitionDao: OperatorToolDefinitionDao,
    private val operatorToolDefinitionSeeder: OperatorToolDefinitionSeeder,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope,
) : OperatorToolDefinitionService {

    private val logger: Logger = LogManager.getLogger(OperatorToolDefinitionServiceImpl::class.java)

    override suspend fun getOperatorToolsForUser(userId: Long): List<OperatorToolDefinition> =
        transactionScope.transaction {
            operatorToolDefinitionDao.getToolsByUserId(userId)
        }

    override suspend fun updateOperatorTool(
        userId: Long,
        tool: OperatorToolDefinition
    ): Either<UpdateOperatorToolError, OperatorToolDefinition> = transactionScope.transaction {
        either {
            // Step 1: Resolve the persisted operator tool definition to validate ownership and to
            // recover the immutable fields (id, userId, type, timestamps) that the client must not
            // be allowed to change.
            val existing = withError({ _: OperatorToolDefinitionError.NotFound ->
                UpdateOperatorToolError.ToolNotFound(tool.id)
            }) {
                operatorToolDefinitionDao.getToolById(tool.id).bind()
            }

            // Step 2: Assert ownership. Operator tools are per-user instances, so the owning user
            // must match the authenticated caller.
            if (existing.userId != userId) {
                raise(UpdateOperatorToolError.Forbidden(tool.id, existing.userId))
            }

            // Step 3: Reconstruct the definition, preserving the immutable identity fields and
            // applying only the user-editable fields from the request (name/description/config/
            // schema/enabled). The base row update goes through the shared ToolService, which
            // validates and refreshes the timestamp.
            val updatedDefinition = existing.copy(
                name = tool.name,
                description = tool.description,
                config = tool.config,
                inputSchema = tool.inputSchema,
                outputSchema = tool.outputSchema,
                isEnabled = tool.isEnabled
            )

            val updatedTool = withError({ error: UpdateToolError ->
                when (error) {
                    is UpdateToolError.ToolNotFound -> UpdateOperatorToolError.ToolNotFound(error.id)
                    is UpdateToolError.ValidationError ->
                        UpdateOperatorToolError.ValidationError(error.error)
                }
            }) {
                toolService.updateTool(updatedDefinition).bind() as OperatorToolDefinition
            }

            logger.info(
                "Updated operator tool {} (user {}) isEnabled={}",
                tool.id, userId, updatedTool.isEnabled
            )

            updatedTool
        }
    }

    override suspend fun resetOperatorToolsToDefaults(
        userId: Long
    ): Either<ResetOperatorToolsError, List<OperatorToolDefinition>> = transactionScope.transaction {
        either {
            withError({ error: SeedOperatorToolsError ->
                ResetOperatorToolsError.SeedFailed(error)
            }) {
                operatorToolDefinitionSeeder.resetToDefaults(userId).bind()
            }
        }
    }
}
