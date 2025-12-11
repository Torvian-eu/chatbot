package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.error.tool.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import eu.torvian.chatbot.server.data.dao.error.SetToolEnabledError as DaoSetToolEnabledError

/**
 * Implementation of the [ToolService] interface.
 * Manages tool definitions and session-specific tool configurations.
 */
class ToolServiceImpl(
    private val toolDefinitionDao: ToolDefinitionDao,
    private val sessionToolConfigDao: SessionToolConfigDao,
    private val localMCPToolDefinitionDao: LocalMCPToolDefinitionDao,
    private val transactionScope: TransactionScope,
) : ToolService {

    private val logger: Logger = LogManager.getLogger(ToolServiceImpl::class.java)

    override suspend fun getAllTools(): List<MiscToolDefinition> {
        return transactionScope.transaction {
            toolDefinitionDao.getAllToolDefinitions()
        }
    }

    override suspend fun getToolById(id: Long): Either<GetToolError, MiscToolDefinition> =
        transactionScope.transaction {
            either {
                withError({ daoError: ToolDefinitionError.NotFound ->
                    GetToolError.ToolNotFound(daoError.id)
                }) {
                    toolDefinitionDao.getToolDefinitionById(id).bind()
                }
            }
        }

    override suspend fun createTool(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): Either<ValidateToolError, MiscToolDefinition> = transactionScope.transaction {
        either {
            // Validate tool definition
            validateToolDefinition(name, description, inputSchema, outputSchema).bind()

            // Persist tool definition
            toolDefinitionDao.insertToolDefinition(
                name = name,
                description = description,
                type = type,
                config = config,
                inputSchema = inputSchema,
                outputSchema = outputSchema,
                isEnabled = isEnabled
            )
        }
    }

    override suspend fun updateTool(tool: ToolDefinition): Either<UpdateToolError, ToolDefinition> =
        transactionScope.transaction {
            either {
                // Validate tool definition
                withError({ error: ValidateToolError ->
                    UpdateToolError.ValidationError(error)
                }) {
                    validateToolDefinition(tool).bind()
                }

                // Update timestamp
                val updatedTool = tool.withUpdatedAt(Clock.System.now())

                // Update tool definition
                withError({ daoError: ToolDefinitionError.NotFound ->
                    UpdateToolError.ToolNotFound(daoError.id)
                }) {
                    toolDefinitionDao.updateToolDefinition(updatedTool).bind()
                }

                updatedTool
            }
        }

    override suspend fun deleteTool(id: Long): Either<DeleteToolError, Unit> =
        transactionScope.transaction {
            either {
                // Delete tool definition
                withError({ daoError: ToolDefinitionError.NotFound ->
                    DeleteToolError.ToolNotFound(daoError.id)
                }) {
                    toolDefinitionDao.deleteToolDefinition(id).bind()
                }
            }
        }

    override suspend fun getEnabledToolsForSession(sessionId: Long): List<ToolDefinition> {
        return transactionScope.transaction {
            sessionToolConfigDao.getEnabledToolsForSession(sessionId)
                // TODO: for better performance, modify sessionToolConfigDao.getEnabledToolsForSession to return polymorphic ToolDefinition
            .map { toolDefinition ->
                if (toolDefinition.type == ToolType.MCP_LOCAL) {
                    localMCPToolDefinitionDao.getToolById(toolDefinition.id).getOrElse {
                        throw IllegalStateException("Failed to retrieve local MCP tool definition")
                    }
                } else {
                    toolDefinition
                }
            }
        }
    }

    override suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolId: Long,
        enabled: Boolean
    ): Either<SetToolEnabledError, Unit> = transactionScope.transaction {
        either {
            // Verify tool exists
            withError({ _: ToolDefinitionError.NotFound ->
                SetToolEnabledError.ToolNotFound(toolId)
            }) {
                toolDefinitionDao.getToolDefinitionById(toolId).bind()
            }

            // Set tool enabled state for session
            withError({ daoError: DaoSetToolEnabledError ->
                when (daoError) {
                    is DaoSetToolEnabledError.ForeignKeyViolation -> {
                        // Try to determine if it's a session or tool issue
                        // Check if tool exists (we already did this above, so this shouldn't fail)
                        // This must be a session issue
                        SetToolEnabledError.SessionNotFound(sessionId)
                    }
                }
            }) {
                sessionToolConfigDao.setToolEnabledForSession(sessionId, toolId, enabled).bind()
            }

            logger.info("Tool $toolId ${if (enabled) "enabled" else "disabled"} for session $sessionId")
        }
    }

    override suspend fun validateToolDefinition(tool: ToolDefinition): Either<ValidateToolError, Unit> = either {
        validateToolDefinition(
            tool.name,
            tool.description,
            tool.inputSchema,
            tool.outputSchema
        ).bind()
    }

    override suspend fun validateToolDefinition(
        name: String,
        description: String,
        inputSchema: JsonObject,
        outputSchema: JsonObject?
    ): Either<ValidateToolError, Unit> = either {
        // Validate name
        ensure(name.isNotBlank()) {
            ValidateToolError.InvalidName("Tool name cannot be blank.", name)
        }
        ensure(name.length <= 255) {
            ValidateToolError.InvalidName("Tool name cannot exceed 255 characters.", name)
        }

        // Validate description
        ensure(description.isNotBlank()) {
            ValidateToolError.InvalidDescription("Tool description cannot be blank.", description)
        }

        // Validate input schema
        ensure(inputSchema.containsKey("type") || inputSchema.containsKey("properties")) {
            ValidateToolError.InvalidInputSchema(
                message = "Input schema must be a valid JSON Schema with 'type' or 'properties'.",
                schema = inputSchema
            )
        }

        // Validate output schema if provided
        if (outputSchema != null) {
            ensure(outputSchema.containsKey("type") || outputSchema.containsKey("properties")) {
                ValidateToolError.InvalidOutputSchema(
                    message = "Output schema must be a valid JSON Schema with 'type' or 'properties'.",
                    schema = outputSchema
                )
            }
        }
    }

    override suspend fun getToolsForUser(userId: Long): List<ToolDefinition> =
        transactionScope.transaction {
            toolDefinitionDao.getToolsForUser(userId)
        }
}

