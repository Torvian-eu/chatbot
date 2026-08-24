package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolDefinitionService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.ResetServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolError
import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.UpdateToolError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [ServerBuiltInToolDefinitionService].
 *
 * Coordinates between [ServerBuiltInToolDefinitionDao] and [ServerBuiltInToolDefinitionSeeder] to
 * enforce strict user-ownership validation before returning or modifying server built-in tool
 * definitions. All operations are wrapped in database transactions via [TransactionScope].
 */
class ServerBuiltInToolDefinitionServiceImpl(
    private val serverBuiltInToolDefinitionDao: ServerBuiltInToolDefinitionDao,
    private val serverBuiltInToolDefinitionSeeder: ServerBuiltInToolDefinitionSeeder,
    private val toolService: ToolService,
    private val transactionScope: TransactionScope,
) : ServerBuiltInToolDefinitionService {

    private val logger: Logger = LogManager.getLogger(ServerBuiltInToolDefinitionServiceImpl::class.java)

    override suspend fun getServerBuiltInToolsForUser(userId: Long): List<ServerBuiltInToolDefinition> =
        transactionScope.transaction {
            serverBuiltInToolDefinitionDao.getToolsByUserId(userId)
        }

    override suspend fun updateServerBuiltInTool(
        userId: Long,
        tool: ServerBuiltInToolDefinition
    ): Either<UpdateServerBuiltInToolError, ServerBuiltInToolDefinition> = transactionScope.transaction {
        either {
            // Step 1: Resolve the persisted server built-in tool definition to validate ownership and
            // to recover the immutable fields (id, userId, type, name, timestamps) that the client
            // must not be allowed to change.
            val existing = withError({ _: ServerBuiltInToolDefinitionError.NotFound ->
                UpdateServerBuiltInToolError.ToolNotFound(tool.id)
            }) {
                serverBuiltInToolDefinitionDao.getToolById(tool.id).bind()
            }

            // Step 2: Assert ownership. Server built-in tools are per-user instances, so the owning
            // user must match the authenticated caller. The owner id is not surfaced in the error:
            // a probing user must not be able to confirm the tool exists or learn who owns it.
            ensure(existing.userId == userId) { UpdateServerBuiltInToolError.Forbidden(tool.id) }

            // Step 3: Reconstruct the definition. The public name and the canonical
            // builtInToolName are intentionally NOT taken from the request: the canonical name is
            // the stable dispatch key, and the public name is prefix-derived and seeder-owned (it
            // changes only through the prefix preference). Only the user-editable fields
            // (description, config, schema, enabled) are applied, matching the operator-tool
            // pattern otherwise.
            val updatedDefinition = existing.copy(
                description = tool.description,
                config = tool.config,
                inputSchema = tool.inputSchema,
                outputSchema = tool.outputSchema,
                isEnabled = tool.isEnabled
            )

            val updatedTool = withError({ error: UpdateToolError ->
                when (error) {
                    is UpdateToolError.ToolNotFound -> UpdateServerBuiltInToolError.ToolNotFound(error.id)
                    is UpdateToolError.ValidationError ->
                        UpdateServerBuiltInToolError.ValidationError(error.error)
                }
            }) {
                toolService.updateTool(updatedDefinition).bind() as ServerBuiltInToolDefinition
            }

            logger.info(
                "Updated server built-in tool {} (user {}) isEnabled={}",
                tool.id, userId, updatedTool.isEnabled
            )

            updatedTool
        }
    }

    override suspend fun resetServerBuiltInToolsToDefaults(
        userId: Long
    ): Either<ResetServerBuiltInToolsError, List<ServerBuiltInToolDefinition>> = transactionScope.transaction {
        either {
            withError({ error: SeedServerBuiltInToolsError ->
                ResetServerBuiltInToolsError.SeedFailed(error)
            }) {
                serverBuiltInToolDefinitionSeeder.resetToDefaults(userId).bind()
            }
        }
    }
}
